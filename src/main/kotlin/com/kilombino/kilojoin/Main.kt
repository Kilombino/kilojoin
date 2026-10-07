package com.kilombino.kilojoin

import java.io.File

/**
 * Kilojoin: coinjoin on BTC (BLAKE2b) from a home node, in the same pools as the Kilowallet
 * app (relay.kilombino.com). Configuration comes from the environment, which the StartOS
 * package fills in:
 *   KILOJOIN_DATA      data directory (default /data)
 *   KILOJOIN_PORT      web port (default 8080)
 *   BITCOIN_RPC_URL    e.g. http://bitcoind.startos:8332/
 *   BITCOIN_RPC_USER / BITCOIN_RPC_PASS, or BITCOIN_RPC_COOKIE (path to .cookie)
 */
fun main() {
    val env = System.getenv()
    // Tests against a regtest node use another network name, so their pools never list on mainnet.
    env["KILOJOIN_NETWORK"]?.let { com.kilombino.pyblockwatch.coinjoin.Protocol.NETWORK = it; Wallet.testnet = it != "blake2b" }
    val dir = File(env["KILOJOIN_DATA"] ?: "/data").apply { mkdirs() }
    val rpc = Rpc(
        env["BITCOIN_RPC_URL"] ?: "http://127.0.0.1:8332/",
        env["BITCOIN_RPC_USER"], env["BITCOIN_RPC_PASS"], env["BITCOIN_RPC_COOKIE"],
    )
    val web = Web(dir, rpc, (env["KILOJOIN_PORT"] ?: "8080").toInt())
    web.onEvent { e -> System.err.println("EVENT ${e.title}: ${e.text}") }
    web.start()
    System.err.println("Kilojoin listening on ${env["KILOJOIN_PORT"] ?: "8080"}, node ${env["BITCOIN_RPC_URL"] ?: "http://127.0.0.1:8332/"}")
}
