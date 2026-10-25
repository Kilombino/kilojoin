package com.kilombino.kilojoin

import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.Bip32Priv
import com.kilombino.pyblockwatch.crypto.TxBuilder
import kotlin.math.ceil

/**
 * Spending from the wallet, with the coins chosen by hand (coin control): a coinjoin is only
 * worth something if its mixed output is never spent together with the coins it came from.
 * Signed with the unified sighash (0x21), so the spend cannot be replayed onto the SHA-256
 * chain, exactly as Kilowallet signs on BLAKE2b (the same TxBuilder, from its source).
 */
object Spend {
    /** Below this a P2WPKH change output would be dust: it goes to the fee instead. */
    const val DUST = 294L

    /** The bc1q / bc1p address of an output script (a payment's recipient), as typed in SEND. */
    fun addressOf(spk: ByteArray): String = when {
        spk.size == 22 && spk[0] == 0.toByte() -> com.kilombino.pyblockwatch.crypto.Bech32.encodeSegwit("bc", 0, spk.copyOfRange(2, 22))
        spk.size == 34 && spk[0] == 0x51.toByte() -> com.kilombino.pyblockwatch.crypto.Bech32.encodeSegwit("bc", 1, spk.copyOfRange(2, 34))
        else -> com.kilombino.pyblockwatch.crypto.Hashes.run { spk.toHex() }
    }

    class Draft(
        val coins: List<Wallet.Coin>, val toScript: ByteArray, val toAddress: String,
        val amount: Long, val change: Long, val fee: Long, val vbytes: Int, val feeRate: Double,
        /** More recipients after the first, paid by the same transaction (address, output). */
        val extra: List<Pair<String, TxBuilder.Output>> = emptyList(),
    ) {
        /** What the recipients get, all of them. */
        val total: Long get() = amount + extra.sumOf { it.second.value }
    }

    // Virtual sizes: P2WPKH input 68, Taproot key-path input 57.75 (with the unified hash byte),
    // any output 9 + script, overhead 10.5.
    private fun vsize(inputs: Int, outScripts: List<Int>, taprootIn: Int = 0): Double =
        10.5 + 68.0 * (inputs - taprootIn) + 57.75 * taprootIn + outScripts.sumOf { 9.0 + it }

    private fun scriptOf(address: String): ByteArray =
        runCatching { Address.decodeToScriptPubKey(address.trim()) }.getOrElse { error("That is not a valid address: ${address.trim()}") }

    /**
     * The transaction to send [amount] (all of the coins, less the fee, when null) to [address],
     * and each of [more] (address, sats) to its own, paying [feeRate] sat/vB, with any change to
     * [changeScript].
     */
    fun plan(coins: List<Wallet.Coin>, address: String, amount: Long?, feeRate: Double, changeScript: ByteArray,
             more: List<Pair<String, Long>> = emptyList()): Draft {
        require(coins.isNotEmpty()) { "Tick at least one coin." }
        val tr = coins.count { it.type == com.kilombino.pyblockwatch.crypto.ScriptType.P2TR }
        require(feeRate in 0.1..1000.0) { "The fee rate must be between 0.1 and 1000 sat/vB." }
        val to = scriptOf(address)
        require(amount != null || more.isEmpty()) { "Everything ticked goes to one recipient: type the amounts, or remove the others." }
        val extra = more.map { (a, v) -> require(v > DUST) { "The amount for ${a.trim()} is too small to send." }; a.trim() to TxBuilder.Output(scriptOf(a), v) }
        val outs = listOf(to.size) + extra.map { it.second.scriptPubKey.size }
        val all = listOf(to) + extra.map { it.second.scriptPubKey }
        require(all.map { it.toList() }.toSet().size == all.size) { "The same address twice: put it once with the whole amount." }
        val sum = coins.sumOf { it.value }
        if (amount == null) {
            val fee = ceil(feeRate * vsize(coins.size, outs, tr)).toLong()
            val send = sum - fee
            require(send > DUST) { "These coins do not cover the fee." }
            return Draft(coins, to, address.trim(), send, 0, fee, ceil(vsize(coins.size, outs, tr)).toInt(), feeRate)
        }
        require(amount > DUST) { "The amount is too small to send." }
        val total = amount + extra.sumOf { it.second.value }
        val feeWith = ceil(feeRate * vsize(coins.size, outs + changeScript.size, tr)).toLong()
        val change = sum - total - feeWith
        if (change >= DUST) return Draft(coins, to, address.trim(), amount, change, feeWith,
            ceil(vsize(coins.size, outs + changeScript.size, tr)).toInt(), feeRate, extra)
        val feeNo = ceil(feeRate * vsize(coins.size, outs, tr)).toLong()
        require(sum - total >= feeNo) { "These coins do not cover ${total} sats plus the fee." }
        // Too little left for a change output: it goes to the miners.
        return Draft(coins, to, address.trim(), amount, 0, sum - total, ceil(vsize(coins.size, outs, tr)).toInt(), feeRate, extra)
    }

    fun sign(secret: Vault.Secret, d: Draft, changeScript: ByteArray): TxBuilder.Signed {
        val master = Wallet.master(secret)
        val inputs = d.coins.map { c ->
            val k = Bip32Priv.derivePath(master, c.path)
            TxBuilder.Input(c.txid, c.vout, c.value, k.key, k.publicKey(), 0xfffffffdL, c.type)
        }
        val outputs = buildList {
            add(TxBuilder.Output(d.toScript, d.amount))
            d.extra.forEach { add(it.second) }
            if (d.change > 0) add(TxBuilder.Output(changeScript, d.change))
        }
        return TxBuilder.build(inputs, outputs, unified = true)
    }
}
