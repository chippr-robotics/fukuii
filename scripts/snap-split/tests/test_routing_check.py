#!/usr/bin/env python3
"""Self-test for routing_check.py (spec 016, R1 / T090): builds a fixture tree and a one-section routing doc.

    python3 scripts/snap-split/tests/test_routing_check.py
"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "routing_check.py"
SNAP = "src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap"

CORE = """package x

case class SNAPSyncConfig(accountConcurrency: Int, healingBatchSize: Int, unusedKey: Int)

object SNAPSyncConfig:
  def fromConfig(config: Config): SNAPSyncConfig =
    val snapConfig = config.getConfig("snap-sync")
    SNAPSyncConfig(
      accountConcurrency = snapConfig.getInt("account-concurrency"),
      healingBatchSize =
        if snapConfig.hasPath("healing-batch-size") then snapConfig.getInt("healing-batch-size")
        else 16,
      unusedKey = snapConfig.getInt("unused-key")
    )
"""

METRICS = """package x
object SNAPSyncMetrics:
  val a = metrics.registry.gauge("snapsync.accounts.synced.gauge", x)
  val b = metrics.counter("snapsync.healing.requests.total")
"""

MODULE = """package x
trait Healing:
  // [HEAL-COMMENT-ONLY] is mentioned here only
  def go(): Unit =
    log.info(s"[HEAL] batch ${snapSyncConfig.healingBatchSize}")
    log.info("SNAP_CALIBRATION_PEER ok")
"""

HEAD = """# Routing

## Phase / module routing

| Phase / module | Status | Current location | Target (post-split) | Config keys | Metrics | Log tags | Reviewer |
|---|---|---|---|---|---|---|---|
"""


def row(status="**target (post-split)** since M6", current="`controller/Healing.scala`", target="unchanged",
        config="`healing-batch-size`", metrics="`snapsync.healing.*`", tags="`[HEAL]`"):
    return f"| **Healing** | {status} | {current} | {target} | {config} | {metrics} | {tags} | prism |\n"


def run(rows, extra_files=None):
    with tempfile.TemporaryDirectory() as d:
        root = Path(d)
        files = {
            f"{SNAP}/SNAPSyncController.scala": CORE,
            f"{SNAP}/SNAPSyncMetrics.scala": METRICS,
            f"{SNAP}/controller/Healing.scala": MODULE,
            "src/main/scala/com/chipprbots/ethereum/db/storage/FrontierStore.scala": "package x\n",
            "src/main/scala/com/chipprbots/ethereum/network/PeerThing.scala": "package x\n// TD-PROXY-GAP\nval s = \"TD-PROXY-GAP\"\n",
        }
        files.update(extra_files or {})
        for p, t in files.items():
            (root / p).parent.mkdir(parents=True, exist_ok=True)
            (root / p).write_text(t)
        doc = root / "doc.md"
        doc.write_text(HEAD + "".join(rows) + "\n## Next section\n\n| not | a | routing | row |\n")
        r = subprocess.run([sys.executable, str(SCRIPT), "--root", str(root), "--doc", str(doc)],
                           capture_output=True, text=True)
        return r.returncode, r.stdout + r.stderr


class RoutingCheckTest(unittest.TestCase):
    def assert_pass(self, rows, **kw):
        code, out = run(rows, **kw)
        self.assertEqual(code, 0, out)
        return out

    def assert_fail(self, rows, needle, **kw):
        code, out = run(rows, **kw)
        self.assertEqual(code, 1, out)
        self.assertIn(needle, out)
        return out

    def test_clean_row_passes(self):
        out = self.assert_pass([row()])
        self.assertIn("1/1 rows pass", out)

    def test_status_must_be_target(self):
        self.assert_fail([row(status="current")], "status: not marked")

    def test_status_unchanged_is_target(self):
        self.assert_pass([row(status="**target (post-split)**: unchanged (not moved)")])

    def test_missing_file(self):
        self.assert_fail([row(target="`controller/Gone.scala`")], "files: `controller/Gone.scala` does not exist")

    def test_brace_expansion_of_files(self):
        self.assert_fail([row(target="`controller/{Healing,Gone}.scala`")], "`controller/Gone.scala` does not exist")

    def test_ssc_alias(self):
        self.assert_pass([row(current="`SSC` + `controller/Healing.scala`")])

    def test_src_ellipsis_resolves_uniquely(self):
        self.assert_pass([row(current="`controller/Healing.scala`; `src/.../db/storage/FrontierStore.scala`")])
        self.assert_fail([row(current="`controller/Healing.scala`; `src/.../db/storage/Nope.scala`")],
                         "`src/.../db/storage/Nope.scala` does not exist")

    def test_tag_not_emitted(self):
        self.assert_fail([row(tags="`[HEAL]`, `[HEAL-GONE]`")], "tags: `[HEAL-GONE]` is not emitted")

    def test_tag_in_comment_only_does_not_count(self):
        self.assert_fail([row(tags="`[HEAL-COMMENT-ONLY]`")], "`[HEAL-COMMENT-ONLY]` is not emitted")

    def test_snake_tag(self):
        self.assert_pass([row(tags="`SNAP_CALIBRATION_PEER`")])

    def test_plain_text_is_not_checked(self):
        self.assert_pass([row(tags="`[HEAL]`; no longer logs the [HEAL-GONE] tag",
                              config="`healing-batch-size`; unused-key is parsed but read nowhere")])

    def test_network_tag_and_path(self):
        self.assert_pass([row(current="`network/PeerThing.scala`", config="`network.*` (spec 011)", metrics="—",
                              tags="`TD-PROXY-GAP`")])

    def test_metric_wildcard_and_braces(self):
        self.assert_pass([row(metrics="`snapsync.{accounts,healing}.*`")])
        self.assert_fail([row(metrics="`snapsync.storage.*`")], "metrics: `snapsync.storage.*` is not registered")

    def test_config_not_parsed(self):
        self.assert_fail([row(config="`healing-gone`")], "config: `healing-gone` is not parsed in SNAPSyncConfig")

    def test_config_parsed_but_not_read_in_row(self):
        self.assert_fail([row(config="`account-concurrency`")],
                         "`account-concurrency` (SNAPSyncConfig.accountConcurrency) is not read")

    def test_config_wildcard_expands_over_parsed_keys(self):
        self.assert_pass([row(config="`healing-*`")])
        self.assert_fail([row(config="`*-concurrency`")], "`account-concurrency`")


if __name__ == "__main__":
    unittest.main()
