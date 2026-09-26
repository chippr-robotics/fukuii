#!/usr/bin/env python3
"""Generate the hive shard plan for the suites that cannot finish inside one CI job.

WHY. consume-engine collects 42,234 tests, consume-rlp 47,589, and consensus roughly 50,000,
and every test starts a fresh client container (~8 s each, measured). At --sim.parallelism 4 a
complete pass is ~23 h per consume suite, while a GitHub-hosted job is capped at 6 h. The only
way to run them to completion is to split each suite across jobs. This script produces that
split, and PROVES it: every known test must be selected by exactly one shard, or it exits 1.

HOW. Each suite's test keys are placed in a prefix trie, each key weighted by its expected run
time (see SECONDS_PER_TEST). A trie node is split only while it holds more test time than one
shard should run; every split node gets a catch-all branch -- "this prefix followed by any
character none of my children claim, or by nothing" -- owned by exactly one shard. The resulting
patterns are therefore an exact partition of ALL possible keys, not just the ones seen when the
plan was generated: a test added upstream still lands in exactly one shard. The leaves are then
bin-packed into shards by expected time. Each shard records its exact test count (est_tests,
which aggregate-full.py checks for consume-*) and its expected minutes (est_minutes).

HOW THE FILTERS ARE MATCHED (read before editing a pattern):

  consume-* (EELS): hive passes --sim.limit to `consume` as HIVE_TEST_PATTERN; consume hands it
  to pytest-regex, which calls re.match(pattern, item.nodeid) -- Python `re`, anchored at the
  START of the pytest node id:
      <sim file>.py::test_<x>[tests/<path>::<inner name>[fork_<Fork>-<format>...]-<client>]
  Key: the inner name, per fork. Patterns look like `.*::<prefix><class>[^\\[:]*\\[fork_F-`.
  The last shard also takes any fork the plan does not know (negative lookahead).

  consensus (Go hivesim): --sim.limit is split at the first `/` into a suite regex and a test
  regex, each wrapped in (?i:...) -- case-INSENSITIVE, UNANCHORED, RE2 (no lookahead). The
  consensus simulator matches the test regex against the test FILE path relative to the suite
  root (e.g. `/GeneralStateTests/stTimeConsuming/sstore_combinations_initial00`). Keys are those
  paths, case-folded; patterns are `^<prefix>` and `^<prefix>(?:$|[^children])`. A file's weight
  is its exact test count at the pinned commit (one test per top-level key) times the suite's
  seconds per test; files hold from 1 to over 1,000 tests, so a per-file average misplaces time.

Usage:
  scripts/hive/gen-shards.py            # regenerate .github/hive-shards.json and verify
  scripts/hive/gen-shards.py --check    # verify the committed plan is current and exact
"""

import argparse
import collections
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

# ---- pinned sources -------------------------------------------------------------------------
# consume-* fixtures are pinned (passed to the simulator as --sim.buildarg fixtures=...), so the
# plan is exact for them. The consensus simulator clones ethereum/tests HEAD at image build; its
# plan is balanced against these commits but stays exhaustive whatever HEAD becomes.
EEST_FIXTURES = "stable@v5.4.0"
EEST_URL = "https://github.com/ethereum/execution-spec-tests/releases/download/v5.4.0/fixtures_stable.tar.gz"
ETH_TESTS_COMMIT = "c67e485ff8b5be9abc8ad15345ec21aa22e290d9"
LEGACY_TESTS_COMMIT = "1f581b8ccdc4c63acf5f2c5c1b155c690c32a8eb"

