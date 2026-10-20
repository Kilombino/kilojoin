package com.kilombino.kilojoin

import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.coinjoin.RelayClient
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The node's coinjoin side: the same pools, protocol and relay as the Kilowallet app
 * ([Protocol.RELAY]), so phones and nodes meet in the same rounds. Keeps this node's rounds
 * (persisted), lists the open pools, and records what happened as events for the web page and
 * for StartOS notifications.
 */
class Engine(private val dir: File, private val rpc: Rpc, private val walletProvider: () -> List<Wallet>) {
    private val sessionsFile = File(dir, "sessions.json")
    private val settingsFile = File(dir, "settings.json")
    private val sessions = ConcurrentHashMap<String, PoolSession>()

    data class Event(val at: Long, val title: String, val text: String, val pool: String?)
    val events = CopyOnWriteArrayList<Event>()
    /** Called for every event (StartOS notifications hook in here). */
    @Volatile var onEvent: (Event) -> Unit = {}
    /** The unlocked wallet's words, for signing on its own (null while locked). */
    @Volatile var secretProvider: () -> Vault.Secret? = { null }

    private fun sats(v: Long) = "%,d".format(v).replace(',', ' ')

    // ------------------------------------------------------------------ settings

    fun settings(): JSONObject = if (settingsFile.exists()) JSONObject(settingsFile.readText()) else JSONObject()
    fun setSetting(k: String, v: Any) { val o = settings(); o.put(k, v); settingsFile.writeText(o.toString()) }
    val notifyNewPools: Boolean get() = settings().optBoolean("notify_new_pools", true)
    /**
     * Accept close requests and sign by itself when the transaction checks out (on by default,
     * as in the app): unlocking to open or join a pool is the only time it asks.
     */
    val autoSign: Boolean get() = settings().optBoolean("auto_sign", true)

    /**
     * Keys of the coins of each live round on its own, taken when it was opened or joined, so it
     * signs even after Kilojoin is locked. Only in memory: after a restart it signs once unlocked.
     */
    private val roundKeys = ConcurrentHashMap<String, List<java.math.BigInteger>>()

