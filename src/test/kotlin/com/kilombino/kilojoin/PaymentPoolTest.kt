package com.kilombino.kilojoin

import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.NostrEvent
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import org.json.JSONObject
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kilowallet 1.3.0's coinjoin as Kilojoin uses it: v5 payment pools, the minimum per person, pools from whole coins, sends to several. */
class PaymentPoolTest {
    private fun pub(n: Long) = Secp256k1.compress(Secp256k1.multiply(BigInteger.valueOf(n), Secp256k1.G))
    private fun addr(n: Long, t: ScriptType = ScriptType.P2WPKH) = Address.encode(pub(n), t)

    @Test fun v5PoolsListAndParse() {
        val np = PoolSession.newPool(100_000, 2.0, 5, 1, v3 = true, outputs = 2, payments = listOf(30_000L, 170_000L))
        val ev = NostrEvent.sign(BigInteger(np.secret, 16), Protocol.KIND_POOL, listOf(listOf("d", np.terms.id), listOf("t", Protocol.TAG)), np.terms.toJson().toString())
        val t = Protocol.Terms.parse(ev)!!
        assertTrue(t.v5); assertEquals(listOf(30_000L, 170_000L), t.payments); assertEquals(200_000L, t.mixTotal)
        // A pool's minimum is per person in all: 2 × 5 000 is a pool, 3 × 3 000 a test.
        assertFalse(Protocol.isTest(PoolSession.newPool(5_000, 1.0, 5, 1, v3 = true, outputs = 2).terms))
        assertTrue(Protocol.isTest(PoolSession.newPool(3_000, 1.0, 5, 1, v3 = true, outputs = 3).terms))
    }

    @Test fun payCheck() {
        assertTrue(CoinjoinPay.check(listOf(addr(1) to 5_000L, addr(2) to 5_000L), false).ok)
        CoinjoinPay.check(listOf(addr(1) to 37_123L, addr(2) to 62_877L), false).let { assertFalse(it.ok); assertTrue(it.paymentOk); assertEquals(100_000L, it.amounts.sum()) }
        assertEquals(CoinjoinPay.NEEDS, CoinjoinPay.check(listOf(addr(1) to 50_000L, addr(2, ScriptType.P2TR) to 50_000L), true).reason)
        assertFalse(CoinjoinPay.check(listOf(addr(3, ScriptType.P2TR) to 50_000L), false).any)
        assertFalse(CoinjoinPay.check(listOf(addr(1) to 4_000L, addr(2) to 5_000L), true).any)
    }

    @Test fun fromWholeCoins() {
        val amount = CoinjoinTx.noChangeAmount(123_457, 2, 1.5, ScriptType.P2WPKH, 3)!!
        assertEquals(0L, CoinjoinTx.change(123_457, amount, 1.5, 2, ScriptType.P2WPKH, 3))
        assertNull(CoinjoinTx.change(123_457, amount + 1, 1.5, 2, ScriptType.P2WPKH, 3))
    }

    @Test fun sendToSeveral() {
        val coin = Wallet.Coin("ab".repeat(32), 0, 100_000, 1, 0, 0, addr(9))
        val change = Address.scriptPubKey(pub(10), ScriptType.P2WPKH)
        val d = Spend.plan(listOf(coin), addr(1), 20_000, 2.0, change, listOf(addr(2) to 30_000L))
        assertEquals(50_000L, d.total); assertEquals(1, d.extra.size)
        assertEquals(100_000L, d.total + d.change + d.fee)
        assertFailsWith<IllegalArgumentException> { Spend.plan(listOf(coin), addr(1), null, 2.0, change, listOf(addr(2) to 30_000L)) }
        assertFailsWith<IllegalArgumentException> { Spend.plan(listOf(coin), addr(1), 20_000, 2.0, change, listOf(addr(1) to 30_000L)) }
        assertEquals(addr(1), Spend.addressOf(Address.decodeToScriptPubKey(addr(1))))
        // A payment round's own data survives a restart.
        val np = PoolSession.newPool(50_000, 1.0, 5, 1, v3 = true, payments = listOf(20_000L, 30_000L))
        assertEquals(listOf(20_000L, 30_000L), PoolSession.State.parse(JSONObject(PoolSession.newState(np.terms, true, np.secret,
            listOf(CoinjoinTx.Coin("cd".repeat(32), 0, 80_000, pub(5))), listOf(BigInteger.valueOf(5)), listOf("a"),
            listOf(Address.scriptPubKey(pub(6), ScriptType.P2WPKH), Address.scriptPubKey(pub(7), ScriptType.P2WPKH)),
            Address.scriptPubKey(pub(8), ScriptType.P2WPKH), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv).toJson().toString())).terms.payments)
    }
}