# ---- sizing ---------------------------------------------------------------------------------
# Shards are sized by expected run time, not by test count. The release full pass of 2026-09-24
# (run 36042798537, commit 7dc514b28) timed every test it ran:
#   - a consume test costs ~7 s on a pre-Cancun fork and ~12 s from Cancun on, where the client
#     also loads the KZG trusted setup and a larger genesis;
#   - consensus tests cost ~6 s (legacy), ~8 s (legacy-cancun) and ~11 s (consensus), but files
#     hold anywhere from 1 to over 1,000 tests each;
#   - one runner ran the same tests 1.6x faster than the others.
# Count-based targets left four consume-engine shards at 283-292 min and three shards cut off by
# the 300 min simulator limit. SECONDS_PER_TEST is the 75th percentile of that run's per-test
# durations, so a shard is sized for a slow runner. A fork the table does not know is costed at
# its suite's slowest fork.
SECONDS_PER_TEST = {
    "consume-engine": {"Paris": 7.3, "Shanghai": 7.2, "ParisToShanghaiAtTime15k": 6.9, "Cancun": 12.2,
                       "ShanghaiToCancunAtTime15k": 12.6, "Prague": 12.3, "CancunToPragueAtTime15k": 13.0},
    "consume-rlp": {"Berlin": 5.9, "Byzantium": 6.4, "Istanbul": 6.4, "London": 6.3, "Paris": 6.4,
                    "Shanghai": 7.3, "ParisToShanghaiAtTime15k": 7.1, "Cancun": 11.9,
                    "ShanghaiToCancunAtTime15k": 11.8, "Prague": 10.1},
    "consensus": {"consensus": 10.7, "legacy": 6.0, "legacy-cancun": 7.7},
}
PARALLELISM = 4  # --sim.parallelism in hive-full.yml
# Minutes of test time per shard. 170 leaves the 300 min simulator limit room for a runner 1.6x
# slower than the table. Consensus gets less: its "test file loader" reads the whole corpus
# before the first test, measured at up to 26 min.
SHARD_MINUTES = {"consume-engine": 170, "consume-rlp": 170, "consensus": 145}
TARGET = {suite: minutes * 60 * PARALLELISM for suite, minutes in SHARD_MINUTES.items()}  # test-seconds
# The consensus simulator matches whole files, so a file is never split. A file bigger than its
# shard target gets a shard to itself, provided it can still finish inside hive-full.yml's 300 min
# simulator limit less the loader: legacy-cancun's stEIP1559/intrinsic holds 6,960 tests, ~223 min.
MAX_UNSPLITTABLE_MINUTES = 250


def seconds_per_test(suite, kind):
    table = SECONDS_PER_TEST[suite]
    return table.get(kind, max(table.values()))


FORMAT = {"consume-engine": "blockchain_test_engine", "consume-rlp": "blockchain_test"}
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PLAN_PATH = os.path.join(ROOT, ".github", "hive-shards.json")


# ---- generic trie partition -----------------------------------------------------------------
def trie_units(weights, counts, target):
    """Split the keys of `weights` (key -> expected test-seconds) into units that each weigh <= target.

    `counts` maps key -> number of tests. Returns a list of (kind, prefix, children, weight, count):
      ("all", P, None, w, n)          every key starting with P
      ("rest", P, children, w, n)     P itself, or P followed by a char not in `children`
    Together the units partition every possible key, seen or not.
    """
    units = []

    def split(prefix, keys):
        total = sum(weights[k] for k in keys)
        if total <= target:
            units.append(("all", prefix, None, total, sum(counts[k] for k in keys)))
            return
        by_next = collections.defaultdict(list)
        exact_w = exact_n = 0
        for k in keys:
            if len(k) == len(prefix):
                exact_w += weights[k]
                exact_n += counts[k]
            else:
                by_next[k[len(prefix)]].append(k)
        if not by_next:
            # A single key: nothing to split on. It becomes its own shard, if it can finish at all.
            minutes = shard_minutes(total)
            if minutes > MAX_UNSPLITTABLE_MINUTES:
                sys.exit(f"key {prefix!r} alone needs ~{minutes} min, over {MAX_UNSPLITTABLE_MINUTES}")
            print(f"note: {prefix!r} cannot be split and gets a shard of its own (~{minutes} min)", file=sys.stderr)
            units.append(("all", prefix, None, total, sum(counts[k] for k in keys)))
            return
        units.append(("rest", prefix, frozenset(by_next), exact_w, exact_n))
        for c in sorted(by_next):
            split(prefix + c, by_next[c])

    split("", list(weights))
    return units


