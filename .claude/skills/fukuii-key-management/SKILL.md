---
name: fukuii-key-management
description: >-
  Generate and handle Fukuii cryptographic material via the `fukuii cli`
  subcommands — node private keys, key pairs, address derivation, key encryption,
  and genesis allocation snippets. Use when bootstrapping a node identity,
  creating accounts/keys, deriving an address from a private key, encrypting a
  key for the keystore, or building genesis allocs for a custom network. Keys are
  secrets — handling them is an irreversible, security-sensitive action under the
  guarded-write protocol.
---

# Fukuii key management

Read `../CONVENTIONS.md` first. **Everything here touches secrets.** Treat key
output as 🔴: never echo a private key into shared logs/PRs, restrict file
permissions, and confirm intent before generating or overwriting `node.key`.

## When to use
- First-time node bootstrap (create `node.key`) — see also `fukuii-first-start`.
- Create account key pairs, derive an address, or encrypt a key for the keystore.
- Build `alloc` entries for a custom-genesis chain (`fukuii-custom-networks`).

## Available subcommands (exactly these — verified in `cli/CliCommands.scala`)
| Command | Purpose |
| :-- | :-- |
| `fukuii cli generate-private-key` | Emit a new random secp256k1 private key |
| `fukuii cli generate-key-pairs [n]` | Emit `n` key pairs (priv on line 1, pub/node-id on line 2) |
| `fukuii cli derive-address <private-key>` | Derive the account/address from a private key |
| `fukuii cli encrypt-key --passphrase <p> <private-key>` | Produce an encrypted keystore entry |
| `fukuii cli generate-allocs --balance <b> [--key …] [--address …]` | Genesis `alloc` JSON |

> There is **no** `import-key`, `compact-database`, or other subcommand beyond
> the five above. Don't invent flags — check `fukuii cli <cmd> --help`.

## Procedure
1. **Confirm scope & isolation** — run on a trusted host; ensure output goes to a
   secure location, not a terminal others can see.
2. **Generate** (🔴) — pick the subcommand above. For a node identity, the public
   line of `generate-key-pairs` is the node ID used in enodes.
3. **Encrypt before storing** — for account keys, `encrypt-key` then place the
   resulting file under `~/.fukuii/<network>/keystore/` with tight permissions.
4. **Back up immediately** (🔴) — keys are unrecoverable; copy to secure off-box
   storage per `fukuii-backup-restore` before relying on them.
5. **Never** reuse, commit, or paste private keys; rotate if exposure is suspected.

## Deep reference
- `src/main/scala/com/chipprbots/ethereum/cli/CliCommands.scala` (source of truth)
- `docs/runbooks/first-start.md` (node.key bootstrap), `docs/runbooks/security.md`

## Output
CONVENTIONS §4 block — **without** secret material. Report *what* was generated
and *where* it was stored (path/permissions), never the key value.
