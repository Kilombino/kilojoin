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

    class Draft(
        val coins: List<Wallet.Coin>, val toScript: ByteArray, val toAddress: String,
        val amount: Long, val change: Long, val fee: Long, val vbytes: Int, val feeRate: Double,
    )

    // Virtual sizes: P2WPKH input 68, P2WPKH output 31, any output 9 + script, overhead 10.5.
    private fun vsize(inputs: Int, outScripts: List<Int>): Double = 10.5 + 68.0 * inputs + outScripts.sumOf { 9.0 + it }

    /**
     * The transaction to send [amount] (all of the coins, less the fee, when null) to [address],
     * paying [feeRate] sat/vB, with any change to [changeScript].
     */
    fun plan(coins: List<Wallet.Coin>, address: String, amount: Long?, feeRate: Double, changeScript: ByteArray): Draft {
        require(coins.isNotEmpty()) { "Tick at least one coin." }
        require(feeRate in 0.1..1000.0) { "The fee rate must be between 0.1 and 1000 sat/vB." }
        val to = runCatching { Address.decodeToScriptPubKey(address.trim()) }.getOrElse { error("That is not a valid address.") }
        val sum = coins.sumOf { it.value }
        if (amount == null) {
            val fee = ceil(feeRate * vsize(coins.size, listOf(to.size))).toLong()
            val send = sum - fee
            require(send > DUST) { "These coins do not cover the fee." }
            return Draft(coins, to, address.trim(), send, 0, fee, ceil(vsize(coins.size, listOf(to.size))).toInt(), feeRate)
        }
        require(amount > DUST) { "The amount is too small to send." }
        val feeWith = ceil(feeRate * vsize(coins.size, listOf(to.size, changeScript.size))).toLong()
        val change = sum - amount - feeWith
        if (change >= DUST) return Draft(coins, to, address.trim(), amount, change, feeWith,
            ceil(vsize(coins.size, listOf(to.size, changeScript.size))).toInt(), feeRate)
        val feeNo = ceil(feeRate * vsize(coins.size, listOf(to.size))).toLong()
        require(sum - amount >= feeNo) { "These coins do not cover ${amount} sats plus the fee." }
        // Too little left for a change output: it goes to the miners.
        return Draft(coins, to, address.trim(), amount, 0, sum - amount, ceil(vsize(coins.size, listOf(to.size))).toInt(), feeRate)
    }

    fun sign(secret: Vault.Secret, d: Draft, changeScript: ByteArray): TxBuilder.Signed {
        val master = Wallet.master(secret)
        val inputs = d.coins.map { c ->
            val k = Bip32Priv.derivePath(master, c.path)
            TxBuilder.Input(c.txid, c.vout, c.value, k.key, k.publicKey(), 0xfffffffdL)
        }
        val outputs = buildList {
            add(TxBuilder.Output(d.toScript, d.amount))
            if (d.change > 0) add(TxBuilder.Output(changeScript, d.change))
        }
        return TxBuilder.build(inputs, outputs, unified = true)
    }
}