def pack(items, target):
    """First-fit-decreasing bin packing of items (dicts with a weight "w") into bins of <= target."""
    bins = []
    for item in sorted(items, key=lambda it: -it["w"]):
        for b in bins:
            if b["weight"] + item["w"] <= target:
                b["items"].append(item)
                b["weight"] += item["w"]
                break
        else:
            bins.append({"items": [item], "weight": item["w"]})
    return bins


def shard_minutes(weight):
    """Expected minutes of test time for a shard of `weight` test-seconds."""
    return round(weight / PARALLELISM / 60)


def esc(s):
    return re.escape(s)


def cls(chars, negate, extra_excluded=""):
    body = "".join(esc(c) for c in sorted(chars)) + extra_excluded
    return ("[^" if negate else "[") + body + "]"


# ---- consume-* ------------------------------------------------------------------------------
INNER = re.compile(r"::([^\[:]*)\[fork_([A-Za-z0-9]+)-")
NAME_REST = r"[^\[:]*"


def load_eest_ids(cache_dir):
    path = os.path.join(cache_dir, "fixtures_stable.tar.gz")
    if not os.path.exists(path):
        print(f"downloading {EEST_URL}", file=sys.stderr)
        urllib.request.urlretrieve(EEST_URL, path)
    with tarfile.open(path, "r:gz") as tf:
        index = json.load(tf.extractfile(tf.getmember("fixtures/.meta/index.json")))
    ids = collections.defaultdict(list)
    for tc in index["test_cases"]:
        ids[tc["format"]].append(tc["id"])
    return ids


def consume_pattern(fork, unit):
    kind, prefix, children = unit[:3]
    tail = f"\\[fork_{fork}-"
    if kind == "all":
        return esc(prefix) + NAME_REST + tail
    # prefix itself, or prefix + an unclaimed char (never '[' or ':', which end the name)
    return esc(prefix) + "(?:" + cls(children, True, "\\[:") + NAME_REST + ")?" + tail


def consume_plan(suite, ids):
    per_fork = collections.defaultdict(collections.Counter)
    for tid in ids:
        m = INNER.search(tid)
        if not m or not m.group(1):
            sys.exit(f"{suite}: cannot parse fork/name from id {tid!r}")
        per_fork[m.group(2)][m.group(1)] += 1
    forks = sorted(per_fork)
    items = []
    for fork in forks:
        cost = seconds_per_test(suite, fork)
        counts = per_fork[fork]
        weights = {name: n * cost for name, n in counts.items()}
        for u in trie_units(weights, counts, TARGET[suite]):
            items.append({"fork": fork, "unit": u, "w": u[3], "n": u[4]})
    bins = pack(items, TARGET[suite])
    shards = []
    for i, b in enumerate(bins):
        alts = [consume_pattern(it["fork"], it["unit"])
                for it in sorted(b["items"], key=lambda it: (it["fork"], it["unit"][1]))]
        body = ".*::(?:" + "|".join(alts) + ")"
        if i == len(bins) - 1:
            body = f"(?:{body}|(?!.*\\[fork_(?:{'|'.join(forks)})-))"
        shards.append({"name": f"{suite}-{i + 1:02d}", "sim_limit": body,
                       "est_tests": sum(it["n"] for it in b["items"]), "est_minutes": shard_minutes(b["weight"])})
    return shards


