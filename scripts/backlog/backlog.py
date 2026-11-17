#!/usr/bin/env python3
"""fukuii's shared backlog: list, claim and update the items in `backlog.yaml` on the `tracker` branch, and render
the Tracker page of the documentation site (Development -> Tracker).

The backlog has its own branch, `tracker`, so a claim needs no PR and no CI. A write is compare-and-swap through
`git push`: fetch the branch, check the item, commit, push. A rejected push means someone else moved first, so the
tool fetches again and re-checks, at most five times. It never force-pushes.

It never touches your checkout. It reads the branch with `git cat-file` and builds each commit with plumbing
(`hash-object`, `mktree`, `commit-tree`), so your working tree, index, HEAD and branches stay as they are. Only the
remote-tracking ref `<remote>/tracker` moves.

Protocol: .claude/agent-protocols/backlog-tracker.md. Constitution: Principle VIII.

Usage, from anywhere inside a fukuii clone (set FUKUII_NODE once per host, e.g. fukuii-fc or dev-node):

    backlog.py list [--status S] [--priority P] [--mine] [--all]
    backlog.py show B-012
    backlog.py add --title T --priority P2 --area sync [--size M] [--ref H4] [--issue 1683] [--note ...]
    backlog.py claim B-012 --agent forge [--branch fix/x] [--force]
    backlog.py heartbeat [B-012 ...]               (no id: every item you hold)
    backlog.py status B-012 in-progress
    backlog.py status B-012 review --pr 1750
    backlog.py status B-012 blocked --note "waits for #1747"
    backlog.py release B-012 [--reason "..."]
    backlog.py done B-012 [--pr 1750]
    backlog.py drop B-012 --reason "..."
    backlog.py render --out DIR
    backlog.py check [--file backlog.yaml]

Exit codes: 0 done; 1 error (git, network, an invalid backlog); 2 usage; 3 refused (another holder, not the holder,
or a transition the rules forbid). A refused claim prints who holds the item and since when.

Standard library plus PyYAML. Safe under `python3 -I`. Deterministic render (pass --now to pin the clock).
"""
from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import math
import os
import random
import re
import subprocess
import sys
import time
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - depends on the host
    sys.stderr.write("backlog.py needs PyYAML: install python3-yaml (apt) or run `pip install pyyaml`\n")
    sys.exit(1)

SCHEMA_VERSION = 1
FILE = "backlog.yaml"
REPO_URL = "https://github.com/chippr-robotics/fukuii"
PROTOCOL = ".claude/agent-protocols/backlog-tracker.md"
MAX_TRIES = 5

STATUSES = ("open", "claimed", "in-progress", "review", "blocked", "done", "dropped")
HELD = ("claimed", "in-progress", "review")  # these need a claim
TERMINAL = ("done", "dropped")  # these carry `closed` and no claim
SET_STATUSES = ("claimed", "in-progress", "review", "blocked")  # what `status` may set
PRIORITIES = ("P0", "P1", "P2", "P3")
SIZES = ("S", "M", "L")
ITEM_KEYS = ("id", "title", "priority", "status", "area", "size", "opened", "closed", "refs", "links", "depends_on",
             "claim", "notes")
REQUIRED = ("id", "title", "priority", "status", "area", "opened", "refs", "links")
LINK_KEYS = ("issues", "prs", "spec", "branch")
CLAIM_KEYS = ("by", "agent", "since", "heartbeat", "branch")
RECENT_DAYS = 14
CHART_DAYS = 366

ID_RE = re.compile(r"^B-(\d{3,})$")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
STAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
ADVISORY_RE = re.compile(r"GHSA-[0-9a-z]{4}-", re.I)
# Push failures that mean "the branch moved under you": fetch, re-check and try again.
RETRYABLE = ("[rejected]", "fetch first", "non-fast-forward", "stale info", "cannot lock ref", "failed to lock",
             "failed to update ref", "incorrect old value")

HEADER = (
    "# fukuii shared backlog (schema version 1). Rendered at https://docs.fukuii.com/development/tracker/\n"
    "# Change it with scripts/backlog/backlog.py on staging (claim, status, done, ...); README.md has the protocol.\n"
    "# Hand edits in the GitHub web UI are fine: keep the schema (backlog.py check), never delete or reuse an id\n"
    "# (drop the item instead). Comments other than this header are not kept when the tool rewrites the file.\n"
)


class BacklogError(Exception):
    """Exit 1: git, network or an invalid backlog."""


class Refused(Exception):
    """Exit 3: the rules forbid this change (another holder, not the holder, a bad transition)."""


# ---------------------------------------------------------------------------------------------------------- time


def parse_stamp(s: str) -> dt.datetime:
    if not isinstance(s, str) or not STAMP_RE.match(s):
        raise BacklogError(f"not a UTC timestamp of the form YYYY-MM-DDTHH:MM:SSZ: {s!r}")
    return dt.datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=dt.timezone.utc)


def now_utc() -> dt.datetime:
    """The clock. FUKUII_BACKLOG_NOW pins it (tests, reproducible renders)."""
    fixed = os.environ.get("FUKUII_BACKLOG_NOW")
    if fixed:
        return parse_stamp(fixed)
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def stamp(t: dt.datetime) -> str:
    return t.strftime("%Y-%m-%dT%H:%M:%SZ")


