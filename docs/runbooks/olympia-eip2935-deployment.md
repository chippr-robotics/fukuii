# Olympia: deploying the EIP-2935 history contract

**Audience**: operators and release coordinators of ETC mainnet and Mordor
**When**: once per network, before the Olympia activation block
**Applies to**: Olympia (ECIP-1121), which brings EIP-2935 ("Serve historical block hashes from state") to ETC
unmodified

## Why this is needed

From Olympia, every block stores its parent's hash in the history contract at
`HISTORY_STORAGE_ADDRESS` = `0x0000F90827F1C53a10cb7A02335B175320002935`, in a ring buffer of 8191 slots.
`BLOCKHASH` is unchanged. Contracts that need older hashes read the history contract.

The client does **not** create that contract. EIP-2935 says: "if no code exists at HISTORY_STORAGE_ADDRESS, the call
must fail silently". A block whose state has no code at the address changes nothing. go-ethereum behaves this way, and
so does fukuii from B-528. Earlier fukuii builds deployed the contract themselves at the Olympia block; that was an
irregular state change that no specification defines, and it has been removed.

The contract arrives by EIP-2935's own deploy transaction. **If that transaction has not landed on a network before
Olympia activates, the history stays empty**: nothing is written until the contract is deployed. The hashes of blocks
from before then are never recorded. Send it on **Mordor** and on **ETC mainnet**, each before its Olympia block.

## The deploy transaction

It is the transaction from the EIP's "Deployment" section, unchanged. It uses Nick's method: the signature is made up,
so nobody holds the sender's key, and the sender, the nonce and therefore the contract address are the same on every
chain.

| Field | Value |
|-------|-------|
| Transaction hash | `0x67139a552b0d3fffc30c0fa7d0c20d42144138c8fe07fc5691f09c1cce632e15` |
| Sender | `0x3462413Af4609098e1E27A490f554f260213D685` (nonce 0) |
| Creates | `0x0000F90827F1C53a10cb7A02335B175320002935` |
| Type | legacy (0x0), `v = 27`: **pre-EIP-155, no chain id** |
| Gas limit | 250,000 (`0x3d090`) |
| Gas price | 1000 gwei (`0xe8d4a51000`) |
| Cost | up to 250,000 x 1000 gwei = **0.25 ETC**, which must be in the sender's balance |

Raw transaction, for `eth_sendRawTransaction` (pinned by `Eip2935DeployTransactionSpec`, which checks that it decodes to
the hash and sender above):

```
0xf8838085e8d4a510008303d0908080b85c60538060095f395ff33373fffffffffffffffffffffffffffffffffffffffe14604657602036036042575f35600143038111604257611fff81430311604257611fff9006545f5260205ff35b5f5ffd5b5f35611fff600143030655001b820539930aa12693182426612186309f02cfe8a80a0000
```

### Requirements

- **PUSH0 (EIP-3855)**: the initcode starts `60 53 80 60 09 5f ...`, and `5f` is PUSH0. ETC has it from **Spiral**, which
  is active on both ETC mainnet and Mordor. On a chain without Spiral the creation fails and the gas is spent.
- **An unprotected transaction**: the transaction has no EIP-155 chain id. ETC's consensus rules accept such
  transactions at every height, so a block that carries it is valid. Over RPC, however, fukuii (like go-ethereum and
  core-geth) refuses it by default with "only replay-protected (EIP-155) transactions allowed over RPC". Submit it
  through a node configured with:

  ```hocon
  fukuii.network.rpc.allow-unprotected-txs = true
  ```

  On core-geth the equivalent flag is `--rpc.allow-unprotected-txs`. Turn the setting off again afterwards.
- **A funded sender**: send at least 0.25 ETC to `0x3462413Af4609098e1E27A490f554f260213D685` first. Any account can
  fund it. Nobody can spend from it except with this one transaction.
- **Gas price**: if it is sent after Olympia, the 1000 gwei price must cover the block's base fee plus the 1 gwei
  minimum tip (ECIP-1122). Sending it before Olympia avoids the question.

## Procedure, per network (Mordor first, then ETC mainnet)

1. Check that it is not already deployed:

   ```bash
   curl -s -X POST -H 'Content-Type: application/json' \
     --data '{"jsonrpc":"2.0","id":1,"method":"eth_getCode","params":["0x0000F90827F1C53a10cb7A02335B175320002935","latest"]}' \
     http://localhost:8546
   ```

   `"result":"0x"` means not deployed. The deployed code is given below.
2. Check the sender's nonce is 0 (`eth_getTransactionCount` for `0x3462413A...D685`). If it is 1, the transaction has
   already been sent on this network.
3. Fund the sender with at least 0.25 ETC.
4. On a node with `allow-unprotected-txs = true`, call `eth_sendRawTransaction` with the raw transaction above. The
   returned hash must be `0x67139a55...632e15`.
5. Wait for the receipt (`eth_getTransactionReceipt`): `status` `0x1`, `contractAddress`
   `0x0000f90827f1c53a10cb7a02335b175320002935`.
6. Verify the code with `eth_getCode` at the address, as in step 1. It must return exactly:

   ```
   0x3373fffffffffffffffffffffffffffffffffffffffe14604657602036036042575f35600143038111604257611fff81430311604257611fff9006545f5260205ff35b5f5ffd5b5f35611fff60014303065500
   ```

7. Turn `allow-unprotected-txs` off again.

## After Olympia activates

Each block from the Olympia block on stores its parent's hash at slot `(number - 1) % 8191`. Check it with
`eth_getStorageAt` at `0x0000F90827F1C53a10cb7A02335B175320002935`: at block `N`, slot `(N - 1) % 8191` (as a hex
quantity) holds the hash of block `N - 1`. If the code check in step 6 fails at activation, the contract was not
deployed in time. Deploy it now: hashes are recorded from the block after it lands.

## References

- EIP-2935, "Deployment" and "Block processing": `EIPS/eip-2935.md`
- ECIP-1121 (Olympia EVM set), which adopts EIP-2935 unmodified
- fukuii: `BlockExecution.applyEip2935` (one path for ETH from Prague and ETC from Olympia); tests
  `Eip2935DeployTransactionSpec`, `BlockHashHistorySpec`, `PragueSystemContractDeploymentSpec`
