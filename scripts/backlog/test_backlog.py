#!/usr/bin/env python3
"""Self-test for scripts/backlog/backlog.py. Each case builds throwaway bare repositories, so it runs offline in
seconds and never touches the real `tracker` branch.

    python3 -I -m unittest discover -s scripts/backlog -v
"""
from __future__ import annotations

import contextlib
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))  # `python3 -I` drops the script directory from sys.path
import backlog  # noqa: E402

BACKLOG = HERE / "backlog.py"
T0 = "2026-11-08T12:00:00Z"


def item(n: int, **kw) -> dict:
    it = {"id": f"B-{n:03d}", "title": f"item {n}", "priority": "P2", "status": "open", "area": "sync",
          "opened": "2026-11-01", "refs": [], "links": {}}
    it.update(kw)
    return it


def claim(by: str, since: str, heartbeat: str | None = None, agent: str = "forge") -> dict:
    return {"by": by, "agent": agent, "since": since, "heartbeat": heartbeat or since}


def doc(*items: dict) -> dict:
    return {"version": 1, "expiry_hours": 24, "items": list(items)}


class GitCase(unittest.TestCase):
    """A bare `origin` with a `tracker` branch, and clones of it."""

    def initial(self) -> dict:
        return doc(
            item(1),
            item(2),
            item(3, status="claimed", claim=claim("old", "2026-11-01T00:00:00Z")),
            item(4, status="in-progress",
                 claim=claim("busy", "2026-11-08T10:00:00Z", "2026-11-08T11:00:00Z", agent="beacon")),
        )

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="backlog-test-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        (self.tmp / "gitconfig").write_text("")
        self.env = {k: v for k, v in os.environ.items() if not k.startswith(("FUKUII_", "GIT_"))}
        self.env.update(GIT_CONFIG_GLOBAL=str(self.tmp / "gitconfig"), GIT_CONFIG_NOSYSTEM="1",
                        GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@example.invalid",
                        GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@example.invalid")
        self.origin = self.tmp / "origin.git"
        self.git("init", "-q", "--bare", "-b", "main", str(self.origin))
        self.seed = self.tmp / "seed"
        self.git("init", "-q", "-b", "main", str(self.seed))
        self.git("-C", str(self.seed), "commit", "-q", "--allow-empty", "-m", "main")
        self.git("-C", str(self.seed), "remote", "add", "origin", str(self.origin))
        self.git("-C", str(self.seed), "push", "-q", "origin", "main")
        self.git("-C", str(self.seed), "checkout", "-q", "--orphan", "tracker")
        self.publish(self.initial(), "2026-11-06T10:00:00Z")

    def git(self, *args: str, env: dict | None = None) -> str:
        r = subprocess.run(["git", *args], capture_output=True, text=True, env=env or self.env)
        if r.returncode != 0:
            raise AssertionError(f"git {args} failed: {r.stderr}")
        return r.stdout

    def publish(self, d: dict, date: str) -> None:
        with open(self.seed / "backlog.yaml", "w", encoding="utf-8", newline="\n") as f:
            f.write(backlog.dump(d))
        env = dict(self.env, GIT_AUTHOR_DATE=date, GIT_COMMITTER_DATE=date)
        self.git("-C", str(self.seed), "add", "backlog.yaml", env=env)
        self.git("-C", str(self.seed), "commit", "-q", "-m", f"seed {date}", env=env)
        self.git("-C", str(self.seed), "push", "-q", "origin", "tracker", env=env)

    def clone(self, name: str) -> Path:
        path = self.tmp / name
        self.git("clone", "-q", str(self.origin), str(path))
        return path

    def cli(self, clone: Path, *args: str, by: str | None = None, now: str = T0) -> subprocess.CompletedProcess:
        cmd = [sys.executable, "-I", str(BACKLOG), *args, "--repo", str(clone)]
        if by:
            cmd += ["--by", by]
        return subprocess.run(cmd, capture_output=True, text=True, env=dict(self.env, FUKUII_BACKLOG_NOW=now))

    def remote(self) -> dict:
        return backlog.parse(self.git("--git-dir", str(self.origin), "cat-file", "blob", "tracker:backlog.yaml"))

    def remote_item(self, iid: str) -> dict:
        return next(i for i in self.remote()["items"] if i["id"] == iid)

    def last_message(self) -> str:
        return self.git("--git-dir", str(self.origin), "log", "-1", "--format=%B", "tracker")


class ClaimTests(GitCase):
    def test_claim_leaves_the_checkout_alone(self):
        a = self.clone("a")
        head = self.git("-C", str(a), "rev-parse", "HEAD")
        r = self.cli(a, "claim", "B-001", "--agent", "forge", "--branch", "fix/x", by="fc")
        self.assertEqual(r.returncode, 0, r.stderr)
        it = self.remote_item("B-001")
        self.assertEqual(it["status"], "claimed")
        self.assertEqual(it["claim"], {"by": "fc", "agent": "forge", "since": T0, "heartbeat": T0, "branch": "fix/x"})
        self.assertEqual(self.git("-C", str(a), "rev-parse", "HEAD"), head)
        self.assertEqual(self.git("-C", str(a), "status", "--porcelain"), "")
        self.assertEqual(self.git("-C", str(a), "branch", "--list", "tracker"), "")
        self.assertEqual(len(self.git("-C", str(a), "worktree", "list").splitlines()), 1)
        self.assertIn("tracker: claim B-001 by fc (forge)", self.last_message())

    def test_two_racing_claims_exactly_one_wins(self):
        clones = [self.clone(f"c{i}") for i in range(4)]
        results: dict[int, subprocess.CompletedProcess] = {}
        gate = threading.Barrier(len(clones))

        def run(i: int):
            gate.wait()
            results[i] = self.cli(clones[i], "claim", "B-002", "--agent", "main", by=f"node{i}")

        threads = [threading.Thread(target=run, args=(i,)) for i in range(len(clones))]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=120)
        codes = sorted(r.returncode for r in results.values())
        self.assertEqual(codes, [0, 3, 3, 3], {i: r.stderr for i, r in results.items()})
        winner = next(i for i, r in results.items() if r.returncode == 0)
        self.assertEqual(self.remote_item("B-002")["claim"]["by"], f"node{winner}")
        for i, r in results.items():
            if i != winner:
                self.assertIn(f"held by node{winner}", r.stderr)
        log = self.git("--git-dir", str(self.origin), "log", "--format=%s", "tracker")
        self.assertEqual(log.count("tracker: claim B-002"), 1)

    def test_a_lost_push_refetches_and_refuses(self):
        a, b = self.clone("a"), self.clone("b")
        real_push = backlog.Tracker.push
        pushes = []

        def racing_push(tracker, commit, base):
            if not pushes:  # alpha lands its claim between beta's fetch and beta's push
                r = self.cli(a, "claim", "B-002", "--agent", "forge", by="alpha")
                self.assertEqual(r.returncode, 0, r.stderr)
            pushes.append(commit)
            return real_push(tracker, commit, base)

        err = io.StringIO()
        with mock.patch.dict(os.environ, dict(self.env, FUKUII_BACKLOG_NOW=T0), clear=True), \
                mock.patch.object(backlog.Tracker, "push", racing_push), contextlib.redirect_stderr(err):
            rc = backlog.main(["claim", "B-002", "--agent", "beacon", "--by", "beta", "--repo", str(b)])
        self.assertEqual(rc, 3, err.getvalue())
        self.assertEqual(len(pushes), 1)  # rejected once, then the re-check refused without another push
        self.assertIn("held by alpha", err.getvalue())
        self.assertEqual(self.remote_item("B-002")["claim"]["by"], "alpha")

    def test_an_expired_claim_can_be_forced(self):
        a = self.clone("a")
        r = self.cli(a, "claim", "B-003", "--agent", "forge", by="new")
        self.assertEqual(r.returncode, 3)
        self.assertIn("EXPIRED", r.stderr)
        self.assertIn("--force", r.stderr)
        self.assertEqual(self.remote_item("B-003")["claim"]["by"], "old")
        r = self.cli(a, "claim", "B-003", "--agent", "forge", "--force", by="new")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.remote_item("B-003")["claim"]["by"], "new")
        msg = self.last_message()
        self.assertIn("took over an expired claim", msg)
        self.assertIn("EXPIRED claim by old", msg)

    def test_an_unexpired_claim_cannot_be_forced(self):
        a = self.clone("a")
        r = self.cli(a, "claim", "B-004", "--agent", "forge", "--force", by="new")
        self.assertEqual(r.returncode, 3)
        self.assertIn("held by busy (beacon)", r.stderr)
        self.assertIn("since 2026-11-08T10:00:00Z", r.stderr)
        self.assertEqual(self.remote_item("B-004")["claim"]["by"], "busy")

    def test_a_heartbeat_extends_the_claim(self):
        a = self.clone("a")
        old = self.remote_item("B-004")["claim"]
        later = "2026-11-09T20:00:00Z"  # 33 h after the seeded heartbeat
        self.assertTrue(backlog.claim_state(old, 24, backlog.parse_stamp(later))[0])
        r = self.cli(a, "heartbeat", "B-004", by="busy", now="2026-11-09T10:00:00Z")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.remote_item("B-004")["claim"]["heartbeat"], "2026-11-09T10:00:00Z")
        r = self.cli(a, "claim", "B-004", "--agent", "forge", "--force", by="new", now=later)
        self.assertEqual(r.returncode, 3, r.stderr)
        self.assertEqual(self.remote_item("B-004")["claim"]["by"], "busy")

    def test_a_heartbeat_by_someone_else_is_refused(self):
        r = self.cli(self.clone("a"), "heartbeat", "B-004", by="other")
        self.assertEqual(r.returncode, 3)
        self.assertIn("not held by other", r.stderr)

    def test_identity_is_required(self):
        r = self.cli(self.clone("a"), "claim", "B-001", "--agent", "forge")
        self.assertEqual(r.returncode, 1)
        self.assertIn("FUKUII_NODE", r.stderr)

    def test_status_review_and_done(self):
        a = self.clone("a")
        self.assertEqual(self.cli(a, "status", "B-001", "in-progress", by="fc").returncode, 3)  # claim first
        self.assertEqual(self.cli(a, "claim", "B-001", "--agent", "forge", by="fc").returncode, 0)
        self.assertEqual(self.cli(a, "status", "B-001", "in-progress", by="fc").returncode, 0)
        self.assertEqual(self.cli(a, "status", "B-001", "review", by="fc").returncode, 3)  # needs --pr
        self.assertEqual(self.cli(a, "status", "B-001", "review", "--pr", "1750", by="fc").returncode, 0)
        self.assertEqual(self.cli(a, "done", "B-001", by="other").returncode, 3)  # live claim of fc
        r = self.cli(a, "done", "B-001", "--pr", "1750", by="fc", now="2026-11-09T08:00:00Z")
        self.assertEqual(r.returncode, 0, r.stderr)
        it = self.remote_item("B-001")
        self.assertEqual((it["status"], it["closed"], it["links"]["prs"]), ("done", "2026-11-09", [1750]))
        self.assertNotIn("claim", it)

    def test_blocked_needs_a_reason_and_release_reopens(self):
        a = self.clone("a")
        self.assertEqual(self.cli(a, "claim", "B-001", "--agent", "forge", by="fc").returncode, 0)
        self.assertEqual(self.cli(a, "status", "B-001", "blocked", by="fc").returncode, 3)
        self.assertEqual(self.cli(a, "status", "B-001", "blocked", "--note", "waits for #1747", by="fc").returncode, 0)
        r = self.cli(a, "release", "B-001", "--reason", "switching to B-002", by="fc")
        self.assertEqual(r.returncode, 0, r.stderr)
        it = self.remote_item("B-001")
        self.assertEqual(it["status"], "blocked")  # releasing does not unblock
        self.assertNotIn("claim", it)
        self.assertIn("released by fc: switching to B-002", it["notes"])
        self.assertEqual(self.cli(a, "claim", "B-002", "--agent", "forge", by="fc").returncode, 0)
        self.assertEqual(self.cli(a, "release", "B-002", by="fc").returncode, 0)
        self.assertEqual(self.remote_item("B-002")["status"], "open")

    def test_add_assigns_the_next_id(self):
        r = self.cli(self.clone("a"), "add", "--title", "new: thing", "--priority", "P1", "--area", "rpc",
                     "--issue", "12", "--ref", "#1654-B", by="fc")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.strip(), "B-005")
        it = self.remote_item("B-005")
        self.assertEqual((it["title"], it["links"], it["refs"], it["opened"]),
                         ("new: thing", {"issues": [12]}, ["#1654-B"], "2026-11-08"))


