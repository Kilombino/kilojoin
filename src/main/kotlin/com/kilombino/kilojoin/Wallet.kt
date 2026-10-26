package com.kilombino.kilojoin

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.Bip39
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.ScriptType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One account of the seed seen through the node: native SegWit (BIP-84, m/84'/0'/0') or, with
 * [taproot], Taproot (BIP-86, m/86'/0'/0'). Its coins come from `scantxoutset` over the
 * account's two branches (see [scanAll]), so the node needs no wallet of its own. Fresh addresses are handed
 * out from a persisted counter, never reused.
 */
class Wallet(private val dir: File, private val rpc: Rpc, val xpub: String, val taproot: Boolean = false) {
    private val stateFile = File(dir, if (taproot) "wallet-tr.json" else "wallet.json")
    val type: ScriptType = if (taproot) ScriptType.P2TR else ScriptType.P2WPKH
    private val account = Bip32.parseExtendedPubKey(xpub)
    /** The key as the node's descriptors want it: xpub on mainnet, tpub on test networks. */
    private val descKey = if (testnet) toVersion(xpub, 0x043587CF) else xpub

    data class Coin(val txid: String, val vout: Int, val value: Long, val height: Int, val branch: Int, val index: Int, val address: String,
                    val type: ScriptType = ScriptType.P2WPKH) {
        val outpoint get() = "$txid:$vout"
        val path get() = "m/${if (type == ScriptType.P2TR) 86 else 84}'/0'/0'/$branch/$index"
    }

    @Volatile var coins: List<Coin> = emptyList(); private set
    @Volatile var scannedAt: Long = 0; private set
    @Volatile var tip: Int = 0; private set

    private fun state(): JSONObject = if (stateFile.exists()) JSONObject(stateFile.readText()) else JSONObject()
    private fun saveState(o: JSONObject) { stateFile.writeText(o.toString()) }

    /** One past the highest index ever used or handed out on [branch]. */
    private fun top(branch: Int): Int = state().optInt("top$branch", 0)
    private fun bump(branch: Int, to: Int) {
        val o = state(); if (to > o.optInt("top$branch", 0)) { o.put("top$branch", to); saveState(o) }
    }

    fun pubkey(branch: Int, index: Int): ByteArray = Bip32.derivePath(account, branch, index).pubkey()
    fun address(branch: Int, index: Int): String = Address.encode(pubkey(branch, index), type)
    fun script(branch: Int, index: Int): ByteArray = Address.scriptPubKey(pubkey(branch, index), type)

    /** A fresh address on [branch] (0 receive, 1 change), reserved so nothing else gets it. */
    @Synchronized
    /** The change script a send would use next, without reserving it (for a preview). */
    fun nextChangeScript(): ByteArray = script(1, top(1))

    @Synchronized
    fun fresh(branch: Int): Pair<Int, ByteArray> {
        val i = top(branch); bump(branch, i + 1)
        return i to script(branch, i)
    }

    /** Next receive address, shown to the user (reserved once handed out). */
    @Synchronized
    fun receive(): Pair<Int, String> { val (i, _) = fresh(0); return i to address(0, i) }

    /** Both branches to look at: what is used or handed out, plus [gap]. */
    private fun ranges(gap: Int) = mapOf(0 to top(0) + gap, 1 to top(1) + gap)
    private fun descriptors(range: Map<Int, Int>) = range.map { (b, n) ->
        JSONObject().put("desc", "${if (taproot) "tr" else "wpkh"}($descKey/$b/*)").put("range", n)
    }

    /** This account's coins out of a scan's result. */
    @Synchronized
    private fun take(r: JSONObject, range: Map<Int, Int>) {
        val byScript = HashMap<String, Pair<Int, Int>>()
        for ((b, n) in range) for (i in 0..n) byScript[script(b, i).toHex()] = b to i
        val found = ArrayList<Coin>()
        val u = r.getJSONArray("unspents")
        for (k in 0 until u.length()) {
            val o = u.getJSONObject(k)
            val (b, i) = byScript[o.getString("scriptPubKey")] ?: continue
            found += Coin(o.getString("txid"), o.getInt("vout"), Math.round(o.getDouble("amount") * 1e8),
                o.optInt("height"), b, i, address(b, i), type)
            bump(b, i + 1)
        }
        tip = r.optInt("height", rpc.height())
        coins = found.sortedByDescending { it.value }
        scannedAt = System.currentTimeMillis()
    }

    fun confirmations(c: Coin): Int = if (c.height <= 0 || tip <= 0) 0 else tip - c.height + 1

    companion object {
        /**
         * The node runs one `scantxoutset` at a time, and each one walks the whole UTXO set: a
         * minute on a fast disk, much longer on a small machine. So there is one scan at a time
         * here too, for every account at once, with no short timeout; its progress and any error
         * are kept for the screen and written to the log.
         */
        private val scanLock = Any()
        @Volatile private var lastScanStart = 0L
        @Volatile var scanning = false; private set
        /** 0–100 while the node is walking the UTXO set, null when it hasn't said. */
        @Volatile var scanProgress: Double? = null; private set
        @Volatile var scanError: String? = null; private set
        private const val SCAN_TIMEOUT_MS = 4 * 3600 * 1000

        fun scanAll(rpc: Rpc, wallets: List<Wallet>, gap: Int = 50) {
            if (wallets.isEmpty()) return
            val asked = System.currentTimeMillis()
            synchronized(scanLock) {
                // A scan that began after this was asked has just finished: nothing new to see.
                if (lastScanStart >= asked) return
                var start = System.currentTimeMillis()
                scanning = true; scanProgress = null
                val poll = Thread {
                    while (true) {
                        runCatching { (rpc.call("scantxoutset", "status", timeoutMs = 15_000) as? JSONObject)?.let { scanProgress = it.optDouble("progress") } }
                        try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                    }
                }.apply { isDaemon = true; start() }
                try {
                    waitForNodeScan(rpc)
                    val parts = wallets.map { it to it.ranges(gap) }
                    val desc = JSONArray().also { a -> parts.forEach { (w, r) -> w.descriptors(r).forEach { a.put(it) } } }
                    println("scan: the node is walking its UTXO set for ${wallets.size} account(s)")
                    start = System.currentTimeMillis()
                    val r = try { rpc.call("scantxoutset", "start", desc, timeoutMs = SCAN_TIMEOUT_MS) }
                        catch (e: Rpc.RpcError) {
                            // Another scan got to the node first (one left running by an earlier start): wait for it, then ours.
                            if (e.message?.contains("in progress") != true) throw e
                            waitForNodeScan(rpc); start = System.currentTimeMillis(); rpc.call("scantxoutset", "start", desc, timeoutMs = SCAN_TIMEOUT_MS)
                        } as JSONObject
                    parts.forEach { (w, rg) -> w.take(r, rg) }
                    scanError = null
                    println("scan: done in ${(System.currentTimeMillis() - start) / 1000} s, ${wallets.sumOf { it.coins.size }} coin(s)")
                } catch (e: Exception) {
                    scanError = e.message ?: e.toString()
                    println("scan failed: $scanError")
                    throw e
                } finally {
                    poll.interrupt(); scanning = false; scanProgress = null; lastScanStart = start
                }
            }
        }

        /** Wait while the node is busy with a scan it already had running. */
        private fun waitForNodeScan(rpc: Rpc) {
            var said = false
            val until = System.currentTimeMillis() + SCAN_TIMEOUT_MS
            while (System.currentTimeMillis() < until) {
                val st = runCatching { rpc.call("scantxoutset", "status", timeoutMs = 15_000) }.getOrNull() as? JSONObject ?: return
                if (!said) { println("scan: waiting for a scan the node already has running (${st.optDouble("progress")} %)"); said = true }
                Thread.sleep(3000)
            }
        }

        fun master(secret: Vault.Secret) = Bip32Priv.fromSeed(Bip39.toSeed(secret.words, secret.passphrase))

        /** The BIP-84 account key as a plain "xpub", the prefix the node's descriptors accept. */
        fun xpubFor(secret: Vault.Secret): String = toXpub(Bip32Priv.accountXpub(master(secret), purpose = 84, account = 0))

        /** The BIP-86 (Taproot) account key, also as a plain "xpub". */
        fun taprootXpubFor(secret: Vault.Secret): String = toXpub(Bip32Priv.accountXpub(master(secret), purpose = 86, account = 0))

        /** Set for a regtest/testnet node (KILOJOIN_NETWORK not "blake2b"). */
        @Volatile var testnet = false

        /** zpub/ypub → xpub: same key, standard version bytes. */
        fun toXpub(key: String): String = toVersion(key, 0x0488B21E)

        fun toVersion(key: String, v: Int): String {
            val raw = com.kilombino.pyblockwatch.crypto.Base58.decodeChecked(key)
            raw[0] = (v ushr 24).toByte(); raw[1] = (v ushr 16).toByte(); raw[2] = (v ushr 8).toByte(); raw[3] = v.toByte()
            return com.kilombino.pyblockwatch.crypto.Base58.encodeChecked(raw)
        }
    }
}