def fmt_age(td: dt.timedelta) -> str:
    mins = max(0, int(td.total_seconds() // 60))
    if mins < 60:
        return f"{mins} min"
    hours = mins // 60
    if hours < 48:
        return f"{hours} h"
    return f"{hours // 24} d {hours % 24} h"


def claim_state(claim: dict, expiry_hours: int, now: dt.datetime) -> tuple[bool, dt.timedelta]:
    """(expired, age since the last heartbeat). A claim expires when no heartbeat came within expiry_hours."""
    age = now - parse_stamp(claim["heartbeat"])
    return age > dt.timedelta(hours=expiry_hours), age


def describe_claim(claim: dict, expiry_hours: int, now: dt.datetime) -> str:
    expired, age = claim_state(claim, expiry_hours, now)
    s = (f"held by {claim['by']} ({claim['agent']}) since {claim['since']}, "
         f"last heartbeat {claim['heartbeat']} ({fmt_age(age)} ago)")
    if expired:
        return s + f"; the claim EXPIRED (no heartbeat within {expiry_hours} h)"
    return s + f"; it expires in {fmt_age(dt.timedelta(hours=expiry_hours) - age)}"


# ---------------------------------------------------------------------------------------------------------- YAML


class _Loader(yaml.SafeLoader):
    """SafeLoader that keeps dates and timestamps as strings and rejects duplicate keys."""

    def construct_mapping(self, node, deep=False):
        seen = set()
        for key_node, _ in node.value:
            key = self.construct_object(key_node, deep=True)
            try:
                dup = key in seen
            except TypeError:  # an unhashable key: SafeConstructor reports it
                continue
            if dup:
                raise yaml.constructor.ConstructorError(None, None, f"duplicate key {key!r}", key_node.start_mark)
            seen.add(key)
        return super().construct_mapping(node, deep=deep)


_Loader.yaml_implicit_resolvers = {
    ch: [r for r in rs if r[0] != "tag:yaml.org,2002:timestamp"]
    for ch, rs in yaml.SafeLoader.yaml_implicit_resolvers.items()
}


def parse(text: str, where: str = FILE) -> dict:
    try:
        doc = yaml.load(text, Loader=_Loader)
    except yaml.YAMLError as e:
        raise BacklogError(f"{where}: not valid YAML: {e}") from None
    if not isinstance(doc, dict):
        raise BacklogError(f"{where}: the top level must be a mapping (version, expiry_hours, items)")
    return doc


def _plain_ok(s: str, flow: bool) -> bool:
    if not s or s != s.strip() or any(c in s for c in "\n\r\t"):
        return False
    probe = f"[{s}]" if flow else f"k: {s}"
    try:
        back = yaml.load(probe, Loader=_Loader)
    except yaml.YAMLError:
        return False
    return back == ([s] if flow else {"k": s})


def _scalar(v, flow: bool = False) -> str:
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    if v is None:
        return "null"
    s = str(v)
    return s if _plain_ok(s, flow) else json.dumps(s, ensure_ascii=False)


def _flow_list(xs) -> str:
    return "[" + ", ".join(_scalar(x, flow=True) for x in xs) + "]"


def _text_field(key: str, s, pad: str) -> list[str]:
    """A string field; multi-line text becomes a literal block when that round-trips exactly."""
    if isinstance(s, str) and "\n" in s:
        lines = s.split("\n")
        if all(l and l == l.rstrip() and "\t" not in l and "\r" not in l for l in lines) and not lines[0].startswith(" "):
            block = [f"{pad}{key}: |-"] + [f"{pad}  {l}" for l in lines]
            try:
                if yaml.load("\n".join(x[len(pad):] for x in block), Loader=_Loader) == {key: s}:
                    return block
            except yaml.YAMLError:
                pass
    return [f"{pad}{key}: {_scalar(s)}"]


def _value_lines(key: str, v, pad: str) -> list[str]:
    if isinstance(v, list):
        return [f"{pad}{key}: {_flow_list(v)}"]
    if isinstance(v, dict):
        if not v:
            return [f"{pad}{key}: {{}}"]
        order = LINK_KEYS if key == "links" else CLAIM_KEYS if key == "claim" else ()
        keys = [k for k in order if k in v] + sorted(k for k in v if k not in order)
        out = [f"{pad}{key}:"]
        for k in keys:
            out += _value_lines(k, v[k], pad + "  ")
        return out
    return _text_field(key, v, pad)


def _item_lines(it: dict) -> list[str]:
    keys = [k for k in ITEM_KEYS if k in it] + sorted(k for k in it if k not in ITEM_KEYS)
    out = []
    for k in keys:
        out += _value_lines(k, it[k], "    ")
    if out:
        out[0] = "  - " + out[0][4:]
    return out


def dump(doc: dict) -> str:
    """The canonical text of a backlog. Fails loudly if it would not read back as the same document."""
    out = [HEADER.rstrip("\n"), f"version: {_scalar(doc.get('version'))}",
           f"expiry_hours: {_scalar(doc.get('expiry_hours'))}"]
    items = doc.get("items") or []
    out.append("items:" if items else "items: []")
    for it in items:
        out += _item_lines(it)
    text = "\n".join(out) + "\n"
    if parse(text) != {"version": doc.get("version"), "expiry_hours": doc.get("expiry_hours"), "items": items}:
        raise BacklogError("internal error: the YAML writer does not round-trip this backlog; nothing was written")
    return text


# ---------------------------------------------------------------------------------------------------- validation


def _text(v) -> bool:
    return isinstance(v, str) and v.strip() != ""


def _date_ok(v) -> bool:
    if not (isinstance(v, str) and DATE_RE.match(v)):
        return False
    try:
        dt.date.fromisoformat(v)
        return True
    except ValueError:
        return False


def _strings(v):
    if isinstance(v, str):
        yield v
    elif isinstance(v, dict):
        for x in v.values():
            yield from _strings(x)
    elif isinstance(v, list):
        for x in v:
            yield from _strings(x)


def check_item(it: dict) -> list[str]:
    m = []
    for k in REQUIRED:
        if k not in it:
            m.append(f"missing required field `{k}`")
    unknown = [k for k in it if k not in ITEM_KEYS]
    if unknown:
        m.append(f"unknown fields {unknown} (allowed: {', '.join(ITEM_KEYS)})")
    if "id" in it and not (isinstance(it["id"], str) and ID_RE.match(it["id"])):
        m.append(f"id must look like B-001, got {it['id']!r}")
    if "title" in it:
        t = it["title"]
        if not (_text(t) and "\n" not in t):
            m.append("title must be a non-empty single-line string")
        elif len(t) > 160:
            m.append(f"title is {len(t)} characters; keep it under 160 (details go in the PR, issue or spec)")
    if "priority" in it and it["priority"] not in PRIORITIES:
        m.append(f"priority must be one of {', '.join(PRIORITIES)}, got {it['priority']!r}")
    st = it.get("status")
    if "status" in it and st not in STATUSES:
        m.append(f"status must be one of {', '.join(STATUSES)}, got {st!r}")
    if "area" in it and not _text(it["area"]):
        m.append("area must be a non-empty string")
    if "size" in it and it["size"] not in SIZES:
        m.append(f"size must be one of {', '.join(SIZES)}, got {it['size']!r}")
    for k in ("opened", "closed"):
        if k in it and not _date_ok(it[k]):
            m.append(f"{k} must be a date YYYY-MM-DD, got {it[k]!r}")
    if "refs" in it and not (isinstance(it["refs"], list) and all(_text(r) for r in it["refs"])):
        m.append("refs must be a list of strings (old ids: H4, 10e, #1654-B, CHASE rows)")
    links = it.get("links")
    if "links" in it:
        if not isinstance(links, dict):
            m.append("links must be a mapping (issues, prs, spec, branch)")
        else:
            bad = [k for k in links if k not in LINK_KEYS]
            if bad:
                m.append(f"links: unknown keys {bad} (allowed: {', '.join(LINK_KEYS)})")
            for k in ("issues", "prs"):
                v = links.get(k, [])
                if not (isinstance(v, list) and all(isinstance(n, int) and not isinstance(n, bool) and n > 0 for n in v)):
                    m.append(f"links.{k} must be a list of issue or PR numbers")
            for k in ("spec", "branch"):
                if k in links and not _text(links[k]):
                    m.append(f"links.{k} must be a non-empty string")
    if "depends_on" in it:
        d = it["depends_on"]
        if not (isinstance(d, list) and all(isinstance(x, str) and ID_RE.match(x) for x in d)):
            m.append("depends_on must be a list of item ids")
        elif it.get("id") in d:
            m.append("depends_on names the item itself")
    if "notes" in it:
        n = it["notes"]
        if not _text(n):
            m.append("notes must be a non-empty string")
        elif len(n.split("\n")) > 3:
            m.append("notes has more than 3 lines; details go in the PR, issue or spec")
    claim = it.get("claim")
    if st in HELD and "claim" not in it:
        m.append(f"status {st} needs a claim (by, agent, since, heartbeat)")
    if st in TERMINAL + ("open",) and "claim" in it:
        m.append(f"status {st} must not carry a claim")
    if "claim" in it:
        if not isinstance(claim, dict):
            m.append("claim must be a mapping (by, agent, since, heartbeat, branch)")
        else:
            bad = [k for k in claim if k not in CLAIM_KEYS]
            if bad:
                m.append(f"claim: unknown keys {bad} (allowed: {', '.join(CLAIM_KEYS)})")
            for k in ("by", "agent"):
                if not (isinstance(claim.get(k), str) and NAME_RE.match(claim[k])):
                    m.append(f"claim.{k} must be a name of letters, digits, '.', '_' or '-', got {claim.get(k)!r}")
            stamps_ok = True
            for k in ("since", "heartbeat"):
                if not (isinstance(claim.get(k), str) and STAMP_RE.match(claim[k])):
                    m.append(f"claim.{k} must be a UTC timestamp YYYY-MM-DDTHH:MM:SSZ, got {claim.get(k)!r}")
                    stamps_ok = False
            if stamps_ok and claim["heartbeat"] < claim["since"]:
                m.append("claim.heartbeat is earlier than claim.since")
            if "branch" in claim and not _text(claim["branch"]):
                m.append("claim.branch must be a non-empty string")
    if st in TERMINAL and "closed" not in it:
        m.append(f"a {st} item needs `closed` (the date)")
    if st in STATUSES and st not in TERMINAL and "closed" in it:
        m.append("only a done or dropped item has `closed`")
    if any(ADVISORY_RE.search(s) for s in _strings(it)):
        m.append("names a security advisory id: keep undisclosed security issues off the tracker (Principle VIII)")
    return m


def validate(doc: dict) -> list[str]:
    errs = []
    if doc.get("version") != SCHEMA_VERSION:
        errs.append(f"version must be {SCHEMA_VERSION}, got {doc.get('version')!r}")
    exp = doc.get("expiry_hours")
    if not (isinstance(exp, int) and not isinstance(exp, bool) and exp > 0):
        errs.append(f"expiry_hours must be a positive integer, got {exp!r}")
    extra = sorted(set(doc) - {"version", "expiry_hours", "items"})
    if extra:
        errs.append(f"unknown top-level keys {extra}")
    items = doc.get("items")
    if not isinstance(items, list):
        errs.append("items must be a list")
        return errs
    ids: dict[str, int] = {}
    for n, it in enumerate(items):
        if not isinstance(it, dict):
            errs.append(f"items[{n}]: must be a mapping")
            continue
        where = it["id"] if isinstance(it.get("id"), str) else f"items[{n}]"
        errs += [f"{where}: {msg}" for msg in check_item(it)]
        if isinstance(it.get("id"), str):
            if it["id"] in ids:
                errs.append(f"{it['id']}: duplicate id (items[{ids[it['id']]}] and items[{n}])")
            else:
                ids[it["id"]] = n
    for it in items:
        if isinstance(it, dict) and isinstance(it.get("depends_on"), list):
            for d in it["depends_on"]:
                if isinstance(d, str) and ID_RE.match(d) and d not in ids:
                    errs.append(f"{it.get('id')}: depends_on names an unknown item {d}")
    return errs


# ----------------------------------------------------------------------------------------------------------- git


class Git:
    def __init__(self, repo: str):
        self.repo = repo
        self.env = dict(os.environ, GIT_TERMINAL_PROMPT="0")

    def proc(self, *args: str, input: str | None = None, env: dict | None = None) -> subprocess.CompletedProcess:
        # bytes in and out: text mode would turn \n into \r\n on Windows
        r = subprocess.run(["git", "-C", self.repo, *args], input=None if input is None else input.encode("utf-8"),
                           capture_output=True, env=env or self.env)
        r.stdout = r.stdout.decode("utf-8", "replace")
        r.stderr = r.stderr.decode("utf-8", "replace")
        return r

    def run(self, *args: str, input: str | None = None, env: dict | None = None) -> str:
        r = self.proc(*args, input=input, env=env)
        if r.returncode != 0:
            raise BacklogError(f"git {' '.join(args[:2])} failed: {(r.stderr or r.stdout).strip()}")
        return r.stdout


class Tracker:
    def __init__(self, repo: str, remote: str, branch: str):
        self.g = Git(repo)
        if self.g.proc("rev-parse", "--git-dir").returncode != 0:
            raise BacklogError(f"{repo} is not inside a git repository (use --repo)")
        self.remote, self.branch = remote, branch
        self.ref = f"refs/remotes/{remote}/{branch}"

    def fetch(self) -> None:
        err = ""
        for attempt in range(3):
            r = self.g.proc("fetch", "--quiet", "--no-tags", self.remote, f"+refs/heads/{self.branch}:{self.ref}")
            if r.returncode == 0:
                return
            err = (r.stderr or r.stdout).strip()
            if "couldn't find remote ref" in err:
                raise BacklogError(f"remote {self.remote} has no `{self.branch}` branch (see {PROTOCOL})")
            if "lock" not in err:
                break
            time.sleep(0.2 * (attempt + 1))  # another process in this clone is fetching
        raise BacklogError(f"git fetch {self.remote} {self.branch} failed: {err}")

    def tip(self) -> str:
        r = self.g.proc("rev-parse", "--verify", "--quiet", f"{self.ref}^{{commit}}")
        if r.returncode != 0:
            raise BacklogError(f"no local copy of {self.ref}: run without --no-fetch")
        return r.stdout.strip()

    def text_at(self, rev: str) -> str:
        return self.g.run("cat-file", "blob", f"{rev}:{FILE}")

    def read(self, fetch: bool) -> tuple[str, dict]:
        if fetch:
            try:
                self.fetch()
            except BacklogError as e:
                if self.g.proc("rev-parse", "--verify", "--quiet", self.ref).returncode != 0:
                    raise
                print(f"warning: {e}; showing the local copy {self.ref}", file=sys.stderr)
        tip = self.tip()
        return tip, parse(self.text_at(tip), f"{self.ref}:{FILE}")

    def _identity_env(self, by: str) -> dict | None:
        """The configured identity if there is one; otherwise a synthetic one naming the node."""
        if self.g.proc("config", "user.email").stdout.strip() or (
                os.environ.get("GIT_AUTHOR_EMAIL") and os.environ.get("GIT_COMMITTER_EMAIL")):
            return None
        name, email = f"backlog ({by})", f"{by}@backlog.invalid"
        return dict(self.g.env, GIT_AUTHOR_NAME=name, GIT_AUTHOR_EMAIL=email, GIT_COMMITTER_NAME=name,
                    GIT_COMMITTER_EMAIL=email)

    def commit(self, base: str, text: str, message: str, by: str) -> str:
        blob = self.g.run("hash-object", "-w", "--stdin", input=text).strip()
        entries = [e for e in self.g.run("ls-tree", "-z", base).split("\0") if e]
        tree_in, found = [], False
        for e in entries:
            meta, name = e.split("\t", 1)
            if name == FILE:
                tree_in.append(f"100644 blob {blob}\t{FILE}")
                found = True
            else:
                tree_in.append(e)
        if not found:
            tree_in.append(f"100644 blob {blob}\t{FILE}")
        tree = self.g.run("mktree", "-z", input="\0".join(tree_in) + "\0").strip()
        return self.g.run("commit-tree", tree, "-p", base, "-F", "-", input=message,
                          env=self._identity_env(by)).strip()

    def push(self, commit: str, base: str) -> bool:
        """True if the remote branch moved from base to commit; False if someone else moved it first."""
        r = self.g.proc("push", "--porcelain", self.remote, f"{commit}:refs/heads/{self.branch}")
        out = (r.stdout + "\n" + r.stderr).strip()
        if r.returncode == 0:
            # keep the remote-tracking ref current; if another process in this clone moved it, the next fetch wins
            self.g.proc("update-ref", self.ref, commit, base)
            return True
        if any(p in out for p in RETRYABLE):
            return False
        raise BacklogError(f"git push {self.remote} {self.branch} failed:\n{out}")

    def mutate(self, fn, by: str):
        """Compare-and-swap: fetch, check and change a fresh copy, commit, push; on a lost race start over."""
        for attempt in range(1, MAX_TRIES + 1):
            self.fetch()
            base = self.tip()
            doc = parse(self.text_at(base), f"{self.remote}/{self.branch}:{FILE}")
            errs = validate(doc)
            if errs:
                raise BacklogError(f"{FILE} on {self.remote}/{self.branch} ({base[:9]}) is invalid; fix it first "
                                   "(backlog.py check):\n  " + "\n  ".join(errs))
            result = fn(doc)
            if result is None:
                return None
            message, summary = result
            errs = validate(doc)
            if errs:
                raise BacklogError("refusing to write an invalid backlog:\n  " + "\n  ".join(errs))
            commit = self.commit(base, dump(doc), message, by)
            if self.push(commit, base):
                return summary
            if attempt < MAX_TRIES:
                time.sleep(min(2.0, 0.25 * 2 ** (attempt - 1)) * random.uniform(0.5, 1.5))
        raise BacklogError(f"gave up after {MAX_TRIES} tries: {self.remote}/{self.branch} kept moving under this write")


# ------------------------------------------------------------------------------------------------------ commands


def norm_id(s: str) -> str:
    m = re.match(r"^(?:[Bb]-)?(\d+)$", s.strip())
    if not m:
        raise BacklogError(f"not an item id: {s!r} (expected B-012)")
    return f"B-{int(m.group(1)):03d}"


def find(doc: dict, iid: str) -> dict:
    for it in doc["items"]:
        if it.get("id") == iid:
            return it
    raise BacklogError(f"no item {iid} in the backlog")


def add_note(it: dict, line: str) -> None:
    lines = it["notes"].split("\n") if it.get("notes") else []
    lines.append(" ".join(line.split()))
    it["notes"] = "\n".join(lines[-3:])


def add_pr(it: dict, pr: int | None) -> None:
    if pr:
        prs = it.setdefault("links", {}).setdefault("prs", [])
        if pr not in prs:
            prs.append(pr)


def need_by(a) -> str:
    if not a.by:
        raise BacklogError("who are you? pass --by NODE or set FUKUII_NODE (e.g. fukuii-fc, dev-node, cloud-<name>)")
    if not NAME_RE.match(a.by):
        raise BacklogError(f"--by must be a name of letters, digits, '.', '_' or '-', got {a.by!r}")
    return a.by


def _guard_other(it: dict, by: str, expiry: int, now: dt.datetime, force: bool | None, verb: str) -> None:
    """Refuse when someone else holds a live claim; an expired one yields only to --force (None: no --force here)."""
    c = it.get("claim")
    if not c or c["by"] == by:
        return
    expired, _ = claim_state(c, expiry, now)
    if expired and force:
        return
    hint = ""
    if expired:
        hint = " It expired: pass --force." if force is not None else " It expired: `claim --force` takes it over."
    raise Refused(f"{it['id']} is {describe_claim(c, expiry, now)}. Only the holder can {verb} it.{hint}")


def cmd_claim(tr: Tracker, a) -> None:
    by, iid = need_by(a), norm_id(a.id)
    agent = a.agent or os.environ.get("FUKUII_AGENT")
    if not agent or not NAME_RE.match(agent):
        raise BacklogError("claim needs --agent WHO (forge, beacon, main, a person's handle; or set FUKUII_AGENT)")

    def fn(doc):
        it, now, exp = find(doc, iid), now_utc(), doc["expiry_hours"]
        if it["status"] in TERMINAL:
            raise Refused(f"{iid} is {it['status']}; add a new item instead of reopening it")
        c = it.get("claim")
        if c and c["by"] == by:
            c["agent"], c["heartbeat"] = agent, stamp(now)
            if a.branch:
                c["branch"] = a.branch
            return f"tracker: claim {iid} by {by} ({agent}), refreshed", f"{iid} is already yours; claim refreshed"
        body = ""
        if c:
            expired, age = claim_state(c, exp, now)
            if not expired:
                raise Refused(f"{iid} is {describe_claim(c, exp, now)}. Pick another item or ask {c['by']} to release it.")
            if not a.force:
                raise Refused(f"{iid} is {describe_claim(c, exp, now)}. Re-run with --force to take it over.")
            body = (f"\n\nTook over an EXPIRED claim by {c['by']} ({c['agent']}): held since {c['since']}, last "
                    f"heartbeat {c['heartbeat']} ({fmt_age(age)} before this claim; expiry {exp} h).")
        it["claim"] = {"by": by, "agent": agent, "since": stamp(now), "heartbeat": stamp(now)}
        if a.branch:
            it["claim"]["branch"] = a.branch
        if it["status"] != "blocked":
            it["status"] = "claimed"
        subject = f"tracker: claim {iid} by {by} ({agent})" + (" [took over an expired claim]" if c else "")
        return subject + body, f"claimed {iid}: {it['title']}"

    print(tr.mutate(fn, by))


def cmd_heartbeat(tr: Tracker, a) -> None:
    by = need_by(a)
    ids = [norm_id(x) for x in a.ids]

    def fn(doc):
        now = now_utc()
        targets = [find(doc, i) for i in ids] if ids else [
            it for it in doc["items"] if (it.get("claim") or {}).get("by") == by]
        if not targets:
            return None
        for it in targets:
            c = it.get("claim")
            if not c or c["by"] != by:
                held = f": it is {describe_claim(c, doc['expiry_hours'], now)}" if c else ": it is not claimed"
                raise Refused(f"{it['id']} is not held by {by}{held}")
            c["heartbeat"] = stamp(now)
        names = ", ".join(it["id"] for it in targets)
        return f"tracker: heartbeat {names} by {by}", f"heartbeat {names}"

    print(tr.mutate(fn, by) or f"nothing is held by {by}")


def cmd_status(tr: Tracker, a) -> None:
    by, iid, st = need_by(a), norm_id(a.id), a.status
    if st == "blocked" and not a.note:
        raise Refused("blocked needs the reason: --note TEXT")

    def fn(doc):
        it, now, exp = find(doc, iid), now_utc(), doc["expiry_hours"]
        if it["status"] in TERMINAL:
            raise Refused(f"{iid} is {it['status']}")
        _guard_other(it, by, exp, now, None, "change the status of")
        c = it.get("claim")
        if not c and st != "blocked":
            raise Refused(f"{iid} is not claimed: claim it first (backlog.py claim {iid} --agent WHO)")
        if st == "review" and not (a.pr or (it.get("links") or {}).get("prs")):
            raise Refused("review needs the PR: --pr N")
        it["status"] = st
        if c:
            c["heartbeat"] = stamp(now)
        add_pr(it, a.pr)
        if a.note:
            add_note(it, f"{stamp(now)[:10]} {st}: {a.note}")
        pr = f" (#{a.pr})" if a.pr else ""
        return (f"tracker: {iid} {st}{pr} by {by}" + (f"\n\n{a.note}" if a.note else ""),
                f"{iid} is now {st}")

    print(tr.mutate(fn, by))


def cmd_release(tr: Tracker, a) -> None:
    by, iid = need_by(a), norm_id(a.id)

    def fn(doc):
        it, now, exp = find(doc, iid), now_utc(), doc["expiry_hours"]
        c = it.get("claim")
        if not c:
            raise Refused(f"{iid} is not claimed")
        _guard_other(it, by, exp, now, a.force, "release")
        del it["claim"]
        if it["status"] != "blocked":
            it["status"] = "open"
        who = by if c["by"] == by else f"{by} (expired claim of {c['by']})"
        if a.reason:
            add_note(it, f"{stamp(now)[:10]} released by {who}: {a.reason}")
        return (f"tracker: release {iid} by {who}" + (f"\n\n{a.reason}" if a.reason else ""),
                f"released {iid}")

    print(tr.mutate(fn, by))


def _close(tr: Tracker, a, status: str, reason: str | None) -> None:
    by, iid = need_by(a), norm_id(a.id)

    def fn(doc):
        it, now, exp = find(doc, iid), now_utc(), doc["expiry_hours"]
        if it["status"] in TERMINAL:
            raise Refused(f"{iid} is already {it['status']}")
        _guard_other(it, by, exp, now, a.force, "close")
        it.pop("claim", None)
        it["status"], it["closed"] = status, stamp(now)[:10]
        add_pr(it, getattr(a, "pr", None))
        if reason:
            add_note(it, f"{stamp(now)[:10]} {status}: {reason}")
        pr = f" (#{a.pr})" if getattr(a, "pr", None) else ""
        return (f"tracker: {status} {iid}{pr} by {by}" + (f"\n\n{reason}" if reason else ""), f"{iid} is {status}")

    print(tr.mutate(fn, by))


def cmd_done(tr: Tracker, a) -> None:
    _close(tr, a, "done", a.note)


def cmd_drop(tr: Tracker, a) -> None:
    _close(tr, a, "dropped", a.reason)


def cmd_add(tr: Tracker, a) -> None:
    by = need_by(a)
    if a.opened and not _date_ok(a.opened):
        raise BacklogError(f"--opened must be a date YYYY-MM-DD, got {a.opened!r}")

    def fn(doc):
        nums = [int(ID_RE.match(i["id"]).group(1)) for i in doc["items"]]
        iid = f"B-{max(nums, default=0) + 1:03d}"
        it = {"id": iid, "title": a.title, "priority": a.priority, "status": "open", "area": a.area}
        if a.size:
            it["size"] = a.size
        it["opened"] = a.opened or stamp(now_utc())[:10]
        it["refs"] = a.ref or []
        links = {}
        if a.issue:
            links["issues"] = a.issue
        if a.pr:
            links["prs"] = a.pr
        if a.spec:
            links["spec"] = a.spec
        if a.branch:
            links["branch"] = a.branch
        it["links"] = links
        if a.depends_on:
            it["depends_on"] = [norm_id(x) for x in a.depends_on]
        if a.note:
            it["notes"] = a.note
        doc["items"].append(it)
        return f"tracker: add {iid} ({a.priority}, {a.area}) by {by}\n\n{a.title}", iid

    print(tr.mutate(fn, by))


def _holder(it: dict, exp: int, now: dt.datetime) -> str:
    c = it.get("claim")
    if not c:
        return "-"
    return f"{c['by']}/{c['agent']}" + (" STALE" if claim_state(c, exp, now)[0] else "")


def cmd_list(tr: Tracker, a) -> None:
    _, doc = tr.read(not a.no_fetch)
    now, exp = now_utc(), doc["expiry_hours"]
    rows = doc["items"]
    if a.status:
        rows = [i for i in rows if i.get("status") in a.status]
    elif not a.all:
        rows = [i for i in rows if i.get("status") not in TERMINAL]
    if a.priority:
        rows = [i for i in rows if i.get("priority") in a.priority]
    if a.mine:
        by = need_by(a)
        rows = [i for i in rows if (i.get("claim") or {}).get("by") == by]
    rows = sorted(rows, key=lambda i: (str(i.get("priority")), str(i.get("id"))))
    for it in rows:
        title = it.get("title", "")
        title = title if len(title) <= 72 else title[:71] + "…"
        print(f"{it.get('id', '?'):6} {it.get('priority', '?'):3} {it.get('status', '?'):12} "
              f"{str(it.get('area', ''))[:12]:12} {_holder(it, exp, now)[:24]:24} {title}")
    print(f"({len(rows)} items)", file=sys.stderr)


def cmd_show(tr: Tracker, a) -> None:
    _, doc = tr.read(not a.no_fetch)
    it = find(doc, norm_id(a.id))
    print("\n".join(l[4:] for l in _item_lines(it)))
    if it.get("claim"):
        print(f"# {describe_claim(it['claim'], doc['expiry_hours'], now_utc())}")


def cmd_check(tr: Tracker | None, a) -> None:
    if a.file:
        raw = Path(a.file).read_bytes()
        if b"\r" in raw:
            raise BacklogError(f"{a.file}: has CR characters; use LF line endings")
        doc = parse(raw.decode("utf-8"), a.file)
        where = a.file
    else:
        tip, doc = tr.read(not a.no_fetch)
        where = f"{tr.remote}/{tr.branch} ({tip[:9]})"
    errs = validate(doc)
    for e in errs:
        print(f"ERROR {e}", file=sys.stderr)
    if errs:
        raise BacklogError(f"{where}: {len(errs)} problem(s)")
    c = counts(doc)
    by_status = ", ".join(f"{s} {sum(i['status'] == s for i in doc['items'])}" for s in STATUSES)
    print(f"OK {where}: {len(doc['items'])} items ({by_status}); {c['open']} not done")


# -------------------------------------------------------------------------------------------------------- render


def counts(doc: dict) -> dict:
    items = [i for i in doc.get("items") or [] if isinstance(i, dict)]
    live = [i for i in items if i.get("status") not in TERMINAL]
    return {
        "open": len(live),
        "hi": sum(i.get("priority") in ("P0", "P1") for i in live),
        "lo": sum(i.get("priority") in ("P2", "P3") for i in live),
        "scope": sum(i.get("status") != "dropped" for i in items),
        "done": sum(i.get("status") == "done" for i in items),
    }


def history(tr: Tracker, tip: str) -> dict[str, dict]:
    """Counts at each UTC day's last commit of the tracker branch. A commit whose file does not parse is skipped."""
    last: dict[str, str] = {}
    for line in tr.g.run("log", "--first-parent", "--format=%H %ct", tip).splitlines():
        sha, ct = line.split()
        last.setdefault(dt.datetime.fromtimestamp(int(ct), dt.timezone.utc).strftime("%Y-%m-%d"), sha)  # newest first
    out = {}
    for d in sorted(last):
        try:
            out[d] = counts(parse(tr.text_at(last[d])))
        except BacklogError:
            continue
    return out


def daily(snaps: dict[str, dict], today: str) -> list[tuple[str, dict]]:
    """One row per day from the first snapshot to today; a day with no commit repeats the day before."""
    if not snaps:
        return []
    keys = sorted(snaps)
    d, end = dt.date.fromisoformat(keys[0]), max(dt.date.fromisoformat(keys[-1]), dt.date.fromisoformat(today))
    rows, cur = [], None
    while d <= end:
        cur = snaps.get(d.isoformat(), cur)
        rows.append((d.isoformat(), cur))
        d += dt.timedelta(days=1)
    return rows[-CHART_DAYS:]


MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
SVG_W, PANEL_H, PAD_L, PAD_R, PAD_T, PAD_B = 720, 230, 48, 120, 52, 30
SVG_STYLE = (
    ".bt{--bt-ink:var(--md-default-fg-color--light,#52514e);--bt-muted:var(--md-default-fg-color--lighter,#898781);"
    "--bt-grid:var(--md-default-fg-color--lightest,#e1e0d9);--bt-surface:var(--md-default-bg-color,#fcfcfb);"
    "--bt-1:#2a78d6;--bt-2:#eb6834;--bt-3:#1baf7a}"
    "[data-md-color-scheme=slate] .bt{--bt-1:#3987e5;--bt-2:#d95926;--bt-3:#199e70}"
    "@media (prefers-color-scheme:dark){svg.bt:root{--bt-ink:#c3c2b7;--bt-muted:#898781;--bt-grid:#2c2c2a;"
    "--bt-surface:#1a1a19;--bt-1:#3987e5;--bt-2:#d95926;--bt-3:#199e70}}"
    ".bt .bg{fill:none}svg.bt:root .bg{fill:var(--bt-surface)}"
    ".bt text{font:12px system-ui,-apple-system,'Segoe UI',sans-serif;fill:var(--bt-ink)}"
    ".bt .t{font-size:14px;font-weight:600}.bt .ax{fill:var(--bt-muted);font-variant-numeric:tabular-nums}"
    ".bt .grid{stroke:var(--bt-grid);stroke-width:1}.bt .base{stroke:var(--bt-muted);stroke-width:1}"
    ".bt .ln{fill:none;stroke-width:2;stroke-linejoin:round;stroke-linecap:round}"
    ".bt .s0{stroke:var(--bt-ink)}.bt .s1{stroke:var(--bt-1)}.bt .s2{stroke:var(--bt-2)}.bt .s3{stroke:var(--bt-3)}"
    ".bt .f0{fill:var(--bt-ink)}.bt .f1{fill:var(--bt-1)}.bt .f2{fill:var(--bt-2)}.bt .f3{fill:var(--bt-3)}"
    ".bt .dash{stroke-dasharray:6 4}.bt .dot{stroke:var(--bt-surface);stroke-width:2}"
    ".bt .hit{fill:transparent}.bt .hit:hover{fill:var(--bt-grid);fill-opacity:.45}"
)


def _scale(vmax: int) -> tuple[int, int]:
    v = max(vmax, 1)
    for step in (1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000):
        if math.ceil(v / step) <= 5:
            return step, math.ceil(v / step) * step
    step = 10 ** math.ceil(math.log10(v / 5))
    return step, math.ceil(v / step) * step


def _f(x: float) -> str:
    return f"{x:.1f}".rstrip("0").rstrip(".")


def _panel(y0: int, title: str, rows, series, tip) -> list[str]:
    """One chart panel. series: (key, label, n, dashed); n picks the colour class (0 = neutral ink)."""
    x0, x1, top, bot = PAD_L, SVG_W - PAD_R, y0 + PAD_T, y0 + PANEL_H - PAD_B
    n = len(rows)
    step, ymax = _scale(max(r[k] for _, r in rows for k, *_ in series))
    X = (lambda i: x0 + (x1 - x0) * i / (n - 1)) if n > 1 else (lambda i: (x0 + x1) / 2)
    Y = lambda v: bot - (bot - top) * v / ymax  # noqa: E731
    out = [f'<text class="t" x="{x0}" y="{y0 + 18}">{html.escape(title)}</text>']
    lx = x0
    for k, label, c, dashed in series:  # legend: always present for two or more series
        out.append(f'<line class="ln s{c}{" dash" if dashed else ""}" x1="{lx}" y1="{y0 + 34}" x2="{lx + 18}" y2="{y0 + 34}"/>')
        out.append(f'<text x="{lx + 24}" y="{y0 + 38}">{html.escape(label)}</text>')
        lx += 24 + 7 * len(label) + 22
    for t in range(0, ymax + 1, step):
        cls = "base" if t == 0 else "grid"
        out.append(f'<line class="{cls}" x1="{x0}" y1="{_f(Y(t))}" x2="{x1}" y2="{_f(Y(t))}"/>')
        out.append(f'<text class="ax" x="{x0 - 8}" y="{_f(Y(t) + 4)}" text-anchor="end">{t}</text>')
    ticks = sorted({round(k * (n - 1) / 5) for k in range(6)}) if n > 6 else range(n)
    for i in ticks:
        d = rows[i][0]
        anchor = "start" if n > 1 and i == 0 else "end" if n > 1 and i == n - 1 else "middle"
        out.append(f'<text class="ax" x="{_f(X(i))}" y="{bot + 18}" text-anchor="{anchor}">'
                   f'{MONTHS[int(d[5:7]) - 1]} {int(d[8:10])}</text>')
    ends = []
    for k, label, c, dashed in series:
        if n > 1:
            pts = " ".join(f"{_f(X(i))},{_f(Y(r[k]))}" for i, (_, r) in enumerate(rows))
            out.append(f'<polyline class="ln s{c}{" dash" if dashed else ""}" points="{pts}"/>')
        last = rows[-1][1][k]
        out.append(f'<circle class="dot f{c}" cx="{_f(X(n - 1))}" cy="{_f(Y(last))}" r="4"/>')
        ends.append([Y(last), f"{label} {last}"])
    ends.sort(key=lambda e: e[0])  # direct labels at the right edge, pushed apart so they never overlap
    for j in range(1, len(ends)):
        ends[j][0] = max(ends[j][0], ends[j - 1][0] + 15)
    for yy, text in ends:  # beside the last point: the right edge, or the middle when there is one day
        out.append(f'<text x="{_f(X(n - 1) + 12)}" y="{_f(yy + 4)}">{html.escape(text)}</text>')
    w = (x1 - x0) / (n - 1) if n > 1 else (x1 - x0)
    for i, (d, r) in enumerate(rows):  # hover layer: one column per day with a native tooltip
        left, right = max(x0, X(i) - w / 2), min(x1, X(i) + w / 2)
        out.append(f'<rect class="hit" x="{_f(left)}" y="{top}" width="{_f(right - left)}" height="{bot - top}">'
                   f'<title>{html.escape(tip(d, r))}</title></rect>')
    return out


def burndown_svg(rows: list[tuple[str, dict]]) -> str:
    h = 2 * PANEL_H + 10
    if rows:
        latest = rows[-1][1]
        desc = (f"Backlog items not done, per day from {rows[0][0]} to {rows[-1][0]}; latest {latest['open']} not "
                f"done (P0-P1 {latest['hi']}, P2-P3 {latest['lo']}), scope {latest['scope']}, done {latest['done']}.")
    else:
        desc = "No tracker history yet."
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" class="bt" viewBox="0 0 {SVG_W} {h}" width="100%" '
           f'style="max-width:{SVG_W}px" role="img" aria-labelledby="bt-title bt-desc">',
           '<title id="bt-title">Backlog burn-down and burn-up</title>',
           f'<desc id="bt-desc">{html.escape(desc)}</desc>',
           f"<style>{SVG_STYLE}</style>",
           f'<rect class="bg" width="{SVG_W}" height="{h}" rx="6"/>']
    if rows:
        out += _panel(0, "Burn-down: items not done", rows,
                      [("open", "Total", 0, False), ("hi", "P0–P1", 1, False), ("lo", "P2–P3", 2, False)],
                      lambda d, r: f"{d}: {r['open']} not done (P0–P1 {r['hi']}, P2–P3 {r['lo']})")
        out += _panel(PANEL_H + 10, "Burn-up: scope and done", rows,
                      [("scope", "Scope", 0, True), ("done", "Done", 3, False)],
                      lambda d, r: f"{d}: scope {r['scope']} (items not dropped), done {r['done']}")
    else:
        out.append(f'<text x="{PAD_L}" y="40">No tracker history yet.</text>')
    out.append("</svg>")
    return "\n".join(out) + "\n"


_ENT = {"\\": "&#92;", "`": "&#96;", "*": "&#42;", "_": "&#95;", "[": "&#91;", "]": "&#93;", "|": "&#124;",
        "~": "&#126;", "^": "&#94;", "=": "&#61;", "{": "&#123;", "}": "&#125;", ":": "&#58;"}


def _esc(s: str) -> str:
    return "".join(_ENT.get(ch, ch) for ch in html.escape(s, quote=False))


def md(s) -> str:
    """Item text for a Markdown table cell: `code spans` stay code, everything else is literal."""
    s = " ".join(str(s).split())
    parts = s.split("`")
    if len(parts) % 2 == 0:  # an unpaired backtick: all literal
        return _esc(s)
    return "".join(f"`{p}`" if i % 2 else _esc(p) for i, p in enumerate(parts))


def _links(it: dict, repo: str, with_spec: bool = True) -> str:
    links = it.get("links") or {}
    out = [f"[#{n}]({repo}/issues/{n})" for n in links.get("issues", [])]
    out += [f"[PR #{n}]({repo}/pull/{n})" for n in links.get("prs", [])]
    if with_spec and links.get("spec"):
        out.append(f"`{links['spec']}`")
    if it.get("depends_on"):
        out.append("after " + ", ".join(it["depends_on"]))
    return ", ".join(out) or "–"


def _prs(it: dict, repo: str) -> str:
    return ", ".join(f"[#{n}]({repo}/pull/{n})" for n in (it.get("links") or {}).get("prs", [])) or "–"


def _short(ts: str) -> str:
    return ts[:10] + " " + ts[11:16] + "Z"


def render_page(doc: dict, rows, svg: str, now: dt.datetime, source: str, repo: str) -> str:
    items, exp = doc["items"], doc["expiry_hours"]
    today = now.date()
    by_pid = lambda i: (i["priority"], i["id"])  # noqa: E731
    live = [i for i in items if i["status"] not in TERMINAL]
    claimed = sorted((i for i in items if i.get("claim")), key=lambda i: (i["claim"]["since"], i["id"]))
    stale = [i for i in claimed if claim_state(i["claim"], exp, now)[0]]
    recent = sorted((i for i in items if i["status"] == "done"
                     and (today - dt.date.fromisoformat(i["closed"])).days <= RECENT_DAYS),
                    key=lambda i: (i["closed"], i["id"]), reverse=True)
    dropped_recent = sum(1 for i in items if i["status"] == "dropped"
                         and (today - dt.date.fromisoformat(i["closed"])).days <= RECENT_DAYS)
    L = [
        "<!-- Generated by scripts/backlog/backlog.py render from backlog.yaml on the tracker branch. Do not edit:",
        "     change the backlog with the CLI or in the GitHub web UI, and the docs site rebuilds. -->",
        "",
        "# Tracker",
        "",
        "The shared backlog: every piece of engineering work that is open, who holds it, and its state "
        "(constitution Principle VIII). " + source + f" Rendered {_short(stamp(now))}.",
        "",
        f"**{len(live)} not done**: {sum(i['status'] in HELD for i in live)} held "
        f"({len(stale)} stale), {sum(i['status'] == 'blocked' for i in live)} blocked, "
        f"{sum(i['status'] == 'open' for i in live)} open and unclaimed. "
        f"**{len(recent)} done** in the last {RECENT_DAYS} days. {len(items)} items in all.",
        "",
        "| Status | P0 | P1 | P2 | P3 | Total |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
    ]
    for s in STATUSES:
        row = [sum(1 for i in items if i["status"] == s and i["priority"] == p) for p in PRIORITIES]
        L.append(f"| {s} | " + " | ".join(str(v) for v in row) + f" | {sum(row)} |")
    row = [sum(1 for i in live if i["priority"] == p) for p in PRIORITIES]
    L.append("| **not done** | " + " | ".join(f"**{v}**" for v in row) + f" | **{sum(row)}** |")
    L += ["", "## Burn-down", "", '<div class="tracker-chart">', svg.rstrip("\n"), "</div>", ""]
    if rows:
        L += [f"Counted at each day's last commit of the `tracker` branch (UTC). [Chart as SVG](burndown.svg).", "",
              '??? note "Daily counts"', "",
              "    | Day | Not done | P0–P1 | P2–P3 | Scope | Done |",
              "    | --- | ---: | ---: | ---: | ---: | ---: |"]
        L += [f"    | {d} | {r['open']} | {r['hi']} | {r['lo']} | {r['scope']} | {r['done']} |"
              for d, r in reversed(rows)]
        L.append("")
    L += ["## Claimed now", ""]
    if claimed:
        L += ["| Item | Pri | Status | Title | Holder | Agent | Since | Heartbeat | Branch | PRs |",
              "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"]
        for i in claimed:
            c = i["claim"]
            expired, age = claim_state(c, exp, now)
            beat = f"{fmt_age(age)} ago" + (" :warning: **STALE**" if expired else "")
            branch = c.get("branch") or (i.get("links") or {}).get("branch")
            L.append(f"| {i['id']} | {i['priority']} | {i['status']} | {md(i['title'])} | {md(c['by'])} | "
                     f"{md(c['agent'])} | {_short(c['since'])} | {beat} | {md(f'`{branch}`') if branch else '–'} | "
                     f"{_prs(i, repo)} |")
        L += ["", f"A claim is **STALE** when no heartbeat came within {exp} h; another holder may then take it "
                  "over with `claim --force`.", ""]
    else:
        L += ["Nothing is claimed.", ""]
    review = sorted((i for i in items if i["status"] == "review"), key=by_pid)
    L += ["## In review", ""]
    if review:
        L += ["| Item | Pri | Title | Holder | PRs |", "| --- | --- | --- | --- | --- |"]
        L += [f"| {i['id']} | {i['priority']} | {md(i['title'])} | {md(i['claim']['by'])} | {_prs(i, repo)} |"
              for i in review]
    else:
        L.append("Nothing is in review.")
    L.append("")
    blocked = sorted((i for i in items if i["status"] == "blocked"), key=by_pid)
    if blocked:
        L += ["## Blocked", "", "| Item | Pri | Title | Holder | Why (latest note) |",
              "| --- | --- | --- | --- | --- |"]
        L += [f"| {i['id']} | {i['priority']} | {md(i['title'])} | "
              f"{md(i['claim']['by']) if i.get('claim') else '–'} | "
              f"{md(i['notes'].split(chr(10))[-1]) if i.get('notes') else '–'} |" for i in blocked]
        L.append("")
    L += ["## Open, by priority", "", "Unclaimed items. Claim one before you start it.", ""]
    for p in PRIORITIES:
        ps = sorted((i for i in items if i["status"] == "open" and i["priority"] == p), key=by_pid)
        L += [f"### {p} ({len(ps)})", ""]
        if ps:
            L += ["| Item | Title | Area | Size | Links | Refs |", "| --- | --- | --- | --- | --- | --- |"]
            L += [f"| {i['id']} | {md(i['title'])} | {md(i['area'])} | {i.get('size', '–')} | {_links(i, repo)} | "
                  f"{md(', '.join(i['refs'])) if i['refs'] else '–'} |" for i in ps]
        else:
            L.append("None.")
        L.append("")
    L += [f"## Recently done ({RECENT_DAYS} days)", ""]
    if recent:
        L += ["| Item | Pri | Title | Closed | PRs |", "| --- | --- | --- | --- | --- |"]
        L += [f"| {i['id']} | {i['priority']} | {md(i['title'])} | {i['closed']} | {_prs(i, repo)} |"
              for i in recent]
    else:
        L.append("Nothing closed as done yet.")
    if dropped_recent:
        L += ["", f"{dropped_recent} item(s) were dropped in the same period."]
    L += [
        "",
        "## How to claim",
        "",
        '!!! tip "Claim before you start"',
        "",
        f"    The protocol is `{PROTOCOL}` in the repository. Set your node name once per host, then claim, keep",
        "    the status current, and close the item when its PR merges:",
        "",
        "    ```bash",
        "    export FUKUII_NODE=fukuii-fc          # or dev-node, cloud-<name>",
        "    python3 -I scripts/backlog/backlog.py list",
        "    python3 -I scripts/backlog/backlog.py claim B-012 --agent forge --branch fix/my-branch",
        "    python3 -I scripts/backlog/backlog.py status B-012 review --pr 1750",
        "    python3 -I scripts/backlog/backlog.py done B-012 --pr 1750",
        "    ```",
        "",
        "    A rejected push means someone moved first: the CLI fetches, re-checks and retries. A PR body names",
        "    its item with a `Backlog: B-012` line. People may also edit "
        f"[`backlog.yaml`]({repo}/edit/tracker/backlog.yaml) in the GitHub web UI.",
        "",
    ]
    return "\n".join(L)


def cmd_render(tr: Tracker | None, a) -> None:
    now = parse_stamp(a.now) if a.now else now_utc()
    repo = a.repo_url.rstrip("/")
    if a.file:
        raw = Path(a.file).read_text(encoding="utf-8")
        doc = parse(raw, a.file)
        snaps = {stamp(now)[:10]: counts(doc)}
        source = f"Source: `{Path(a.file).name}` (a local file, no history)."
    else:
        tip, doc = tr.read(not a.no_fetch)
        snaps = history(tr, tip)
        when = tr.g.run("log", "-1", "--format=%ct", tip).strip()
        when = stamp(dt.datetime.fromtimestamp(int(when), dt.timezone.utc))
        source = (f"Source: `backlog.yaml` on the [`{tr.branch}` branch]({repo}/tree/{tr.branch}) at "
                  f"[`{tip[:9]}`]({repo}/commit/{tip}) ({_short(when)}).")
    errs = validate(doc)
    if errs:
        raise BacklogError("the backlog is invalid, nothing rendered:\n  " + "\n  ".join(errs))
    rows = daily(snaps, stamp(now)[:10])
    svg = burndown_svg(rows)
    page = render_page(doc, rows, svg, now, source, repo)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    for name, text in (("burndown.svg", svg), ("index.md", page)):  # both built in memory first: no half render
        with open(out / name, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    print(f"rendered {out / 'index.md'} and {out / 'burndown.svg'}: {len(doc['items'])} items, {len(rows)} days")


# ----------------------------------------------------------------------------------------------------------- CLI


def build_parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--repo", default=".", help="a directory inside the fukuii clone (default: .)")
    common.add_argument("--remote", default=os.environ.get("FUKUII_TRACKER_REMOTE", "origin"))
    common.add_argument("--tracker-branch", default=os.environ.get("FUKUII_TRACKER_BRANCH", "tracker"))
    common.add_argument("--by", default=os.environ.get("FUKUII_NODE"),
                        help="your node: fukuii-fc, dev-node, cloud-<name> (default: $FUKUII_NODE)")
    reader = argparse.ArgumentParser(add_help=False)
    reader.add_argument("--no-fetch", action="store_true", help="use the local copy of the tracker branch")

    p = argparse.ArgumentParser(prog="backlog.py", description=__doc__.split("\n\n")[0],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True, metavar="COMMAND")

    s = sub.add_parser("list", parents=[common, reader], help="list items (not done or dropped unless --all/--status)")
    s.add_argument("--status", action="append", choices=STATUSES)
    s.add_argument("--priority", action="append", choices=PRIORITIES)
    s.add_argument("--mine", action="store_true", help="only items held by --by / $FUKUII_NODE")
    s.add_argument("--all", action="store_true", help="include done and dropped items")
    s.set_defaults(fn=cmd_list)

    s = sub.add_parser("show", parents=[common, reader], help="show one item")
    s.add_argument("id")
    s.set_defaults(fn=cmd_show)

    s = sub.add_parser("add", parents=[common], help="add an item (assigns the next id)")
    s.add_argument("--title", required=True)
    s.add_argument("--priority", required=True, choices=PRIORITIES)
    s.add_argument("--area", required=True, help="e.g. sync, snap, storage, rpc, p2p, evm, tui, ci, docs, ops")
    s.add_argument("--size", choices=SIZES)
    s.add_argument("--ref", action="append", help="an old id (H4, 10e, #1654-B, a CHASE row); repeatable")
    s.add_argument("--issue", action="append", type=int)
    s.add_argument("--pr", action="append", type=int)
    s.add_argument("--spec", help="e.g. specs/027-tui-dashboard")
    s.add_argument("--branch")
    s.add_argument("--depends-on", action="append")
    s.add_argument("--note", help="at most 3 lines; no secrets")
    s.add_argument("--opened", help="YYYY-MM-DD (default: today)")
    s.set_defaults(fn=cmd_add)

    s = sub.add_parser("claim", parents=[common], help="claim an item before you start it")
    s.add_argument("id")
    s.add_argument("--agent", help="who works it: forge, beacon, main, a person (default: $FUKUII_AGENT)")
    s.add_argument("--branch", help="the work branch")
    s.add_argument("--force", action="store_true", help="take over an EXPIRED claim (never a live one)")
    s.set_defaults(fn=cmd_claim)

    s = sub.add_parser("heartbeat", parents=[common], help="refresh your claims (no id: all of yours)")
    s.add_argument("ids", nargs="*")
    s.set_defaults(fn=cmd_heartbeat)

    s = sub.add_parser("status", parents=[common], help="set claimed, in-progress, review (--pr) or blocked (--note)")
    s.add_argument("id")
    s.add_argument("status", choices=SET_STATUSES)
    s.add_argument("--pr", type=int)
    s.add_argument("--note")
    s.set_defaults(fn=cmd_status)

    s = sub.add_parser("release", parents=[common], help="give an item back (open again)")
    s.add_argument("id")
    s.add_argument("--reason")
    s.add_argument("--force", action="store_true", help="release someone else's EXPIRED claim")
    s.set_defaults(fn=cmd_release)

    s = sub.add_parser("done", parents=[common], help="close an item as done (its PR merged)")
    s.add_argument("id")
    s.add_argument("--pr", type=int)
    s.add_argument("--note")
    s.add_argument("--force", action="store_true", help="close over someone else's EXPIRED claim")
    s.set_defaults(fn=cmd_done)

    s = sub.add_parser("drop", parents=[common], help="close an item as dropped")
    s.add_argument("id")
    s.add_argument("--reason", required=True)
    s.add_argument("--force", action="store_true", help="drop over someone else's EXPIRED claim")
    s.set_defaults(fn=cmd_drop)

    s = sub.add_parser("render", parents=[common, reader], help="write the Tracker page (index.md, burndown.svg)")
    s.add_argument("--out", required=True)
    s.add_argument("--file", help="render a local backlog.yaml instead of the branch (no history)")
    s.add_argument("--now", help="pin the clock, YYYY-MM-DDTHH:MM:SSZ (default: now)")
    s.add_argument("--repo-url", default=REPO_URL)
    s.set_defaults(fn=cmd_render)

    s = sub.add_parser("check", parents=[common, reader], help="validate the schema (the branch, or --file)")
    s.add_argument("--file")
    s.set_defaults(fn=cmd_check)
    return p


def main(argv: list[str] | None = None) -> int:
    a = build_parser().parse_args(argv)
    try:
        local = a.cmd in ("check", "render") and a.file
        tr = None if local else Tracker(a.repo, a.remote, a.tracker_branch)
        a.fn(tr, a)
        return 0
    except Refused as e:
        print(f"refused: {e}", file=sys.stderr)
        return 3
    except BacklogError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
