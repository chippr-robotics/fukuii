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
5. Prints the pass/fail counts and the names of failing tests for every suite the run
   produced. The script exits non-zero if any test failed.

Steps 2–5 hold a machine-wide lock (`/tmp/fukuii-hive-local.lock`). The Docker image tags they
build are global to the daemon, so runs from several clones, even ones using different hive
checkouts, queue up instead of testing each other's jar.

Options:

| Option | Default | Meaning |
|---|---|---|
| `--limit REGEX` | none | hive `--sim.limit` |
| `--skip 'A\|B'` | none | tests NOT to run, as CI's `sim_skip`; `--limit` must then name suites only |
| `--parallelism N` | 4 | hive `--sim.parallelism` |
| `--timelimit` | 40m | hive `--sim.timelimit` |
| `--checktimelimit` | 120s | hive `--client.checktimelimit` |
| `--clients LIST` | fukuii | hive `--client`, e.g. `fukuii,go-ethereum` to compare with a reference client or to run the cross-client sync tests |
| `--skip-build` | off | reuse the last assembly |
| `-- …` | | extra arguments passed straight to hive |

`devp2p`'s `eth` suite has two tests, `GetCells` and `BlobTxWithInvalidCells`, that wait
without a timeout for a `GetCells` request fukuii cannot send until #1409. Run the suite with
`--limit eth --skip 'GetCells|BlobTxWithInvalidCells'`, as `hive-devp2p.yml` does, or it hangs
until the time limit.

Run it from a normal clone, not a `git worktree`: the build's sbt-git plugin fails to load in
a linked worktree (`NoWorkTreeException`).

Use a hive checkout at the same `master` that CI clones. The simulators change between
commits, so an older checkout can give different results from CI. Go is not needed on the
host; build the binary in a container:

```bash
git clone --depth=1 https://github.com/ethereum/hive.git ~/hive-master && cd ~/hive-master
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -e GOCACHE=/tmp/gocache -e GOPATH=/tmp/gopath \
  -e CGO_ENABLED=0 -v "$PWD":/src -w /src golang:1.25-alpine go build -o hive .
```

On a workstation with `systemd-oomd` (Ubuntu desktop default), run the script as its own user
unit. Under memory pressure oomd kills a whole cgroup. A run started straight from a terminal
shares that terminal's cgroup, so oomd kills the terminal too. That happened on the ops node
(4 cores, 15 GB) with four heavy clients running in parallel.

```bash
systemd-run --user --unit=hive-local --collect -p MemoryHigh=4G \
  --working-directory="$PWD" -E HIVE_DIR="$HOME/hive-master" \
  scripts/hive/local.sh ethereum/engine --limit '/Blob Transaction' --parallelism 2
journalctl --user -fu hive-local     # follow it
```

On a machine that size, use `--parallelism 2`. The heaviest vectors, such as
`CALLBlake2f_MaxRounds`, take about 130 s each at that setting.

Logs are in `$HIVE_DIR/workspace/logs/`. Each client log is under `fukuii/`.

Expect fixed overhead per run. On the ops node (4 cores), a single
`consensus/.*UncleFromSideChain_Cancun` test took 22.6 min in total: the assembly build plus
about 16 min inside the consensus simulator, which loads its whole fixture corpus whatever
`--limit` selects. Pass `--skip-build` to reuse the last assembly while iterating.

For an exact comparison with CI, use `--parallelism 4` if the machine can take it. Resource
failures depend on how many clients run at once: a heavy vector that passes with
`--parallelism 1` or `2` can still time out at 4. A resource-bound result from a local run
at lower parallelism therefore needs its CI counterpart before anyone relies on it.