def verify_consume(suite, shards, ids):
    """Each id must match exactly one shard, whatever the outer simulator test is called."""
    progs = [re.compile(s["sim_limit"]) for s in shards]
    outers = ["src/x/test_via_engine.py::test_blockchain_via_engine", "src/x/test_via_rlp.py::test_via_rlp"]
    probes = ["tests/x/y.py::test_a[fork_Amsterdam-z]",      # unknown fork
              "tests/x/y.py::~odd[fork_Prague-z]",            # unseen leading char
              "tests/x/y.py::test_valid_zzz[fork_Prague-z]",  # unseen continuation
              "tests/x/y.py::t[fork_Prague-z]"]               # a bare trie prefix
    bad, per = 0, collections.Counter()
    for tid in list(ids) + probes:
        for outer in outers:
            nodeid = f"{outer}[{tid}-fukuii]"
            hits = [i for i, p in enumerate(progs) if p.match(nodeid)]
            if len(hits) != 1:
                bad += 1
                if bad <= 5:
                    print(f"{suite}: {len(hits)} shards match {nodeid}", file=sys.stderr)
            elif outer == outers[0] and tid not in probes:
                per[hits[0]] += 1
    for i, s in enumerate(shards):
        if per[i] != s["est_tests"]:
            bad += 1
            print(f"{suite}: {s['name']} selects {per[i]}, planned {s['est_tests']}", file=sys.stderr)
    return bad


# ---- consensus ------------------------------------------------------------------------------
CONSENSUS_SUITES = {
    "consensus": ("ethereum/tests", ETH_TESTS_COMMIT, "BlockchainTests"),
    "legacy": ("ethereum/legacytests", LEGACY_TESTS_COMMIT, "Constantinople/BlockchainTests"),
    "legacy-cancun": ("ethereum/legacytests", LEGACY_TESTS_COMMIT, "Cancun/BlockchainTests"),
}


def list_files(repo, commit, root, cache_dir):
    d = os.path.join(cache_dir, repo.replace("/", "_"))
    if not os.path.isdir(d):
        subprocess.run(["git", "clone", "-q", "--filter=blob:none", "--no-checkout",
                        f"https://github.com/{repo}", d], check=True)
    out = subprocess.run(["git", "-C", d, "ls-tree", "-r", "--name-only", commit],
                         check=True, capture_output=True, text=True).stdout.split("\n")
    prefix = root + "/"
    return [p[len(root):-len(".json")] for p in out
            if p.startswith(prefix) and p.endswith(".json") and "/.meta/" not in p]


# One test per top-level key. The fixtures are pretty-printed with a 4-space indent, so a line scan
# counts them without parsing files that run to hundreds of MB.
TOP_LEVEL_KEY = re.compile(r'^    "[^"]+"\s*:\s*\{')


def count_tests(repo, commit, root, cache_dir):
    """Tests in each file under `root` at `commit`, keyed like list_files.

    Reading the files needs their contents. A blob-less clone fetches them a batch at a time
    (hours for the legacy corpus), so this fetches the one pinned commit shallowly instead: every
    blob in one pack, under a minute.
    """
    files = list_files(repo, commit, root, cache_dir)
    d = os.path.join(cache_dir, f"{repo.replace('/', '_')}-{commit[:12]}")
    if not os.path.isdir(os.path.join(d, ".git")):
        print(f"fetching {repo}@{commit[:10]}", file=sys.stderr)
        subprocess.run(["git", "init", "-q", d], check=True)
        subprocess.run(["git", "-C", d, "remote", "add", "origin", f"https://github.com/{repo}"], check=True)
        subprocess.run(["git", "-C", d, "fetch", "-q", "--depth", "1", "origin", commit], check=True)
    marker = os.path.join(d, f".checked-out-{root.replace('/', '_')}")
    if not os.path.exists(marker):
        subprocess.run(["git", "-C", d, "-c", "advice.detachedHead=false", "checkout", "-q", commit, "--", root],
                       check=True)
        open(marker, "w").close()
    tests = {}
    for p in files:
        path = os.path.join(d, root + p + ".json")
        with open(path, encoding="utf-8", errors="replace") as f:
            n = sum(1 for line in f if TOP_LEVEL_KEY.match(line))
        if n == 0 and os.path.getsize(path) > 2:
            with open(path, encoding="utf-8") as f:
                n = len(json.load(f))
        tests[p] = n
    return tests


def consensus_pattern(unit):
    kind, prefix, children = unit[:3]
    if kind == "all":
        return "^" + esc(prefix)
    return "^" + esc(prefix) + "(?:$|" + cls(children, True) + ")"


