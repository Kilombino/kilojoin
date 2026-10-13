package com.kilombino.kilojoin

import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Bip39
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * The web page and its JSON API. Everything but the static page needs a session cookie, got
 * by unlocking with the wallet password: on a home network anyone could otherwise read the
 * balance or sign. The decrypted words stay in memory only while unlocked.
 */
class Web(private val dir: File, private val rpc: Rpc, port: Int) {
    private val vault = Vault(dir)
    private val engine: Engine
    @Volatile private var secret: Vault.Secret? = null
    @Volatile private var token: String? = null
    @Volatile private var wallet: Wallet? = null
    /** The seed's Taproot (BIP-86) account, for Taproot pools; set up at the first unlock after 0.5.0. */
    @Volatile private var walletTr: Wallet? = null
    private fun wallets(): List<Wallet> = listOfNotNull(wallet, walletTr)
    private fun walletFor(t: ScriptType): Wallet =
        (if (t == ScriptType.P2TR) walletTr else wallet) ?: error("unlock Kilojoin once so it can work out its Taproot account")
    private fun allCoins(): List<Wallet.Coin> = wallets().flatMap { it.coins }.sortedByDescending { it.value }
    private fun scriptOf(c: Wallet.Coin): String = walletFor(c.type).script(c.branch, c.index).toHex()

    /** Both accounts from the words (BIP-84 and BIP-86), remembered by their public keys. */
    private fun openWallets(sec: Vault.Secret) {
        if (wallet == null) { val x = Wallet.xpubFor(sec); engine.setSetting("xpub", x); wallet = Wallet(dir, rpc, x) }
        if (walletTr == null) {
            val x = Wallet.taprootXpubFor(sec); engine.setSetting("xpub_tr", x)
            walletTr = Wallet(dir, rpc, x, taproot = true).also { w -> Thread { runCatching { w.scan() } }.start() }
        }
    }
    @Volatile private var pools: List<Protocol.Terms> = emptyList()
    private val server = HttpServer.create(InetSocketAddress(port), 0)
    private val rnd = SecureRandom()

    init {
        engine = Engine(dir, rpc) { wallets() }
        engine.secretProvider = { secret }
        engine.settings().optString("xpub").takeIf { it.isNotEmpty() }?.let { wallet = Wallet(dir, rpc, it) }
        engine.settings().optString("xpub_tr").takeIf { it.isNotEmpty() }?.let { walletTr = Wallet(dir, rpc, it, taproot = true) }
        engine.load(); engine.startAll(); engine.startPoolWatcher()
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { ex -> runCatching { route(ex) }.onFailure { e -> reply(ex, 500, err(e.message ?: "error")) } }
    }

    fun start() {
        server.start()
        Thread { wallets().forEach { runCatching { it.scan() } } }.start()
        // Keep the balance current without a click: every 10 minutes (the UTXO scan is cheap).
        Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(
            { wallets().forEach { runCatching { it.scan() } } }, 10, 10, java.util.concurrent.TimeUnit.MINUTES)
    }
    val events get() = engine.events
    fun onEvent(f: (Engine.Event) -> Unit) { engine.onEvent = f }

    // ------------------------------------------------------------------ plumbing

    private fun err(m: String) = JSONObject().put("error", m)
    private fun ok() = JSONObject().put("ok", true)

