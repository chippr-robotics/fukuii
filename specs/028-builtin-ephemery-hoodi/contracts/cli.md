# Contract: command line (spec 028)

## `fukuii hoodi`

The same shape as `fukuii sepolia`: `fukuii [public|enterprise] hoodi [--tui] [options]`.
- It loads `conf/hoodi.conf`, from the filesystem first (`FUKUII_CONF_DIR`, `-Dfukuii.conf.dir`, `./conf`), then from
  the classpath.
- It needs a consensus client on the Engine API, as Sepolia does.
- `hoodi` is added to the help text's network list.

## `fukuii ephemery`

`fukuii [public|enterprise] ephemery [--tui] [options]`. It needs no preparation step. Before the node configuration
loads, the startup resolver (data-model.md, Startup decision) picks one of:
- the operator's prepared directory;
- the newest current cached or bundled iteration;
- a freshly fetched one.

**Help text line**, replacing "run scripts/ephemery/fetch-genesis.sh first":

```text
  ephemery               Ephemery testnet (ETH, resets every 28 days; built in, refreshes itself after a reset)
  hoodi                  Hoodi testnet (ETH)
```

The process exit codes are in exit-codes.md.

## `fukuii cli ephemery-fetch`

```text
fukuii cli ephemery-fetch [--release ephemery-N] [--from-dir DIR] [--out DIR]
```

| Flag | Meaning | Default |
|------|---------|---------|
| `--release ephemery-N` | which iteration to fetch; must match `^ephemery-[0-9]+$` | the iteration whose window contains now (R7) |
| `--from-dir DIR` | read an unpacked release from DIR instead of downloading (no network) | download from `fukuii.ephemery.source` |
| `--out DIR` | write **today's script layout** to DIR (FR-015): `DIR/ephemery-N/{testnet,chains,conf,data,release.env,release.json}`, the `DIR/current` symlink, and `DIR/ephemery-local.conf` once. DIR must not contain quotes, backslashes, `$`, backticks or newlines (as `F`:74) | no `--out`: write into **the node's Ephemery root** (`<root>/releases/ephemery-N/`, including `testnet/`), the place the node reads from. This is how to get the CL directory for the bundled iteration (R10), or prepare offline before a reset |

**How it finds its configuration.** `App`'s `determineNetworkArg` returns `None` for `cli`, so a plain `cli`
subcommand never loads `conf/ephemery.conf`. `ephemery-fetch` therefore selects the Ephemery network configuration
itself, with `App.setNetworkConfig("ephemery")`, before it reads the raw config. Then:
- `FUKUII_CONF_DIR` and `-Dfukuii.conf.dir` find an operator `ephemery.conf` exactly as `fukuii ephemery` does.
- `-Dfukuii.datadir` sets the Ephemery root. Without `--out`, the command writes where `fukuii ephemery` with the same
  environment reads.
- `fukuii.ephemery.source` and `refresh-timeout` apply. `refresh = false` does not stop an explicit `ephemery-fetch`:
  the operator asked.

**Behaviour.**
- It applies exactly the node's rules: same library, same refusals, same messages.
- Its exit codes are 0 on success and the class of the refusal otherwise (exit-codes.md). A usage error exits 2, as
  `CheckpointCli` does.
- It prints the `release.env` lines on success.
- It never starts a node and never opens RocksDB.

**Slice availability.**
- **S2:** only `--from-dir` together with `--out`.
- **S4b:** downloading (no `--from-dir`), and the no-`--out` mode.

## `scripts/ephemery/fetch-genesis.sh` (kept, FR-015)

```text
scripts/ephemery/fetch-genesis.sh [--release ephemery-N] [--out DIR] [--from-dir TESTNET_DIR]
```

- **Flags.** Unchanged. `--out` still defaults to `$FUKUII_EPHEMERY_DIR`, else `$HOME/.fukuii/ephemery-network`.
- **The binary.** It runs `${FUKUII_BIN:-fukuii}`. If that is not on PATH, it exits 2, naming `FUKUII_BIN` and the
  sbt `runMain` form for a checkout.
- **S2.** The script still downloads with curl and unpacks, then runs
  `fukuii cli ephemery-fetch --from-dir <unpacked> --out "$OUT"` for everything else.
- **S4b.** The script becomes `exec "${FUKUII_BIN:-fukuii}" cli ephemery-fetch "$@"`, after it fills in the default
  `--out`.
- **The runbook.** It calls the script the optional way to pin or prepare an iteration (FR-024).

## `scripts/ephemery/update-bundled.sh` (new, FR-003; for maintainers)

```text
scripts/ephemery/update-bundled.sh ephemery-N
```

1. Builds the staged distribution (`sbt stage`, under the sbt lock).
2. Runs `FUKUII_BIN=target/universal/stage/bin/fukuii scripts/ephemery/fetch-genesis.sh --release ephemery-N --out
   <tmp>`. This works from S3 on: until S4b the script downloads with curl and delegates the rules to the CLI, and
   after S4b it is the CLI.
3. Replaces `src/main/resources/ephemery/bundled/ephemery-*` with `ephemery-N/{ephemery-chain.conf,
   ephemery-genesis.json,release.json}`.
4. Copies the raw EL inputs from `<tmp>/ephemery-N/testnet/` (genesis.json, retention.vars,
   deposit_contract_block_hash.txt, enodes.txt, boot_enr.txt) to `src/test/resources/ephemery/releases/ephemery-N/`.
5. Prints the files to `git add`.

It refuses if the release fails validation. `BundledEphemerySpec` then pins the result.