    /** Telegram, through the user's own bot: every event also arrives there, with sound. */
    fun telegram(text: String): Result<Unit> = runCatching {
        val token = settings().optString("telegram_token").trim(); val chat = settings().optString("telegram_chat").trim()
        if (token.isEmpty() || chat.isEmpty()) return@runCatching
        val c = java.net.URL("https://api.telegram.org/bot$token/sendMessage").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("chat_id", chat).put("text", text).toString().toByteArray()) }
        val code = c.responseCode
        if (code != 200) error("Telegram answered $code: " + (c.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""))
    }

    /**
     * Scripts of this wallet's mixed outputs (hex), kept in settings so a coin stays marked
     * "mixed" after its round is removed from the list. Spending one together with unmixed
     * coins would tie them back together, so the wallet warns about it.
     */
    @Synchronized
    fun mixedScripts(): Set<String> {
        val a = settings().optJSONArray("mixed_scripts") ?: JSONArray()
        val saved = (0 until a.length()).map { a.getString(it) }
        val live = sessions.values.map { it.state }
            .filter { it.phase == PoolSession.Phase.BROADCAST || it.phase == PoolSession.Phase.CONFIRMED }
            .flatMap { st -> st.mixScripts.map { it.toHex() } }
        return (saved + live).toSet()
    }

    @Synchronized
    private fun rememberMixed(scriptHex: Collection<String>) {
        val all = mixedScripts() + scriptHex
        setSetting("mixed_scripts", JSONArray(all.toList()))
    }

    /** Rescan the wallet off the caller's thread (after a round goes out or confirms). */
    private fun rescanSoon() { Thread { walletProvider().forEach { w -> runCatching { w.scan() } } }.start() }

    // ------------------------------------------------------------------ persistence

    @Synchronized
    private fun persist() {
        val a = JSONArray(); sessions.values.forEach { a.put(it.state.toJson()) }
        val tmp = File(dir, "sessions.json.tmp"); tmp.writeText(a.toString()); tmp.renameTo(sessionsFile)
    }

    fun load() {
        if (!sessionsFile.exists()) return
        val weekAgo = System.currentTimeMillis() / 1000 - 7 * 86400
        val a = runCatching { JSONArray(sessionsFile.readText()) }.getOrNull() ?: return
        for (i in 0 until a.length()) {
            val st = runCatching { PoolSession.State.parse(a.getJSONObject(i)) }.getOrNull() ?: continue
            val over = st.phase in setOf(PoolSession.Phase.CONFIRMED, PoolSession.Phase.ABORTED, PoolSession.Phase.REJECTED)
            if (over && st.created < weekAgo) continue
            sessions[st.poolId] = PoolSession(env(st), st)
        }
    }

    fun startAll() = sessions.values.filter { !it.done }.forEach { s -> Thread { runCatching { s.start() } }.start() }

    fun states(): List<PoolSession.State> = sessions.values.map { it.state }.sortedByDescending { it.created }
    fun session(id: String): PoolSession? = sessions[id]
    fun remove(id: String) { sessions.remove(id)?.stop(); roundKeys.remove(id); persist() }
    fun lockedOutpoints(): Set<String> = sessions.values.filter { !it.done && it.state.phase != PoolSession.Phase.BROADCAST }
        .flatMap { s -> s.state.seat.coins.map { it.outpoint } }.toSet()

    // ------------------------------------------------------------------ joining

    private fun add(st: PoolSession.State, keys: List<java.math.BigInteger>): PoolSession {
        if (autoSign) roundKeys[st.poolId] = keys
        val s = PoolSession(env(st), st)
        sessions[st.poolId] = s; persist()
        Thread { runCatching { s.start() } }.start()
        return s
    }

    /**
     * A seat's coins (all of [w]'s type) with their keys, and fresh scripts from [w]: one per mixed
     * output the pool gives each person (several in Kilojoin v4), and the change.
     */
    private class Picked(val coins: List<CoinjoinTx.Coin>, val keys: List<java.math.BigInteger>, val paths: List<String>,
                         val mixes: List<ByteArray>, val change: ByteArray)

    private fun pick(secret: Vault.Secret, w: Wallet, cs: List<Wallet.Coin>, outputs: Int): Picked {
        val master = Wallet.master(secret)
        return pickNodes(w, cs, cs.map { Bip32Priv.derivePath(master, it.path) }, outputs)
    }

    private fun pickNodes(w: Wallet, cs: List<Wallet.Coin>, nodes: List<Bip32Priv.ExtendedPrivKey>, outputs: Int): Picked {
        require(cs.isNotEmpty()) { "pick a coin" }
        require(cs.all { it.type == w.type }) { "all the coins must be of the pool's address type" }
        cs.indices.forEach { require(nodes[it].publicKey().contentEquals(w.pubkey(cs[it].branch, cs[it].index))) { "these words do not match this wallet's key" } }
        val coins = cs.indices.map { CoinjoinTx.Coin(cs[it].txid, cs[it].vout, cs[it].value, nodes[it].publicKey(), w.type) }
        return Picked(coins, nodes.map { it.key }, cs.map { it.path }, (0 until outputs).map { w.fresh(0).second }, w.fresh(1).second)
    }

    fun create(secret: Vault.Secret, w: Wallet, cs: List<Wallet.Coin>, amount: Long, feeRate: Double, minPeers: Int, maxPeers: Int,
               hours: Int, password: String?, maxInputs: Int = 1, outputs: Int = 1): PoolSession.State {
        // Kilojoin v3 (blind outputs), like the app since 1.1.5; v4 with several outputs each (1.2.0).
        val np = PoolSession.newPool(amount, feeRate, maxPeers, hours, minPeers, !password.isNullOrEmpty(),
            w.type, maxInputs, v3 = true, outputs = outputs)
        val p = pick(secret, w, cs, outputs)
        return add(PoolSession.newState(np.terms, true, np.secret, p.coins, p.keys, p.paths, p.mixes, p.change,
            password?.ifEmpty { null }, np.creatorSecret, np.blindPriv), p.keys).state
    }

    fun join(secret: Vault.Secret, w: Wallet, cs: List<Wallet.Coin>, terms: Protocol.Terms, password: String?): PoolSession.State {
        require(w.type == terms.script) { "this pool takes ${if (terms.script == com.kilombino.pyblockwatch.crypto.ScriptType.P2TR) "Taproot" else "native SegWit"} coins only" }
        require(cs.size <= terms.maxInputs) { "this pool takes at most ${terms.maxInputs} coin(s) per person" }
        return joinWith(pick(secret, w, cs, terms.outputs), terms, password)
    }

    private fun joinWith(p: Picked, terms: Protocol.Terms, password: String?): PoolSession.State {
        require(terms.id !in sessions.keys || sessions[terms.id]!!.done) { "you are already in this pool" }
        require(p.mixes.size == terms.outputs) { "this pool gives ${terms.outputs} mixed outputs each" }
        return add(PoolSession.newState(terms, false, null, p.coins, p.keys, p.paths, p.mixes, p.change,
            password?.ifEmpty { null }), p.keys).state
    }

    // ------------------------------------------------------------------ the perfect coin, then the pool

    /**
     * A pool waiting for its perfect coin (output [vout] of [txid], a payment to ourselves). At its
     * first confirmation the pool is joined, or opened, by itself with the coin's key, taken when
     * the payment was signed. In memory only; after a restart the coin waits for a join by hand.
     */
    class AfterExact(val txid: String, val vout: Int, val value: Long, val type: com.kilombino.pyblockwatch.crypto.ScriptType,
                     val index: Int, val node: Bip32Priv.ExtendedPrivKey, val poolId: String?, val poolPassword: String?,
                     val create: JSONObject?, val since: Long = System.currentTimeMillis() / 1000)
    private val afterExact = ConcurrentHashMap<String, AfterExact>()

    fun expectExact(a: AfterExact) { afterExact["${a.txid}:${a.vout}"] = a }
    fun waitingExact(): List<AfterExact> = afterExact.values.toList()

    private fun checkExact() {
        val now = System.currentTimeMillis() / 1000
        for ((op, a) in afterExact) {
            if (now - a.since > 3 * 86400) { afterExact.remove(op); continue }
            val o = runCatching { rpc.txOut(a.txid, a.vout) }.getOrNull() ?: continue
            if (o.optInt("confirmations") < 1) continue
            val w = walletProvider().firstOrNull { it.type == a.type } ?: continue
            afterExact.remove(op)
            runCatching {
                val coin = Wallet.Coin(a.txid, a.vout, a.value, rpc.height() - o.getInt("confirmations") + 1, 0, a.index, w.address(0, a.index), a.type)
                val c = a.create
                val outputs = c?.optInt("outputs", 1) ?: (fetchPools().firstOrNull { it.id == a.poolId }?.outputs ?: 1)
                val p = pickNodes(w, listOf(coin), listOf(a.node), outputs)
                if (c != null) {
                    val np = PoolSession.newPool(c.getLong("amount"), c.getDouble("feeRate"), c.getInt("maxPeers"), c.getInt("hours"),
                        c.getInt("minPeers"), c.optString("password").isNotEmpty(), w.type, c.optInt("maxInputs", 1), v3 = true, outputs = outputs)
                    add(PoolSession.newState(np.terms, true, np.secret, p.coins, p.keys, p.paths, p.mixes, p.change, c.optString("password").ifEmpty { null },
                        np.creatorSecret, np.blindPriv), p.keys).state
                } else {
                    val t = fetchPools().firstOrNull { it.id == a.poolId } ?: error("that pool is no longer open")
                    joinWith(p, t, a.poolPassword)
                }
            }.onSuccess { emit("Coinjoin: perfect coin in", "${sats(a.value)} sats confirmed: " + (if (a.create != null) "pool opened" else "asked to join") + " on its own", it.poolId) }
             .onFailure { emit("Coinjoin: perfect coin ready", "${sats(a.value)} sats confirmed, but ${it.message}. Join a pool with it.", null) }
        }
    }

    fun sign(secret: Vault.Secret, poolId: String) {
        val s = sessions[poolId] ?: error("no such pool")
        val master = Wallet.master(secret)
        s.sign(s.state.coinPaths.map { Bip32Priv.derivePath(master, it).key })
    }

    // ------------------------------------------------------------------ open pools

    fun fetchPools(sinceSeconds: Long = 3 * 86400): List<Protocol.Terms> {
        val found = ConcurrentHashMap<String, Protocol.Terms>()
        val r = RelayClient(Protocol.RELAY, { _, ev ->
            Protocol.Terms.parse(ev)?.let { t -> if ((found[t.id]?.createdAt ?: 0) < t.createdAt) found[t.id] = t }
        })
        try {
            r.connect()
            r.subscribe("pools", listOf(JSONObject().put("kinds", JSONArray().put(Protocol.KIND_POOL))
                .put("#t", JSONArray().put(Protocol.TAG)).put("since", System.currentTimeMillis() / 1000 - sinceSeconds)))
        } finally { r.close() }
        val now = System.currentTimeMillis() / 1000
        // A pool not re-announced for a while has lost its creator: nobody would let us in.
        return found.values.filter {
            it.state == "open" && it.expiresAt > now && it.peers < it.maxPeers && it.amount >= Protocol.MIN_AMOUNT &&
                now - it.createdAt < Protocol.STALE_AFTER
        }
            .sortedByDescending { it.createdAt }
    }

    /** Every few minutes: tell about public pools opened since the last look. */
    fun startPoolWatcher() {
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({
            runCatching {
                if (!notifyNewPools) return@runCatching
                val last = settings().optLong("last_pool_seen", System.currentTimeMillis() / 1000 - 3600)
                val pools = fetchPools(86400).filter { it.createdAt > last && it.id !in sessions.keys && !it.private }
                pools.maxOfOrNull { it.createdAt }?.let { setSetting("last_pool_seen", it) }
                pools.take(3).forEach { t -> emit("New coinjoin pool",
                    "${sats(t.amount)} sats${if (t.script == com.kilombino.pyblockwatch.crypto.ScriptType.P2TR) " · Taproot" else ""} · " +
                        "${t.peers}/${t.maxPeers} people · ${t.feeRate} sat/vB", t.id) }
            }
        }, 20, 300, TimeUnit.SECONDS)
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay({ runCatching { checkExact() } }, 30, 30, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ events

    private fun emit(title: String, text: String, pool: String?) {
        val e = Event(System.currentTimeMillis(), title, text, pool)
        Thread { telegram("$title\n$text") }.start()
        events.add(0, e); while (events.size > 200) events.removeAt(events.size - 1)
        runCatching { onEvent(e) }
    }

    private fun env(st: PoolSession.State): PoolSession.Env = object : PoolSession.Env {
        override fun coinUnspent(coin: CoinjoinTx.Coin): Boolean {
            val o = rpc.txOut(coin.txid, coin.vout) ?: return false
            // The coin's own script too: a coin claimed under another address type does not match.
            return Math.round(o.getDouble("value") * 1e8) == coin.value &&
                o.optJSONObject("scriptPubKey")?.optString("hex") == coin.script.toHex()
        }
        override fun broadcast(rawHex: String): String = rpc.call("sendrawtransaction", rawHex) as String
        override fun confirmations(txid: String): Int? {
            // Our mixed output is somewhere in the transaction; any unspent output tells.
            for (i in 0 until 60) { rpc.txOut(txid, i)?.let { return it.optInt("confirmations") } }
            return runCatching { (rpc.call("getrawtransaction", txid, true) as JSONObject).optInt("confirmations") }.getOrNull()
        }
        override fun save(state: PoolSession.State) {
            if (state.phase == PoolSession.Phase.ABORTED && state.reason == PoolSession.LEFT_BY_CHOICE) {
                sessions.remove(state.poolId)?.let { gone -> Thread { runCatching { gone.stop() } }.start() }
            }
            persist()
        }
        override fun event(e: PoolSession.Event) {
            val pool = (if (st.terms.outputs > 1) "${st.terms.outputs} × " else "") + "${sats(st.terms.amount)} sats pool"
            when (e) {
                is PoolSession.Event.Changed -> {}
                is PoolSession.Event.DidAlone -> {}   // the app's way of running on its own; here the events below do it
                is PoolSession.Event.Welcomed -> emit("Coinjoin: you are in", "$pool · waiting for more people", st.poolId)
                is PoolSession.Event.Rejected -> emit("Coinjoin: join refused", e.reason, st.poolId)
                is PoolSession.Event.Joined -> emit("Coinjoin: someone joined", "$pool · ${e.peers}/${st.terms.maxPeers} people", st.poolId)
                is PoolSession.Event.CloseRequested -> if (!e.byMe) {
                    if (autoSign) {
                        Thread { runCatching { Thread.sleep(2000); sessions[st.poolId]?.vote(true) } }.start()
                        emit("Coinjoin: closing accepted", "$pool · ${e.peers} people (accepted on its own)", st.poolId)
                    } else emit("Coinjoin: close now?", "$pool · ${e.peers} people. Accept or refuse.", st.poolId)
                }
                is PoolSession.Event.CloseRefused -> emit("Coinjoin: stays open", "$pool · someone said not yet", st.poolId)
                is PoolSession.Event.Closing -> emit("Coinjoin: closing", "$pool · ${e.peers} people", st.poolId)
                is PoolSession.Event.SignNeeded -> {
                    val secret = secretProvider()
                    val keys = roundKeys[st.poolId]
                    if (autoSign && (keys != null || secret != null)) Thread {
                        Thread.sleep(2000)
                        // sign() checks our mixed output, change and fee first, and refuses otherwise.
                        runCatching { if (keys != null) sessions[st.poolId]!!.sign(keys) else sign(secret!!, st.poolId) }
                            .onSuccess { emit("Coinjoin: signed", "$pool · checked and signed on its own", st.poolId) }
                            .onFailure { emit("Coinjoin: NOT signed", "$pool · ${it.message}. Check it in Kilojoin.", st.poolId) }
                    }.start()
                    else emit("Coinjoin: sign now", "$pool · the transaction is ready to sign" +
                        (if (autoSign) " (Kilojoin restarted: unlock it to sign)" else ""), st.poolId)
                }
                is PoolSession.Event.Broadcast -> { roundKeys.remove(st.poolId); rememberMixed(st.mixScripts.map { it.toHex() }); emit("Coinjoin sent", "$pool · ${e.txid}", st.poolId) }
                is PoolSession.Event.Confirmed -> { rememberMixed(st.mixScripts.map { it.toHex() }); rescanSoon(); emit("Coinjoin confirmed", "$pool · ${e.txid}", st.poolId) }
                is PoolSession.Event.Aborted -> { roundKeys.remove(st.poolId); emit("Coinjoin cancelled", "$pool · ${e.reason}. Your coin did not move.", st.poolId) }
                is PoolSession.Event.ExpiringSoon -> emit("Coinjoin: under an hour left",
                    "$pool · ${e.peers}/${st.terms.maxPeers} people, enough to mix. It expires in ${e.minutes} min: ask to close now.", st.poolId)
            }
        }
        override fun log(msg: String) { System.err.println("[${st.poolId.take(8)}] $msg") }
    }
}
