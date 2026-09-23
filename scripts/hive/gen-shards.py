#!/usr/bin/env python3
"""Generate the hive shard plan for the suites that cannot finish inside one CI job.

WHY. consume-engine collects 42,234 tests, consume-rlp 47,589, and consensus roughly 50,000,
and every test starts a fresh client container (~8 s each, measured). At --sim.parallelism 4 a
complete pass is ~23 h per consume suite, while a GitHub-hosted job is capped at 6 h. The only
way to run them to completion is to split each suite across jobs. This script produces that
split, and PROVES it: every known test must be selected by exactly one shard, or it exits 1.

HOW. Each suite's test keys are placed in a prefix trie. A trie node is split only while it
holds more tests than one shard should run; every split node gets a catch-all branch -- "this
prefix followed by any character none of my children claim, or by nothing" -- owned by exactly
one shard. The resulting patterns are therefore an exact partition of ALL possible keys, not
just the ones seen when the plan was generated: a test added upstream still lands in exactly one
shard. The leaves are then bin-packed into shards.

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
  paths, case-folded; patterns are `^<prefix>` and `^<prefix>(?:$|[^children])`.

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
# Measured on hive at parallelism 4 (13c1e5686): consume-engine 2,452 tests / 79.8 min,
# consume-rlp 2,832 / 79.7 min, consensus 1,582 / 60 min. Targets keep each shard near 3 h, well
# inside the 300 min simulator limit and the 360 min job cap.
TARGET = {"consume-engine": 5600, "consume-rlp": 6400, "consensus": 4800}
# consensus files hold several tests each; measured 3.30 (consensus) and 3.48 (legacy) per file.
TESTS_PER_FILE = {"consensus": 3.30, "legacy": 3.48, "legacy-cancun": 3.48}

FORMAT = {"consume-engine": "blockchain_test_engine", "consume-rlp": "blockchain_test"}
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PLAN_PATH = os.path.join(ROOT, ".github", "hive-shards.json")


# ---- generic trie partition -----------------------------------------------------------------
def trie_units(counts, target):
    """Split `counts` (key -> weight) into units that each weigh <= target.

    Returns a list of (kind, prefix, children, weight):
      ("all", P, None, w)          every key starting with P
      ("rest", P, children, w)     P itself, or P followed by a char not in `children`
    Together the units partition every possible key, seen or not.
    """
    units = []

    def split(prefix, keys):
        total = sum(counts[k] for k in keys)
        if total <= target:
            units.append(("all", prefix, None, total))
            return
        by_next = collections.defaultdict(list)
        exact = 0
        for k in keys:
            if len(k) == len(prefix):
                exact += counts[k]
            else:
                by_next[k[len(prefix)]].append(k)
        if not by_next:
            sys.exit(f"cannot split key {prefix!r} ({total} > {target})")
        units.append(("rest", prefix, frozenset(by_next), exact))
        for c in sorted(by_next):
            split(prefix + c, by_next[c])

    split("", list(counts))
    return units


def pack(units, target):
    """First-fit-decreasing bin packing of weighted items into bins of <= target."""
    bins = []
    for item in sorted(units, key=lambda u: -u[-1]):
        for b in bins:
            if b["weight"] + item[-1] <= target:
                b["items"].append(item)
                b["weight"] += item[-1]
                break
        else:
            bins.append({"items": [item], "weight": item[-1]})
    return bins


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
    kind, prefix, children, _ = unit
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
        for u in trie_units(per_fork[fork], TARGET[suite]):
            items.append((fork, u, u[-1]))
    bins = pack(items, TARGET[suite])
    shards = []
    for i, b in enumerate(bins):
        alts = [consume_pattern(fork, u) for fork, u, _ in sorted(b["items"], key=lambda x: (x[0], x[1][1]))]
        body = ".*::(?:" + "|".join(alts) + ")"
        if i == len(bins) - 1:
            body = f"(?:{body}|(?!.*\\[fork_(?:{'|'.join(forks)})-))"
        shards.append({"name": f"{suite}-{i + 1:02d}", "sim_limit": body, "est_tests": b["weight"]})
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


def consensus_pattern(unit):
    kind, prefix, children, _ = unit
    if kind == "all":
        return "^" + esc(prefix)
    return "^" + esc(prefix) + "(?:$|" + cls(children, True) + ")"


def consensus_plan(files_by_suite):
    shards = []
    for suite, files in files_by_suite.items():
        ratio = TESTS_PER_FILE[suite]
        counts = collections.Counter()
        for p in files:
            counts[p.lower()] += ratio
        units = trie_units(counts, TARGET["consensus"])
        bins = pack([(u, u[-1]) for u in units], TARGET["consensus"])
        for i, b in enumerate(bins):
            alts = [consensus_pattern(u) for u, _ in sorted(b["items"], key=lambda x: x[0][1])]
            name = f"consensus-{suite}" + (f"-{i + 1:02d}" if len(bins) > 1 else "")
            shards.append({"name": name, "sim_limit": f"^{suite}$/" + "|".join(alts), "est_tests": round(b["weight"])})
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
    files = {s: list_files(repo, commit, root, cache_dir) for s, (repo, commit, root) in CONSENSUS_SUITES.items()}
    shards = consensus_plan(files)
    bad += verify_consensus(shards, files)
    plan["suites"]["consensus"] = {"expected_files": {s: len(f) for s, f in files.items()}, "shards": shards}
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
        print(f"{suite}: {len(s['shards'])} shards, est " + ", ".join(str(x["est_tests"]) for x in s["shards"])
              + f" (longest pattern {longest} chars)")


if __name__ == "__main__":
    main()
