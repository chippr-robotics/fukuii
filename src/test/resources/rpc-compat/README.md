# Vendored JSON-RPC conformance fixtures

These files are third-party test data used only by tests. They are not shipped in any fukuii artifact.

## Source

| Path | Origin | Licence |
|------|--------|---------|
| `tests/chain.rlp`, `tests/genesis.json`, `tests/forkenv.json`, `tests/headfcu.json` | [ethereum/execution-apis](https://github.com/ethereum/execution-apis) `tests/`, commit `62eefaa` ("eth: leave eth_simulateV1 VM error messages to the client (#948)") | CC0 1.0 Universal (execution-apis `LICENSE`) |
| `tests/<method>/*.io` (16 method directories) | the same execution-apis commit, `tests/<method>/` | CC0 1.0 Universal |
| `amsterdam/ethSimulate-*.io` | execution-apis `tests/eth_simulateV1/`. Added in #1606 against execution-apis #881 (`377333f4de`); byte-identical to the files at `62eefaa` | CC0 1.0 Universal |
| `amsterdam/head-0x3c.json` | fukuii-generated, not vendored: the head header and the trie nodes `eth_getProof` returned from a fukuii node that imported execution-apis' `tests/chain.rlp` (see its `source` field and #1606) | project licence |

Every vendored file is a **verbatim copy**. Nothing has been edited, reformatted or regenerated. hive's
`ethereum/rpc-compat` simulator reads the same files: it clones execution-apis at build time and compares responses
the same way `conformance/RpcCompatVectors` does.

## Specs that read them

- `jsonrpc/conformance/TypedTxConformanceSpec`: imports `tests/` chain in process, runs the `.io` vectors through
  `JsonRpcController` and checks GraphQL against them.
- `jsonrpc/EthSimulateRpcCompatAmsterdamSpec`: `amsterdam/`.

## Refreshing

1. Clone execution-apis at the new commit:
   `git clone https://github.com/ethereum/execution-apis && git -C execution-apis checkout <sha>`.
2. Copy `tests/{chain.rlp,genesis.json,forkenv.json,headfcu.json}` and each `tests/<method>/` directory listed in
   `TypedTxConformanceSpec.methods` over the files here. Use `cp`: the files must stay byte-identical.
3. If the chain changed (a new `chain.rlp`), update the head number and hash in `TypedTxConformanceSpec`. Then
   regenerate `amsterdam/head-0x3c.json` from a node that imported the new chain, or drop the Amsterdam spec's
   dependence on it.
4. Run `sbt "testOnly *TypedTxConformanceSpec *EthSimulateRpcCompatAmsterdamSpec"`. Any vector that now diverges either
   needs a fix or a `knownDivergences` entry that gives the reason.
5. Update the commit in this file, in `NOTICE` and in the spec headers.

The GraphQL counterpart, `src/test/resources/graphql/geth-schema.graphql`, has its provenance in its own header and in
`NOTICE`.
