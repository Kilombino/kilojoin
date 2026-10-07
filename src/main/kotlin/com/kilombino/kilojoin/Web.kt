package com.kilombino.kilojoin

import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.crypto.Bip39
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
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
    @Volatile private var pools: List<Protocol.Terms> = emptyList()
    private val server = HttpServer.create(InetSocketAddress(port), 0)
    private val rnd = SecureRandom()

    init {
        engine = Engine(dir, rpc) { wallet }
        engine.settings().optString("xpub").takeIf { it.isNotEmpty() }?.let { wallet = Wallet(dir, rpc, it) }
        engine.load(); engine.startAll(); engine.startPoolWatcher()
        server.executor = Executors.newFixedThreadPool(8)
        server.createContext("/") { ex -> runCatching { route(ex) }.onFailure { e -> reply(ex, 500, err(e.message ?: "error")) } }
    }

    fun start() { server.start(); Thread { runCatching { wallet?.scan() } }.start() }
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
            "/api/scan" -> { w?.scan(); reply(ex, 200, status()) }
            "/api/receive" -> { val (i, a) = w!!.receive(); reply(ex, 200, JSONObject().put("index", i).put("address", a).put("script", w.script(0, i).toHex())) }
            "/api/pools" -> { pools = engine.fetchPools(); reply(ex, 200, JSONArray(pools.map { termsJson(it) })) }
            "/api/events" -> reply(ex, 200, JSONArray(engine.events.map { eventJson(it) }))
            "/api/settings" -> {
                if (b.has("notify_new_pools")) engine.setSetting("notify_new_pools", b.getBoolean("notify_new_pools"))
                reply(ex, 200, JSONObject().put("notify_new_pools", engine.notifyNewPools))
            }
            "/api/create" -> {
                val c = coin(w!!, b.getString("coin"))
                val st = engine.create(s!!, w, c, b.getLong("amount"), b.getDouble("feeRate"), b.optInt("minPeers", 2),
                    b.optInt("maxPeers", 5), b.optInt("hours", 6), b.optString("password"))
                reply(ex, 200, JSONObject().put("pool", st.poolId))
            }
            "/api/join" -> {
                val t = pools.firstOrNull { it.id == b.getString("pool") } ?: error("that pool is no longer open")
                val c = coin(w!!, b.getString("coin"))
                engine.join(s!!, w, c, t, b.optString("password"))
                reply(ex, 200, ok())
            }
            "/api/close" -> { engine.session(b.getString("pool"))!!.requestClose(); reply(ex, 200, ok()) }
            "/api/vote" -> { engine.session(b.getString("pool"))!!.vote(b.getBoolean("accept")); reply(ex, 200, ok()) }
            "/api/sign" -> { engine.sign(s!!, b.getString("pool")); reply(ex, 200, ok()) }
            "/api/leave" -> { engine.session(b.getString("pool"))!!.leave(); reply(ex, 200, ok()) }
            "/api/remove" -> { engine.remove(b.getString("pool")); reply(ex, 200, ok()) }
            else -> reply(ex, 404, err("not found"))
        }
    }

    private fun coin(w: Wallet, outpoint: String): Wallet.Coin {
        require(outpoint !in engine.lockedOutpoints()) { "that coin is already in a round" }
        return w.coins.firstOrNull { it.outpoint == outpoint } ?: error("coin not found: scan again")
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
        val xpub = Wallet.xpubFor(sec)
        engine.setSetting("xpub", xpub)
        secret = sec; wallet = Wallet(dir, rpc, xpub)
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
        if (wallet == null) wallet = Wallet(dir, rpc, Wallet.xpubFor(sec))
        withCookie(ex, newSession())
        reply(ex, 200, ok())
    }

    // ------------------------------------------------------------------ JSON

    private fun eventJson(e: Engine.Event) = JSONObject().put("at", e.at).put("title", e.title).put("text", e.text).put("pool", e.pool ?: "")

    private fun termsJson(t: Protocol.Terms) = JSONObject()
        .put("id", t.id).put("amount", t.amount).put("feeRate", t.feeRate).put("peers", t.peers)
        .put("minPeers", t.minPeers).put("maxPeers", t.maxPeers).put("expiresAt", t.expiresAt).put("private", t.private)
        .put("feeWithChange", CoinjoinTx.feeShare(t.feeRate, true)).put("feeNoChange", CoinjoinTx.feeShare(t.feeRate, false))

    private fun status(): JSONObject {
        val w = wallet
        val locked = engine.lockedOutpoints()
        val o = JSONObject().put("setup", true).put("unlocked", secret != null)
            .put("height", runCatching { rpc.height() }.getOrDefault(0))
            .put("relay", Protocol.RELAY).put("notify_new_pools", engine.notifyNewPools)
        if (w != null) {
            o.put("balance", w.coins.sumOf { it.value }).put("scannedAt", w.scannedAt)
            o.put("coins", JSONArray(w.coins.map { c ->
                JSONObject().put("outpoint", c.outpoint).put("value", c.value).put("address", c.address)
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
            .put("expiresAt", st.terms.expiresAt).put("phaseDeadline", st.phaseDeadline)
            .put("coinValue", st.seat.coin.value).put("change", st.seat.changeValue)
            .put("iAsked", st.voteBy != null && st.voteBy == st.token?.let { Protocol.tokenHash(it) })
            .put("voted", st.votedOn != null && st.votedOn == st.voteId)
            .put("signed", st.postedSig).put("sigs", st.sigs.size).put("txid", st.txid ?: "")
            .put("planFee", plan?.fee ?: 0).put("planPeople", plan?.coins?.size ?: 0)
    }
}
