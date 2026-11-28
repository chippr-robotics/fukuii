#!/usr/bin/env python3
"""List the PRs of a sprint, route each to the agents that own its area, and
trace fix PRs back to the sprint PR whose lines they changed.

Usage (from the repo root):
  python3 -I .claude/skills/fukuii-sprint-retro/scripts/sprint_prs.py \
      [--from REF] [--to REF] [--json]

--from defaults to the `to_sha` of the last row in .claude/evals/ledger.tsv,
else to the staging commit that bumped version.sbt to the version on
origin/main (the last released version). --to defaults to origin/staging.
"""
import argparse, json, os, re, subprocess, sys
from collections import Counter

PKG = "src/main/scala/com/chipprbots/ethereum/"
# path prefix (under PKG unless it starts with '/') -> owning agents
ROUTES = [
    ("jsonrpc/", ["conduit"]),
    ("db/", ["vault"]),
    ("network/", ["herald"]), ("forkid/", ["herald"]), ("/scalanet", ["herald"]),
    ("blockchain/sync/snap/", ["herald", "vault"]),
    ("vm/", ["CONSENSUS"]), ("crypto/", ["CONSENSUS"]), ("/crypto/", ["CONSENSUS"]),
    ("mpt/", ["CONSENSUS"]), ("/rlp/", ["CONSENSUS"]), ("ledger/", ["CONSENSUS"]),
    ("domain/", ["CONSENSUS"]), ("consensus/", ["CONSENSUS"]),
    ("ommers/", ["forge"]), ("transactions/", ["CONSENSUS"]),
]
ETH_KW = re.compile(r"\b(engine|sepolia|hoodi|ephemery|proof of stake|PoS|payload|blob|osaka|amsterdam|"
                    r"glamsterdam|withdrawal|timestamp|consensus (client|layer)|forkchoice|EIP-\d+)\b", re.I)
ETC_KW = re.compile(r"\b(ETC|mordor|ethash|ECIP-?\d*|olympia|proof-of-work|proof of work|PoW|ommers?|"
                    r"min(er|ing)|core-geth)\b", re.I)


def git(*a, check=True):
    r = subprocess.run(["git", *a], capture_output=True, text=True)
    if check and r.returncode:
        sys.exit(f"git {' '.join(a)}: {r.stderr.strip()}")
    return r.stdout


def default_from():
    led = ".claude/evals/ledger.tsv"
    if os.path.exists(led):
        rows = [l.split("\t") for l in open(led) if l.strip() and not l.startswith("#")]
        if rows and len(rows[-1]) > 3:
            return rows[-1][3]  # to_sha
    ver = re.search(r'"([0-9.]+)"', git("show", "origin/main:version.sbt")).group(1)
    sha = git("log", "--format=%H", "-1", "origin/staging",
              f"--grep=bump version to {re.escape(ver)}$").strip()
    if not sha:
        sys.exit(f"no staging commit bumps the version to {ver}; pass --from")
    return sha


def route(files, msg):
    agents = set()
    chain = "n/a"
    eth, etc = bool(ETH_KW.search(msg)), bool(ETC_KW.search(msg))
    for f in files:
        rel = f[len(PKG):] if f.startswith(PKG) else "/" + f
        for pre, owners in ROUTES:
            if rel.startswith(pre) or (pre.startswith("/") and ("/" + f).startswith(pre)):
                agents.update(owners)
    if "CONSENSUS" in agents:
        agents.discard("CONSENSUS")
        chain = "both" if eth == etc else ("ETH" if eth else "ETC")
        agents.update({"ETH": ["beacon"], "ETC": ["forge"], "both": ["forge", "beacon"]}[chain])
    code = [f for f in files if f.endswith(".scala")]
    main = [f for f in code if "/src/main/" in "/" + f]
    if code:
        agents.add("eye")
    if main and not agents & {"forge", "beacon"}:
        agents.add("prism")
    if any(f.endswith((".sbt",)) or f.startswith("project/") for f in files):
        agents.add("wraith")
    return sorted(agents), chain


COMMENT = re.compile(r"^\s*(//|/\*|\*|$)")