class CheckTests(unittest.TestCase):
    def check(self, d: dict) -> subprocess.CompletedProcess:
        with tempfile.TemporaryDirectory() as t:
            f = Path(t) / "backlog.yaml"
            f.write_text(backlog.dump(d) if not backlog.validate(d) else _raw(d), encoding="utf-8")
            return subprocess.run([sys.executable, "-I", str(BACKLOG), "check", "--file", str(f)],
                                  capture_output=True, text=True)

    def test_a_valid_backlog_passes(self):
        r = self.check(doc(item(1), item(2, status="done", closed="2026-11-08")))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("OK", r.stdout)

    def test_a_bad_status_is_rejected(self):
        r = self.check(doc(item(1, status="wip")))
        self.assertEqual(r.returncode, 1)
        self.assertIn("status must be one of", r.stderr)

    def test_a_duplicate_id_is_rejected(self):
        r = self.check(doc(item(1), item(1, title="again")))
        self.assertEqual(r.returncode, 1)
        self.assertIn("duplicate id", r.stderr)

    def test_claims_and_closures_follow_the_status(self):
        errs = backlog.validate(doc(item(1, claim=claim("x", T0)), item(2, status="review"),
                                    item(3, status="done"), item(4, oops=1)))
        text = "\n".join(errs)
        self.assertIn("B-001: status open must not carry a claim", text)
        self.assertIn("B-002: status review needs a claim", text)
        self.assertIn("B-003: a done item needs `closed`", text)
        self.assertIn("B-004: unknown fields ['oops']", text)

    def test_a_duplicate_key_is_rejected(self):
        with self.assertRaises(backlog.BacklogError):
            backlog.parse("version: 1\nversion: 1\nexpiry_hours: 24\nitems: []\n")

    def test_dump_round_trips_awkward_text(self):
        d = doc(item(1, title="CHASE A: `max-open-files` # not a comment", refs=["#1654-B", "10e", "yes", "a, b"],
                     notes="first line: with colon\n* a bullet-looking line\n\"quoted\""),
                item(2, title="'single' and \"double\" quotes", notes="no"))
        text = backlog.dump(d)
        self.assertEqual(backlog.parse(text), d)
        self.assertNotIn("\r", text)


