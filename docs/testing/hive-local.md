# Running hive locally

## When hive runs on GitHub Actions

GitHub Actions runners are a small shared pool, and the hive suites tie up runners for a long
time:

| Suite | Per run |
|---|---|
| consensus, consume-engine, consume-rlp | 40–80 min sample each |
| engine | about 50 min |
| full pass | about 33 jobs × 3.5 h |

So they are a **release gate**, not a per-push check:

| Workflow | Runs on |
|---|---|
| hive-smoke-genesis, hive-smoke-network, hive-sync, hive-rpc-compat, hive-graphql (5–10 min each) | every PR to `staging` or `main` (path-filtered) |
| hive-consensus, hive-consume-engine, hive-consume-rlp, hive-engine, hive-devp2p, hive-osaka, hive-prague | PRs to `main` (the staging → main release PR), `workflow_dispatch` |
| hive-full (sharded, runs to completion) | PRs to `main` labelled `hive-full`, `workflow_dispatch` |

Before cutting a version, open the staging → main PR, add the `hive-full` label, and wait for
the `aggregate` verdict.

## Targeted runs on a local machine

To check a fix between releases, run just the affected tests locally:

```bash
# one-time: a hive checkout with a built binary
git clone https://github.com/ethereum/hive ~/hive && (cd ~/hive && go build .)

# from the fukuii checkout under test
scripts/hive/local.sh ethereum/consensus \
  --limit 'legacy-cancun/.*static_Call1MB1024Calldepth.*'

# reuse the last assembly (no sbt rebuild)
scripts/hive/local.sh ethereum/eels/consume-engine \
  --limit '.*static_Call50000.*' --skip-build --parallelism 2
```

The script does the same steps as `.github/workflows/_hive-sim.yml`:

1. Runs `sbt assembly`.
2. Builds the thin `chipprbots/fukuii:latest` image.
3. Copies `hive/fukuii/*` and the jar into `$HIVE_DIR/clients/fukuii`.
4. Runs hive.
5. Prints the pass/fail counts and the names of failing tests. The script exits non-zero if
   any test failed.

Options:

| Option | Default | Meaning |
|---|---|---|
| `--limit REGEX` | none | hive `--sim.limit` |
| `--parallelism N` | 4 | hive `--sim.parallelism` |
| `--timelimit` | 40m | hive `--sim.timelimit` |
| `--checktimelimit` | 120s | hive `--client.checktimelimit` |
| `--skip-build` | off | reuse the last assembly |
| `-- …` | | extra arguments passed straight to hive |

Run it from a normal clone, not a `git worktree`: the build's sbt-git plugin fails to load in
a linked worktree (`NoWorkTreeException`).

Logs are in `$HIVE_DIR/workspace/logs/`. Each client log is under `fukuii/`.

Expect fixed overhead per run. On the ops node (4 cores), a single
`consensus/.*UncleFromSideChain_Cancun` test took 22.6 min in total: the assembly build plus
about 16 min inside the consensus simulator, which loads its whole fixture corpus whatever
`--limit` selects. Pass `--skip-build` to reuse the last assembly while iterating.

For comparison with CI, keep `--parallelism` at 4. Resource failures depend on how many
clients run at once: a heavy vector that passes with `--parallelism 1` can still time out
at 4.