def lineage(sha, parent, in_range):
    """Sprint commits whose src/main code lines this commit deletes or rewrites.

    Blame runs with -w -M -C, so a line an earlier commit only re-indented,
    moved or copied is credited to the commit that wrote it; comment and blank
    lines are skipped, so a TODO scaladoc a later PR fulfils does not count.
    Even so, a pair is a candidate, not a defect: read both commits.
    """
    origins = Counter()
    diff = git("diff", "-U0", parent, sha, "--", "src/main", "*/src/main")
    cur, old_ln = None, 0
    targets = {}
    for line in diff.splitlines():
        if line.startswith("--- "):
            cur = line[6:] if line.startswith("--- a/") else None
        elif line.startswith("+++ "):
            continue
        elif line.startswith("@@"):
            old_ln = int(re.match(r"@@ -(\d+)", line).group(1))
        elif line.startswith("-") and cur:
            if not COMMENT.match(line[1:]):
                targets.setdefault(cur, []).append(old_ln)
            old_ln += 1
    for f, lines in targets.items():
        for ln in lines:
            out = git("blame", "-w", "-M", "-C", "--porcelain", "-L", f"{ln},{ln}", parent, "--", f, check=False)
            b = re.match(r"^([0-9a-f]{40}) ", out)
            if b and b.group(1) in in_range:
                origins[in_range[b.group(1)]] += 1
    return dict(origins)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="frm")
    ap.add_argument("--to", default="origin/staging")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--no-lineage", action="store_true")
    a = ap.parse_args()
    frm = a.frm or default_from()
    log = git("log", "--reverse", "--format=%H%x1f%P%x1f%s%x1f%b%x1e", f"{frm}..{a.to}")
    rows, in_range = [], {}
    for rec in log.split("\x1e"):
        if not rec.strip():
            continue
        sha, parents, subj, body = rec.strip("\n").split("\x1f")
        if subj.startswith("chore: bump version"):
            continue
        m = re.search(r"\(#(\d+)\)\s*$", subj) or re.search(r"Merge pull request #(\d+)", subj)
        pr = int(m.group(1)) if m else None
        kind = (re.match(r"(\w+)(\(|!|:)", subj) or [None, "other"])[1]
        parent = parents.split()[0]
        stat = git("diff", "--numstat", parent, sha)
        files, ins, dels = [], 0, 0
        for l in stat.splitlines():
            i, d, f = l.split("\t", 2)
            files.append(f)
            ins += int(i) if i.isdigit() else 0
            dels += int(d) if d.isdigit() else 0
        agents, chain = route(files, subj + "\n" + body)
        rows.append(dict(pr=pr, sha=sha, parent=parent, kind=kind, subject=subj,
                         specs=sorted(set(re.findall(r"spec (\d{3})", subj + body))),
                         backlog=sorted(set(re.findall(r"\bB-\d+\b", subj + body))),
                         files=len(files), ins=ins, dels=dels,
                         tests=sum(1 for f in files if "/src/test/" in "/" + f or "/src/it/" in "/" + f),
                         agents=agents, chain=chain, fixes_lines_of={}))
        in_range[sha] = pr or sha[:9]
    if not a.no_lineage:
        for r in rows:
            if r["kind"] == "fix":
                r["fixes_lines_of"] = {k: v for k, v in lineage(r["sha"], r["parent"], in_range).items()
                                       if k != (r["pr"] or r["sha"][:9])}
    if a.json:
        json.dump(dict(frm=git("rev-parse", frm).strip(), to=git("rev-parse", a.to).strip(), prs=rows),
                  sys.stdout, indent=1)
        return
    print(f"# sprint {frm[:9]}..{a.to}  ({len(rows)} PRs)")
    print("pr\tkind\tchain\tagents\tfiles\t+/-\ttests\tfixes_lines_of\tsubject")
    for r in rows:
        fx = ",".join(f"#{k}:{v}" for k, v in r["fixes_lines_of"].items())
        print(f"{r['pr']}\t{r['kind']}\t{r['chain']}\t{','.join(r['agents']) or '-'}\t{r['files']}\t"
              f"+{r['ins']}/-{r['dels']}\t{r['tests']}\t{fx or '-'}\t{r['subject'][:100]}")
    roster = Counter(a for r in rows for a in r["agents"])
    unrouted = sum(1 for r in rows if not set(r["agents"]) - {"eye", "prism"} and r["kind"] in ("feat", "fix"))
    print(f"\n# agent load: {dict(roster.most_common())}")
    print(f"# feat/fix PRs with no specialist owner (roster-gap signal): {unrouted}")


if __name__ == "__main__":
    main()
