package com.kilombino.kilojoin

import com.kilombino.pyblockwatch.coinjoin.CoinjoinTx
import com.kilombino.pyblockwatch.coinjoin.NostrEvent
import com.kilombino.pyblockwatch.coinjoin.PoolSession
import com.kilombino.pyblockwatch.coinjoin.Protocol
import com.kilombino.pyblockwatch.crypto.Address
import com.kilombino.pyblockwatch.crypto.ScriptType
import com.kilombino.pyblockwatch.crypto.Secp256k1
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Kilojoin v6 on this node: what older nodes and apps make of its pools, and a creator's round kept on disk. */
class AsyncPoolsTest {
    private fun pub(n: Long) = Secp256k1.compress(Secp256k1.multiply(BigInteger.valueOf(n), Secp256k1.G))
    private fun script(n: Long) = Address.scriptPubKey(pub(n), ScriptType.P2WPKH)

    private fun announcement(np: PoolSession.Companion.NewPool): JSONObject =
        NostrEvent.sign(BigInteger(np.secret, 16), Protocol.KIND_POOL, listOf(listOf("d", np.terms.id), listOf("t", Protocol.TAG),
            listOf("network", Protocol.NETWORK)), np.terms.toJson().toString()).toJson()

    /**
     * The parser of a released Kilojoin (0.9.3, the same Protocol.kt as Kilowallet 1.3.9–1.4.4), loaded from its own jar:
     * it lists the v5 pool and not the v6 one. KILOJOIN_LEGACY_JAR=<its kilojoin.jar>; skipped without it.
     */
    @Test fun aReleasedWalletDoesNotListV6Pools() {
        val jar = System.getenv("KILOJOIN_LEGACY_JAR")?.let { File(it) }?.takeIf { it.exists() } ?: return println("KILOJOIN_LEGACY_JAR not set: skipped")
        val v6 = announcement(PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, async = true))
        val v5 = announcement(PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, outputs = 2, payments = listOf(7_000L, 13_000L)))
        val v3 = announcement(PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true))
        URLClassLoader(arrayOf(jar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { cl ->
            val json = cl.loadClass("org.json.JSONObject")
            val evClass = cl.loadClass("com.kilombino.pyblockwatch.coinjoin.NostrEvent")
            val evParse = evClass.getField("Companion").get(null).let { c -> c to c.javaClass.getMethod("parse", json) }
            val terms = cl.loadClass("com.kilombino.pyblockwatch.coinjoin.Protocol\$Terms")
            val parse = terms.getField("Companion").get(null).let { c -> c to c.javaClass.getMethod("parse", evClass) }
            fun legacy(o: JSONObject): Any? {
                val ev = evParse.second.invoke(evParse.first, json.getConstructor(String::class.java).newInstance(o.toString()))
                return parse.second.invoke(parse.first, ev)
            }
            assertNotNull(legacy(v3), "the released parser lists a v3 pool")
            assertNotNull(legacy(v5), "the released parser lists a v5 pool")
            assertNull(legacy(v6), "the released parser does not list a v6 pool")
            println("released parser (${jar.name}): v3 listed, v5 listed, v6 not listed")
        }
    }

    /** What Engine keeps in sessions.json for a v6 creator comes back as it was, windows and vote bookkeeping included. */
    @Test fun aCreatorsRoundComesBackFromDisk() {
        val dir = kotlin.io.path.createTempDirectory("kj-v6").toFile()
        val np = PoolSession.newPool(10_000, 1.0, 5, 2, v3 = true, async = true)
        val st = PoolSession.newState(np.terms, true, np.secret, listOf(CoinjoinTx.Coin("ab".repeat(32), 1, 30_000, pub(5))),
            listOf(BigInteger.valueOf(5)), listOf("m/0"), listOf(script(6)), script(7), creatorSecret = np.creatorSecret, blindPriv = np.blindPriv)
        st.phase = PoolSession.Phase.VOTING; st.voteId = "0102030405060708"; st.voteN = 3; st.voteSeq = 4; st.votesSent = 3
        st.accepts += setOf("aa", "bb"); st.usedVotes += "0102030405060708"; st.voteDeadline = np.terms.async!!.final - 100
        File(dir, "sessions.json").writeText(JSONArray().put(st.toJson()).toString())
        val engine = Engine(dir, Rpc("http://127.0.0.1:1/", "u", "p", null)) { emptyList() }
        engine.load()
        val back = engine.states().single()
        assertTrue(back.terms.v6); assertEquals(np.terms.async, back.terms.async)
        assertEquals(PoolSession.Phase.VOTING, back.phase); assertEquals(3, back.voteN); assertEquals(4, back.voteSeq)
        assertEquals(setOf("aa", "bb"), back.accepts); assertEquals(setOf("0102030405060708"), back.usedVotes)
        dir.deleteRecursively()
    }
}
