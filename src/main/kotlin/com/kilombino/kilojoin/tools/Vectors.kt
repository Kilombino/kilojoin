package com.kilombino.kilojoin.tools

import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.Nip44
import com.kilombino.pyblockwatch.coinjoin.NostrEvent
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.crypto.Ecdsa
import com.kilombino.pyblockwatch.crypto.Hashes
import com.kilombino.pyblockwatch.crypto.Hashes.toHex
import com.kilombino.pyblockwatch.crypto.Schnorr
import com.kilombino.pyblockwatch.crypto.Secp256k1
import com.kilombino.pyblockwatch.crypto.TxBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * Prints docs/vectors.json: Kilojoin v1 test vectors made by the very code Kilowallet and
 * Kilojoin run, from fixed keys, so another implementation can check itself byte for byte.
 * Run: java -cp build/libs/kilojoin.jar com.kilombino.kilojoin.tools.VectorsKt
 */
fun main() {
    fun key(n: Int) = BigInteger(1, Hashes.sha256("kilojoin vector key $n".toByteArray()))
    fun pub33(k: BigInteger) = Secp256k1.compress(Secp256k1.multiply(k, Secp256k1.G))
    val out = JSONObject()

    // --- the two participants' coins and keys
    val ka = key(1); val kb = key(2)
    val coinA = CoinjoinTx.Coin("11".repeat(32), 0, 50_000, pub33(ka))
    val coinB = CoinjoinTx.Coin("22".repeat(32), 1, 60_000, pub33(kb))
    val poolKey = key(10); val poolPub = NostrEvent.pubOf(poolKey)
    val poolId = "0123456789abcdef"
    val amount = 10_000L; val feeRate = 2.0
    out.put("pool", JSONObject().put("id", poolId).put("pool_secret", poolKey.toString(16).padStart(64, '0')).put("pool_pub", poolPub)
        .put("denomination", amount).put("fee_rate", feeRate))

    // --- fees
    out.put("fee_share", JSONObject().put("with_change", CoinjoinTx.feeShare(feeRate, true))
        .put("without_change", CoinjoinTx.feeShare(feeRate, false))
        .put("minimum_coin", CoinjoinTx.minimumCoin(amount, feeRate))
        .put("change_for_50000", CoinjoinTx.change(50_000, amount, feeRate))
        .put("change_for_10209", CoinjoinTx.change(10_209, amount, feeRate)))

    // --- join: ownership proof and change signature (RFC 6979, deterministic)
    val joinKeyB = key(20); val joinPubB = NostrEvent.pubOf(joinKeyB)
    val mixA = byteArrayOf(0x00, 0x14) + Hashes.hash160(pub33(key(3)))
    val mixB = byteArrayOf(0x00, 0x14) + Hashes.hash160(pub33(key(4)))
    val chA = byteArrayOf(0x00, 0x14) + Hashes.hash160(pub33(key(5)))
    val chB = byteArrayOf(0x00, 0x14) + Hashes.hash160(pub33(key(6)))
    val changeA = CoinjoinTx.change(coinA.value, amount, feeRate)!!
    val changeB = CoinjoinTx.change(coinB.value, amount, feeRate)!!
    val ownMsg = CoinjoinTx.ownershipMessage(poolId, joinPubB, coinB)
    val chMsg = Protocol.changeMessage(poolId, coinB, chB, changeB)
    out.put("join", JSONObject()
        .put("join_secret", joinKeyB.toString(16).padStart(64, '0')).put("join_pub", joinPubB)
        .put("coin", Protocol.coinJson(coinB)).put("coin_secret", kb.toString(16).padStart(64, '0'))
        .put("ownership_preimage", "kilojoin/v1/own|$poolId|$joinPubB|${coinB.outpoint}|${coinB.value}")
        .put("ownership_hash", ownMsg.toHex())
        .put("ownership_sig_der", Ecdsa.der(Ecdsa.sign(kb, ownMsg)).toHex())
        .put("change_script", chB.toHex()).put("change_value", changeB)
        .put("change_preimage", "kilojoin/v1/change|$poolId|${coinB.outpoint}|${chB.toHex()}|$changeB")
        .put("change_hash", chMsg.toHex())
        .put("change_sig_der", Ecdsa.der(Ecdsa.sign(kb, chMsg)).toHex())
        .put("password", "hunter2")
        .put("password_key", Protocol.passwordKey(poolId, "hunter2").toHex())
        .put("password_proof", Protocol.passwordProof(Protocol.passwordKey(poolId, "hunter2"), joinPubB))
        .put("token", "00112233445566778899aabbccddeeff")
        .put("token_hash", Protocol.tokenHash("00112233445566778899aabbccddeeff")))

    // --- the canonical transaction, sighashes and signatures
    val plan = CoinjoinTx.plan(listOf(coinB, coinA), listOf(mixB, mixA),
        listOf(TxBuilder.Output(chB, changeB), TxBuilder.Output(chA, changeA)), amount)
    val sigA = CoinjoinTx.sign(plan, coinA, ka); val sigB = CoinjoinTx.sign(plan, coinB, kb)
    val signed = CoinjoinTx.assemble(plan, mapOf(coinA.outpoint to sigA, coinB.outpoint to sigB))
    out.put("transaction", JSONObject()
        .put("inputs_in_order", JSONArray(plan.coins.map { it.outpoint }))
        .put("outputs_in_order", JSONArray(plan.outputs.map { JSONObject().put("script", it.scriptPubKey.toHex()).put("value", it.value) }))
        .put("fee", plan.fee)
        .put("sighash_type", "0x21 (SIGHASH_UNIFIED | SIGHASH_ALL)")
        .put("unified_sighash_input0", TxBuilder.unifiedSighash(CoinjoinTx.VERSION, plan.inputs, plan.outputs, 0, 0).toHex())
        .put("unified_sighash_input1", TxBuilder.unifiedSighash(CoinjoinTx.VERSION, plan.inputs, plan.outputs, 1, 0).toHex())
        .put("sig_A", sigA.toHex()).put("sig_B", sigB.toHex())
        .put("txid_before_signing", CoinjoinTx.txid(plan))
        .put("raw_tx", signed.rawHex).put("txid", signed.txid))

    // --- a channel message: NIP-44 (fixed nonce) inside a kind 2023 event (BIP-340 aux = 0)
    val msg = JSONObject().put("type", "output").put("script", mixA.toHex()).toString()
    val conv = Nip44.conversationKey(poolKey, Hashes.hexToBytes(poolPub))
    val nonce = ByteArray(32) { 7 }
    val content = Nip44.encrypt(msg, conv, nonce)
    val tags = listOf(listOf("p", poolPub)); val created = 1791400000L
    val h = NostrEvent.hash(poolPub, created, Protocol.KIND_MSG, tags, content)
    out.put("channel_event", JSONObject()
        .put("plaintext", msg).put("conversation_key", conv.toHex()).put("nonce", nonce.toHex())
        .put("event", JSONObject().put("id", h.toHex()).put("pubkey", poolPub).put("created_at", created)
            .put("kind", Protocol.KIND_MSG).put("tags", JSONArray().put(JSONArray().put("p").put(poolPub)))
            .put("content", content).put("sig", Schnorr.sign(poolKey, h, ByteArray(32)).toHex())))
    println(out.toString(2))
}
