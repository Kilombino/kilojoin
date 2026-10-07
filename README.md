# Kilojoin

Coinjoin on BTC (the BLAKE2b chain) from your own node, in the same pools as the
[Kilowallet](https://github.com/Kilombino/kilowallet) Android app.

- Same protocol and same code as the app: Kilowallet is a git submodule, and its
  `coinjoin/` and `crypto/` packages are compiled straight into this server.
- Same relay, `wss://relay.kilombino.com`, so servers and phones meet in the same pools.
- A web page to import or create a BIP-39 wallet (BIP84), receive, send with coin control,
  prepare an exact coin for a pool, open or join pools, vote and sign. Coins are labelled
  "mixed" (a coinjoin output) or "change", and a send that would link a mixed coin to
  others asks first. Spends are signed with the unified sighash (0x21), like Kilowallet. The words are stored encrypted with your password
  (PBKDF2-SHA256 600,000 rounds + AES-GCM).
- Every coin offered to a pool is checked against your node (`gettxout`); the wallet is
  found with `scantxoutset`, so a pruned node is enough.

On StartOS, install it from the Kilombino Registry (package `kilojoin`, wrapper at
[kilojoin-startos](https://github.com/Kilombino/kilojoin-startos)).

## Build and run

```sh
git clone --recursive https://github.com/Kilombino/kilojoin
cd kilojoin
./gradlew fatJar            # build/libs/kilojoin.jar (JDK 17)
docker build -t kilojoin .  # or the container
```

| Variable             | Default                  | Meaning                                    |
| -------------------- | ------------------------ | ------------------------------------------ |
| `KILOJOIN_DATA`      | `/data`                  | Data directory                             |
| `KILOJOIN_PORT`      | `8080`                   | Web port                                   |
| `BITCOIN_RPC_URL`    | `http://127.0.0.1:8332/` | The BLAKE2b node's RPC                     |
| `BITCOIN_RPC_COOKIE` |                          | Path to its `.cookie`, re-read on each call |
| `BITCOIN_RPC_USER` / `BITCOIN_RPC_PASS` |       | Instead of the cookie                      |

`/internal/events?since=<ms>` answers loopback only, with the events the page shows
(new pool, vote, sign now, sent, confirmed…); the StartOS package turns them into
notifications.

## License

Apache 2.0, like Kilowallet.