def _raw(d: dict) -> str:
    """An invalid document written plainly (dump() refuses nothing, but keep the test independent of it)."""
    import yaml
    return yaml.safe_dump(d, sort_keys=False)


class RenderTests(GitCase):
    def test_render_is_deterministic_and_complete(self):
        d = self.initial()
        d["items"].append(item(5, status="review", priority="P1", links={"prs": [1747]},
                               claim=claim("fc", "2026-11-07T09:00:00Z", "2026-11-08T09:00:00Z")))
        d["items"].append(item(6, status="done", closed="2026-11-07", links={"prs": [1746]}))
        self.publish(d, "2026-11-07T10:00:00Z")
        a = self.clone("a")
        outs = [self.tmp / "o1", self.tmp / "o2"]
        for o in outs:
            r = self.cli(a, "render", "--out", str(o), "--now", T0)
            self.assertEqual(r.returncode, 0, r.stderr)
        for name in ("index.md", "burndown.svg"):
            self.assertEqual((outs[0] / name).read_bytes(), (outs[1] / name).read_bytes(), name)
        page = (outs[0] / "index.md").read_text(encoding="utf-8")
        for heading in ("# Tracker", "## Burn-down", "## Claimed now", "## In review", "## Open, by priority",
                        "## Recently done (14 days)", "## How to claim"):
            self.assertIn(heading, page)
        self.assertIn("**STALE**", page)  # B-003's claim is a week old
        self.assertIn("https://github.com/chippr-robotics/fukuii/pull/1747", page)
        self.assertIn("| 2026-11-08 |", page)  # history filled to today
        self.assertIn("| 2026-11-06 |", page)
        relative = re.findall(r"\]\((?!https://|#|burndown\.svg\))([^)]*)\)", page)
        self.assertEqual(relative, [], "the page may link only to absolute URLs and its own chart")
        svg = ET.parse(outs[0] / "burndown.svg").getroot()
        self.assertEqual(len(svg.findall(".//{http://www.w3.org/2000/svg}polyline")), 5)
        self.assertNotIn(b"\r", (outs[0] / "index.md").read_bytes())


if __name__ == "__main__":
    unittest.main()
