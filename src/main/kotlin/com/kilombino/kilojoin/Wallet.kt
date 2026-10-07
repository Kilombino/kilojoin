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
 * A native SegWit (BIP-84, m/84'/0'/0') wallet seen through the node: its coins come from
 * `scantxoutset` over the account's two branches, so the node needs no wallet of its own.
 * Fresh addresses are handed out from a persisted counter, never reused.
 */
class Wallet(private val dir: File, private val rpc: Rpc, val xpub: String) {
    private val stateFile = File(dir, "wallet.json")
    private val account = Bip32.parseExtendedPubKey(xpub)
    /** The key as the node's descriptors want it: xpub on mainnet, tpub on test networks. */
    private val descKey = if (testnet) toVersion(xpub, 0x043587CF) else xpub

    data class Coin(val txid: String, val vout: Int, val value: Long, val height: Int, val branch: Int, val index: Int, val address: String) {
        val outpoint get() = "$txid:$vout"
        val path get() = "m/84'/0'/0'/$branch/$index"
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
    fun address(branch: Int, index: Int): String = Address.encode(pubkey(branch, index), ScriptType.P2WPKH)
    fun script(branch: Int, index: Int): ByteArray = Address.scriptPubKey(pubkey(branch, index), ScriptType.P2WPKH)

    /** A fresh address on [branch] (0 receive, 1 change), reserved so nothing else gets it. */
    @Synchronized
    fun fresh(branch: Int): Pair<Int, ByteArray> {
        val i = top(branch); bump(branch, i + 1)
        return i to script(branch, i)
    }

    /** Next receive address, shown to the user (reserved once handed out). */
    @Synchronized
    fun receive(): Pair<Int, String> { val (i, _) = fresh(0); return i to address(0, i) }

    /** Scan the UTXO set for this wallet's coins (both branches, used top + gap). Blocking: can take a minute. */
    @Synchronized
    fun scan(gap: Int = 50) {
        val desc = JSONArray()
        val range = mapOf(0 to top(0) + gap, 1 to top(1) + gap)
        for ((b, n) in range) desc.put(JSONObject().put("desc", "wpkh($descKey/$b/*)").put("range", n))
        val r = rpc.call("scantxoutset", "start", desc, timeoutMs = 600_000) as JSONObject
        val byScript = HashMap<String, Pair<Int, Int>>()
        for ((b, n) in range) for (i in 0..n) byScript[script(b, i).toHex()] = b to i
        val found = ArrayList<Coin>()
        val u = r.getJSONArray("unspents")
        for (k in 0 until u.length()) {
            val o = u.getJSONObject(k)
            val (b, i) = byScript[o.getString("scriptPubKey")] ?: continue
            found += Coin(o.getString("txid"), o.getInt("vout"), Math.round(o.getDouble("amount") * 1e8),
                o.optInt("height"), b, i, address(b, i))
            bump(b, i + 1)
        }
        tip = r.optInt("height", rpc.height())
        coins = found.sortedByDescending { it.value }
        scannedAt = System.currentTimeMillis()
    }

    fun confirmations(c: Coin): Int = if (c.height <= 0 || tip <= 0) 0 else tip - c.height + 1

    companion object {
        fun master(secret: Vault.Secret) = Bip32Priv.fromSeed(Bip39.toSeed(secret.words, secret.passphrase))

        /** The BIP-84 account key as a plain "xpub", the prefix the node's descriptors accept. */
        fun xpubFor(secret: Vault.Secret): String = toXpub(Bip32Priv.accountXpub(master(secret), purpose = 84, account = 0))

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