def consensus_plan(tests_by_suite):
    shards = []
    for suite, tests in tests_by_suite.items():
        cost = seconds_per_test("consensus", suite)
        counts, weights = collections.Counter(), collections.Counter()
        for p, n in tests.items():
            counts[p.lower()] += n
            weights[p.lower()] += n * cost
        units = trie_units(weights, counts, TARGET["consensus"])
        bins = pack([{"unit": u, "w": u[3], "n": u[4]} for u in units], TARGET["consensus"])
        for i, b in enumerate(bins):
            alts = [consensus_pattern(it["unit"]) for it in sorted(b["items"], key=lambda it: it["unit"][1])]
            name = f"consensus-{suite}" + (f"-{i + 1:02d}" if len(bins) > 1 else "")
            shards.append({"name": name, "sim_limit": f"^{suite}$/" + "|".join(alts),
                           "est_tests": sum(it["n"] for it in b["items"]), "est_minutes": shard_minutes(b["weight"])})
    return shards


def verify_consensus(shards, files_by_suite):
    compiled = []
    for s in shards:
        suite_re, test_re = s["sim_limit"].split("/", 1)
        compiled.append((re.compile("(?i:" + suite_re + ")"), re.compile("(?i:" + test_re + ")")))
    bad = 0
    for suite, files in files_by_suite.items():
        for p in files + ["/GeneralStateTests/stQzNew/x", "/Zz/y/z", "/a/b/c", "/GENERALSTATETESTS/STTIMECONSUMING/X"]:
            hits = [i for i, (sr, tr) in enumerate(compiled) if sr.search(suite) and tr.search(p)]
            if len(hits) != 1:
                bad += 1
                if bad <= 5:
                    print(f"consensus/{suite}: {len(hits)} shards match {p}", file=sys.stderr)
    return bad


# ---- main -----------------------------------------------------------------------------------
def build(cache_dir):
    ids = load_eest_ids(cache_dir)
    plan = {"generated_by": "scripts/hive/gen-shards.py", "eest_fixtures": EEST_FIXTURES,
            "ethereum_tests_commit": ETH_TESTS_COMMIT, "legacy_tests_commit": LEGACY_TESTS_COMMIT,
            "suites": {}}
    bad = 0
    for suite, fmt in FORMAT.items():
        shards = consume_plan(suite, ids[fmt])
        bad += verify_consume(suite, shards, ids[fmt])
        plan["suites"][suite] = {"expected_tests": len(ids[fmt]), "shards": shards}
    tests = {s: count_tests(repo, commit, root, cache_dir) for s, (repo, commit, root) in CONSENSUS_SUITES.items()}
    shards = consensus_plan(tests)
    bad += verify_consensus(shards, {s: list(t) for s, t in tests.items()})
    plan["suites"]["consensus"] = {"expected_files": {s: len(t) for s, t in tests.items()},
                                   "expected_tests": {s: sum(t.values()) for s, t in tests.items()},
                                   "shards": shards}
    return plan, bad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="verify the committed plan is current and exact")
    ap.add_argument("--cache", default=os.path.join(tempfile.gettempdir(), "hive-shards-cache"))
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)
    plan, bad = build(args.cache)
    if bad:
        sys.exit(f"shard plan is NOT an exact partition ({bad} problems)")
    text = json.dumps(plan, indent=2) + "\n"
    if args.check:
        with open(PLAN_PATH) as f:
            if f.read() != text:
                sys.exit(f"{PLAN_PATH} is stale; run scripts/hive/gen-shards.py")
        print("shard plan is current and exact")
        return
    with open(PLAN_PATH, "w") as f:
        f.write(text)
    for suite, s in plan["suites"].items():
        longest = max(len(x["sim_limit"]) for x in s["shards"])
        print(f"{suite}: {len(s['shards'])} shards, est minutes "
              + ", ".join(f"{x['name'].split('-')[-1]}:{x['est_minutes']}" for x in s["shards"])
              + f" (longest pattern {longest} chars)")


if __name__ == "__main__":
    main()