    private fun reply(ex: HttpExchange, code: Int, body: Any, type: String = "application/json") {
        val bytes = (if (body is ByteArray) body else body.toString().toByteArray())
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("X-Frame-Options", "DENY")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun body(ex: HttpExchange): JSONObject =
        ex.requestBody.bufferedReader().use { it.readText() }.let { if (it.isBlank()) JSONObject() else JSONObject(it) }

    private fun authed(ex: HttpExchange): Boolean {
        val t = token ?: return false
        val c = ex.requestHeaders["Cookie"]?.joinToString(";") ?: return false
        return c.split(';').map { it.trim() }.any { it == "kj=$t" }
    }

    private fun static(ex: HttpExchange, name: String, type: String) {
        val r = javaClass.getResourceAsStream("/web/$name")?.readBytes() ?: return reply(ex, 404, err("not found"))
        reply(ex, 200, r, type)
    }

    // ------------------------------------------------------------------ routes

    private fun route(ex: HttpExchange) {
        val p = ex.requestURI.path
        when {
            p == "/" || p == "/index.html" -> return static(ex, "index.html", "text/html; charset=utf-8")
            p == "/app.js" -> return static(ex, "app.js", "application/javascript; charset=utf-8")
            p == "/style.css" -> return static(ex, "style.css", "text/css; charset=utf-8")
            p == "/api/status" && !authed(ex) -> return reply(ex, 200, JSONObject().put("setup", vault.exists()).put("unlocked", false))
            p == "/api/setup" -> return setup(ex)
            p == "/api/unlock" -> return unlock(ex)
            // For the StartOS side only (same container): events after a timestamp, to notify.
            p == "/internal/events" && ex.remoteAddress.address.isLoopbackAddress -> {
                val since = ex.requestURI.query?.substringAfter("since=")?.toLongOrNull() ?: 0L
                return reply(ex, 200, JSONArray(engine.events.filter { it.at > since }.map { eventJson(it) }))
            }
        }
        if (!authed(ex)) return reply(ex, 401, err("locked"))
        val s = secret
        val w = wallet
        val b = if (ex.requestMethod == "POST") body(ex) else JSONObject()
        when (p) {
            "/api/status" -> reply(ex, 200, status())
            "/api/lock" -> { secret = null; token = null; reply(ex, 200, ok()) }
            "/api/scan" -> { wallets().forEach { it.scan() }; reply(ex, 200, status()) }
            "/api/receive" -> {
                val rw = walletFor(if (ex.requestURI.query?.contains("type=p2tr") == true) ScriptType.P2TR else ScriptType.P2WPKH)
                val (i, a) = rw.receive()
                reply(ex, 200, JSONObject().put("index", i).put("address", a).put("script", rw.script(0, i).toHex())
                    .put("type", Protocol.scriptName(rw.type)))
            }
            "/api/pools" -> { pools = engine.fetchPools(); reply(ex, 200, JSONArray(pools.map { termsJson(it) })) }
            "/api/events" -> reply(ex, 200, JSONArray(engine.events.map { eventJson(it) }))
            "/api/settings" -> {
                if (b.has("notify_new_pools")) engine.setSetting("notify_new_pools", b.getBoolean("notify_new_pools"))
                if (b.has("auto_sign")) engine.setSetting("auto_sign", b.getBoolean("auto_sign"))
                if (b.has("telegram_token")) engine.setSetting("telegram_token", b.getString("telegram_token").trim())
                if (b.has("telegram_chat")) engine.setSetting("telegram_chat", b.getString("telegram_chat").trim())
                reply(ex, 200, settingsJson())
            }
            "/api/telegram/test" -> {
                val r = engine.telegram("Kilojoin: test message. Pool events will arrive here.")
                reply(ex, if (r.isSuccess) 200 else 400, if (r.isSuccess) ok() else err(r.exceptionOrNull()?.message ?: "failed"))
            }
            "/api/create" -> {
                val type = Protocol.parseScript(b.optString("script", "p2wpkh")) ?: error("unknown address type")
                val pw = walletFor(type)
                val cs = coins(pw, b)
                val st = engine.create(s!!, pw, cs, b.getLong("amount"), b.getDouble("feeRate"), b.optInt("minPeers", 2),
                    b.optInt("maxPeers", 5), b.optInt("hours", 6), b.optString("password"), b.optInt("maxInputs", 1))
                reply(ex, 200, JSONObject().put("pool", st.poolId))
            }
            "/api/join" -> {
                val t = pools.firstOrNull { it.id == b.getString("pool") } ?: error("that pool is no longer open")
                val pw = walletFor(t.script)
                engine.join(s!!, pw, coins(pw, b), t, b.optString("password"))
                reply(ex, 200, ok())
            }
            "/api/fee" -> reply(ex, 200, JSONObject().put("suggested", rpc.feeEstimate() ?: JSONObject.NULL))
            "/api/send/preview" -> { val (d, warn) = draft(w!!, b); reply(ex, 200, draftJson(d, warn)) }
            "/api/send" -> {
                val (d, warn) = draft(w!!, b)
                require(warn == null || b.optBoolean("acceptWarning")) { warn!! }
                val change = if (d.change > 0) w!!.fresh(1).second else w!!.nextChangeScript()
                val signed = Spend.sign(s!!, d, change)
                val txid = rpc.call("sendrawtransaction", signed.rawHex) as String
                // The perfect coin of a pool: its key goes with it, so the pool goes on by itself at 1 confirmation.
                b.optJSONObject("after")?.let { after -> runCatching {
                    val t = Protocol.parseScript(b.optString("exactType", "p2wpkh")) ?: error("type")
                    val i = b.getInt("exactIndex"); val ew = walletFor(t)
                    require(ew.address(0, i) == d.toAddress) { "not the exact coin's address" }
                    val raw = rpc.call("decoderawtransaction", signed.rawHex) as JSONObject
                    val outs = raw.getJSONArray("vout")
                    val vout = (0 until outs.length()).first { outs.getJSONObject(it).getJSONObject("scriptPubKey").getString("hex") == d.toScript.toHex() }
                    val node = Bip32Priv.derivePath(Wallet.master(s), "m/${if (t == ScriptType.P2TR) 86 else 84}'/0'/0'/0/$i")
                    engine.expectExact(Engine.AfterExact(txid, vout, d.amount, t, i, node, after.optString("pool").ifEmpty { null },
                        after.optString("poolPassword").ifEmpty { null }, after.optJSONObject("create")))
                } }
                Thread { runCatching { Thread.sleep(3000); w.scan() } }.start()
                reply(ex, 200, JSONObject().put("txid", txid))
            }
            "/api/close" -> { engine.session(b.getString("pool"))!!.requestClose(); reply(ex, 200, ok()) }
            "/api/vote" -> { engine.session(b.getString("pool"))!!.vote(b.getBoolean("accept")); reply(ex, 200, ok()) }
            "/api/sign" -> { engine.sign(s!!, b.getString("pool")); reply(ex, 200, ok()) }
            "/api/leave" -> { engine.session(b.getString("pool"))!!.leave(); reply(ex, 200, ok()) }
            "/api/remove" -> { engine.remove(b.getString("pool")); reply(ex, 200, ok()) }
            else -> reply(ex, 404, err("not found"))
        }
    }

    /**
     * The send asked for in [b] (coins, address, amount or all, fee rate), and a warning when it
     * would undo a mix: a mixed coin spent with any other coin ties them together again.
     */
    private fun draft(w: Wallet, b: JSONObject): Pair<Spend.Draft, String?> {
        val picked = b.getJSONArray("coins").let { a -> (0 until a.length()).map { a.getString(it) } }
        val locked = engine.lockedOutpoints()
        val coins = picked.map { op ->
            require(op !in locked) { "A ticked coin is in a coinjoin round right now." }
            allCoins().firstOrNull { it.outpoint == op } ?: error("A ticked coin is gone: scan again.")
        }
        val amount = b.optLong("amount", 0L).takeIf { it > 0 }
        val d = Spend.plan(coins, b.getString("address"), amount, b.getDouble("feeRate"), w.nextChangeScript())
        val mixed = engine.mixedScripts()
        val nMixed = coins.count { scriptOf(it) in mixed }
        val warn = when {
            nMixed > 0 && coins.size > nMixed -> "You are spending mixed and unmixed coins together: anyone can link them again, which undoes the coinjoin."
            nMixed > 1 -> "You are spending several mixed coins together: they become linked to each other."
            nMixed > 0 && d.change > 0 -> "Spending part of a mixed coin leaves change that is tied to this payment."
            else -> null
        }
        return d to warn
    }

    private fun draftJson(d: Spend.Draft, warn: String?) = JSONObject()
        .put("address", d.toAddress).put("amount", d.amount).put("change", d.change).put("fee", d.fee)
        .put("vbytes", d.vbytes).put("feeRate", d.feeRate).put("inputs", d.coins.size)
        .put("total", d.coins.sumOf { it.value }).put("warning", warn ?: JSONObject.NULL)

    private fun coin(w: Wallet, outpoint: String): Wallet.Coin {
        require(outpoint !in engine.lockedOutpoints()) { "that coin is already in a round" }
        return w.coins.firstOrNull { it.outpoint == outpoint }
            ?: error(if (allCoins().any { it.outpoint == outpoint }) "that coin is not of the pool's address type" else "coin not found: scan again")
    }

    /** The coins a create/join asks for: `coins` (a list) or the older single `coin`, all from [w]. */
    private fun coins(w: Wallet, b: JSONObject): List<Wallet.Coin> {
        val ops = b.optJSONArray("coins")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: listOf(b.getString("coin"))
        require(ops.isNotEmpty() && ops.toSet().size == ops.size) { "pick each coin once" }
        return ops.map { coin(w, it) }
    }

    private fun newSession(): String {
        val t = ByteArray(24).also { rnd.nextBytes(it) }.toHex(); token = t; return t
    }

    private fun withCookie(ex: HttpExchange, t: String) =
        ex.responseHeaders.add("Set-Cookie", "kj=$t; Path=/; HttpOnly; SameSite=Strict")

    private fun setup(ex: HttpExchange) {
        if (vault.exists()) return reply(ex, 409, err("this node already has a wallet"))
        val b = body(ex)
        val password = b.getString("password")
        val passphrase = b.optString("passphrase", "")
        val words = if (b.optString("mode") == "import")
            b.getString("words").trim().lowercase().split(Regex("\\s+"))
        else Bip39.fromEntropy(ByteArray(if (b.optInt("count", 12) == 24) 32 else 16).also { rnd.nextBytes(it) })
        require(Bip39.isValid(words)) { "These words are not a valid BIP-39 seed (a word is wrong or out of order)." }
        vault.store(words, passphrase, password)
        val sec = Vault.Secret(words, passphrase)
        secret = sec; wallet = null; walletTr = null
        openWallets(sec)
        Thread { runCatching { wallet?.scan() } }.start()
        withCookie(ex, newSession())
        // The new words are shown ONCE, to be written down; imported ones are not echoed back.
        reply(ex, 200, JSONObject().put("ok", true).apply { if (b.optString("mode") != "import") put("words", words.joinToString(" ")) })
    }

    private fun unlock(ex: HttpExchange) {
        val b = body(ex)
        val sec = try { vault.open(b.getString("password")) } catch (e: Vault.WrongPassword) {
            Thread.sleep(1500) // slows guessing down
            return reply(ex, 403, err("Wrong password."))
        }
        secret = sec
        openWallets(sec)
        withCookie(ex, newSession())
        reply(ex, 200, ok())
    }

    // ------------------------------------------------------------------ JSON

    /** Telegram settings for the page: whether it is set up, never the token itself. */
    private fun settingsJson() = JSONObject().put("notify_new_pools", engine.notifyNewPools).put("auto_sign", engine.autoSign)
        .put("telegram_set", engine.settings().optString("telegram_token").isNotEmpty())
        .put("telegram_chat", engine.settings().optString("telegram_chat"))

    private fun eventJson(e: Engine.Event) = JSONObject().put("at", e.at).put("title", e.title).put("text", e.text).put("pool", e.pool ?: "")

    private fun termsJson(t: Protocol.Terms) = JSONObject()
        .put("id", t.id).put("amount", t.amount).put("feeRate", t.feeRate).put("peers", t.peers)
        .put("minPeers", t.minPeers).put("maxPeers", t.maxPeers).put("expiresAt", t.expiresAt).put("private", t.private)
        .put("feeWithChange", CoinjoinTx.feeShare(t.feeRate, true, 1, t.script)).put("feeNoChange", CoinjoinTx.feeShare(t.feeRate, false, 1, t.script))
        .put("script", Protocol.scriptName(t.script)).put("maxInputs", t.maxInputs).put("version", t.version)
        .put("inputFee", kotlin.math.ceil(t.feeRate * CoinjoinTx.inputVb(t.script)).toLong())

    private fun status(): JSONObject {
        val ws = wallets()
        val locked = engine.lockedOutpoints()
        val o = JSONObject().put("setup", true).put("unlocked", secret != null)
            .put("height", runCatching { rpc.height() }.getOrDefault(0))
            .put("relay", Protocol.RELAY).put("notify_new_pools", engine.notifyNewPools)
            .put("auto_sign", engine.autoSign).put("telegram", settingsJson())
            .put("waitingExact", JSONArray(engine.waitingExact().map { JSONObject().put("value", it.value).put("create", it.create != null) }))
        if (ws.isNotEmpty()) {
            val mixed = engine.mixedScripts()
            o.put("balance", ws.sumOf { w -> w.coins.sumOf { it.value } }).put("scannedAt", ws.minOf { it.scannedAt })
            o.put("taproot", walletTr != null)
            o.put("coins", JSONArray(allCoins().map { c ->
                val w = walletFor(c.type)
                JSONObject().put("outpoint", c.outpoint).put("value", c.value).put("address", c.address)
                    .put("type", Protocol.scriptName(c.type))
                    .put("label", when { scriptOf(c) in mixed -> "mixed"; c.branch == 1 -> "change"; else -> "" })
                    .put("confirmations", w.confirmations(c)).put("inRound", c.outpoint in locked)
            }))
        }
        o.put("mine", JSONArray(engine.states().map { st -> stateJson(st) }))
        return o
    }

    private fun stateJson(st: PoolSession.State): JSONObject {
        val plan = engine.session(st.poolId)?.plan()
        val people = if (st.round.isNotEmpty()) st.round.size else st.seats.size
        return JSONObject().put("id", st.poolId).put("amount", st.terms.amount).put("feeRate", st.terms.feeRate)
            .put("creator", st.creator).put("private", st.terms.private).put("phase", st.phase.name).put("reason", st.reason)
            .put("people", people).put("minPeers", st.terms.minPeers).put("maxPeers", st.terms.maxPeers)
            .put("expiresAt", st.terms.expiresAt).put("phaseDeadline", st.phaseDeadline).put("voteDeadline", st.voteDeadline)
            .put("coinValue", st.seat.inputValue).put("coins", st.seat.coins.size).put("change", st.seat.changeValue)
            .put("script", Protocol.scriptName(st.terms.script))
            .put("iAsked", st.voteBy != null && st.voteBy == st.token?.let { Protocol.tokenHash(it) })
            .put("voted", st.votedOn != null && st.votedOn == st.voteId)
            .put("signed", st.postedSig).put("sigs", st.sigs.size).put("txid", st.txid ?: "")
            .put("planFee", plan?.fee ?: 0).put("planPeople", st.round.size).put("planCoins", plan?.coins?.size ?: 0)
    }
}
