#!/usr/bin/env python3
"""Fixture-based self-test for scripts/snap-split/verify.sh (spec 016, S0f).

Each case builds a throwaway git repository with the real snap/ directory layout, commits a base and
the commits under test, then runs verify.sh on base..HEAD and asserts pass/fail (and which check).

    python3 scripts/snap-split/tests/test_verify.py
"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

VERIFY = Path(__file__).resolve().parents[1] / "verify.sh"
SNAP = "src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap"
CTRL = f"{SNAP}/controller"
CORE = f"{SNAP}/SNAPSyncController.scala"
TEST = "src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/FooSpec.scala"

CORE_BASE = """package x

class SNAPSyncControllerImpl:
  private var count = 0
  var shared = 1

  private def heal(a: Int) =
    val tmp = a + 1
    count = tmp
    println(tmp)

  def other(): Int = 2
"""

CORE_AFTER_MOVE = """package x

class SNAPSyncControllerImpl extends HealingOrchestrator:
  var shared = 1

  def other(): Int = 2
"""

MODULE_MOVE = """package x.controller

import x.Foo

private[snap] trait HealingOrchestrator:
  self: SNAPSyncControllerImpl =>
  private var count = 0

  private[snap] def heal(a: Int) =
    val tmp = a + 1
    count = tmp
    println(tmp)
"""

MODULE_NARROW = """package x.controller

import x.Foo

private[snap] trait HealingState:
  def shared: Int
  def shared_=(v: Int): Unit

private[snap] trait HealingOrchestrator:
  self: HealingState & SnapSharedState =>
  private var count = 0

  private[snap] def heal(a: Int) =
    val tmp = a + 1
    count = tmp
    println(tmp)
"""

STUB_TEST = """package x
class StubSpec:
  val s = new StubHealingState with HealingOrchestrator
"""

SPEC_BASE = """package x
import a.b
class FooSpec:
  "foo" should "work" in {
    1 shouldBe 1
  }
  val fixture = Setup(1)
"""


_FIXTURES = []


def tearDownModule():
    for t in _FIXTURES:
        t.cleanup()


class Fixture:
    def __init__(self, branch="refactor/snap-016-m2"):
        self.tmp = tempfile.TemporaryDirectory()
        _FIXTURES.append(self.tmp)
        self.d = Path(self.tmp.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@t")
        self.git("config", "user.name", "t")
        self.write(CORE, CORE_BASE)
        self.write(TEST, SPEC_BASE)
        self.write("src/main/resources/application.conf", "a = 1\n")
        self.commit("base")
        self.git("tag", "base")
        self.git("checkout", "-q", "-b", branch)
        self.branch = branch

    def git(self, *a):
        return subprocess.run(["git", *a], cwd=self.d, check=True, capture_output=True, text=True).stdout

    def write(self, path, text):
        p = self.d / path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)

    def commit(self, msg):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", msg)

    def verify(self, *extra):
        env = dict(os.environ, SNAP_VERIFY_CONFIG_DIR=str(self.cfg()))
        p = subprocess.run([str(VERIFY), *extra, "base"], cwd=self.d, capture_output=True, text=True, env=env)
        return p.returncode, p.stdout + p.stderr

    def cfg(self):
        c = self.d / ".cfg"
        c.mkdir(exist_ok=True)
        if not (c / "protected-paths.txt").exists():
            (c / "protected-paths.txt").write_text("# none\nsrc/test/scala/golden/*\n")
        if not (c / "test-branch-main-allow.txt").exists():
            (c / "test-branch-main-allow.txt").write_text("# empty\n")
        return c


def move_commit(f: Fixture, module=MODULE_MOVE, core=CORE_AFTER_MOVE, symbols="heal count", partial=False):
    f.write(f"{CTRL}/HealingOrchestrator.scala", module)
    f.write(CORE, core)
    extra = "# partial: test fixture, narrowed in a later commit\n" if partial else ""
    f.commit(f"refactor(snap): move heal (#1401)\n\n# moved: {symbols}\n{extra}")


NARROW_MSG = "refactor(snap): narrow (#1401)\n\n# new-tests: src/test/scala/x/StubSpec.scala\n"


class VerifyTests(unittest.TestCase):
    def ok(self, f, *a):
        rc, out = f.verify(*a)
        self.assertEqual(rc, 0, out)
        return out

    def bad(self, f, check, *a):
        rc, out = f.verify(*a)
        self.assertEqual(rc, 1, out)
        self.assertIn(f"[{check}]", out)
        return out

    # ---- move + narrowing happy path
    def test_move_then_narrow_passes_and_prints_counts(self):
        f = Fixture()
        move_commit(f)
        f.write(f"{CTRL}/HealingOrchestrator.scala", MODULE_NARROW)
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST)
        f.commit(NARROW_MSG)
        out = self.ok(f)
        self.assertIn("[counts] HealingOrchestrator: State=HealingState(2)", out)

    def test_list_move_commits(self):
        f = Fixture()
        move_commit(f, partial=True)
        rc, out = f.verify("--list-move-commits")
        self.assertEqual(rc, 0)
        self.assertEqual(len(out.split()), 1)

    # ---- step 1 and 2
    def test_modified_body_fails_identity(self):
        f = Fixture()
        move_commit(f, module=MODULE_MOVE.replace("val tmp = a + 1", "val tmp = a + 2"))
        out = self.bad(f, "step2")
        self.assertIn("`heal` differs", out)

    def test_deleted_code_fails_moved_blocks(self):
        f = Fixture()
        move_commit(f, core=CORE_AFTER_MOVE.replace("  def other(): Int = 2\n", ""))
        self.bad(f, "step1")

    def test_missing_symbol_fails(self):
        f = Fixture()
        move_commit(f, symbols="heal nothere")
        self.bad(f, "step2")

    def test_only_visibility_change_is_allowed(self):
        f = Fixture()
        move_commit(f, partial=True)
        self.ok(f)

    # ---- (a)
    def test_two_impl_mentions_in_move_commit_fail(self):
        f = Fixture()
        move_commit(f, module=MODULE_MOVE.replace("  private var", "  // see SNAPSyncControllerImpl\n  private var"))
        self.bad(f, "FR-016a")

    def test_impl_mention_after_narrowing_fails(self):
        f = Fixture()
        move_commit(f)
        f.write(f"{CTRL}/HealingOrchestrator.scala", MODULE_NARROW.replace("import x.Foo", "import x.Foo // SNAPSyncControllerImpl"))
        f.commit("narrow")
        self.bad(f, "FR-016a")

    def test_coordinator_impl_in_actor_dir_fails(self):
        f = Fixture()
        f.write(f"{SNAP}/actors/healing/Mod.scala", "package x\ntrait Mod:\n  self: TrieNodeHealingCoordinatorImpl =>\n  def a = 1\n")
        f.commit("add")
        self.bad(f, "FR-016a")

    # ---- (d)
    def d_case(self, body):
        f = Fixture()
        f.write(f"{CTRL}/M.scala", "package x\n\ntrait M:\n" + body)
        f.commit("add")
        return f

    def test_d_local_val_in_def_passes(self):
        self.ok(self.d_case("  def a(x: Int) =\n    val y = x\n    y\n"))

    def test_d_member_val_fails(self):
        self.bad(self.d_case("  val a = 1\n"), "FR-016d")

    def test_d_lazy_val_passes(self):
        self.ok(self.d_case("  lazy val a = compute()\n"))

    def test_d_top_level_statement_fails(self):
        self.bad(self.d_case('  println("x")\n'), "FR-016d")

    def test_d_impure_var_fails(self):
        self.bad(self.d_case("  private var t = System.nanoTime()\n"), "FR-016d")
        self.bad(self.d_case("  private var t = ctx.self\n"), "FR-016d")

    def test_d_allowed_var_inits_pass(self):
        body = (
            "  private var a = 0\n  private var b = -1L\n  private var c = true\n  private var d = \"s\"\n"
            "  private var e: Option[Int] = None\n  private var g = Nil\n  private var h = Map.empty[String, Int]\n"
            "  private var i = Foo.Bar.Limit\n  var f: Int => Int = Foo.Ident\n  var q: Map[String, Int => Int] = Map.empty\n  var j: Int\n  def k: Int\n  type T = Int\n  import a.b\n  end M\n"
        )
        self.ok(self.d_case(body))

    def test_d_companion_method_call_fails(self):
        self.bad(self.d_case("  private var t = Foo.compute\n"), "FR-016d")

    def test_d_trait_nested_in_object_is_checked(self):
        f = Fixture()
        f.write(f"{CTRL}/M.scala", "package x\n\nobject O:\n  trait M:\n    val a = 1\n    def b = 2\n")
        f.commit("add")
        self.bad(f, "FR-016d")

    def test_d_object_members_not_checked(self):
        f = Fixture()
        f.write(f"{CTRL}/M.scala", "package x\n\ntrait M:\n  def a = 1\n\nobject M:\n  val C = 1\n")
        f.commit("add")
        self.ok(f)

    # ---- step 6 and (b)
    def test_narrowing_that_changes_a_body_fails(self):
        f = Fixture()
        move_commit(f)
        f.write(f"{CTRL}/HealingOrchestrator.scala", MODULE_NARROW.replace("count = tmp", "count = tmp + 1"))
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST)
        f.commit(NARROW_MSG)
        out = self.bad(f, "step6")
        self.assertIn("count = tmp", out)

    def test_narrowing_without_stub_test_fails(self):
        f = Fixture()
        move_commit(f)
        f.write(f"{CTRL}/HealingOrchestrator.scala", MODULE_NARROW)
        f.commit("narrow")
        self.bad(f, "FR-016b")

    def test_narrowing_with_tagged_stub_test_fails(self):
        f = Fixture()
        move_commit(f)
        f.write(f"{CTRL}/HealingOrchestrator.scala", MODULE_NARROW)
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST + "// taggedAs SyncTest\n")
        f.commit("narrow")
        self.bad(f, "FR-016b")

    # ---- step 3 on refactor branches
    def test_refactor_import_only_test_change_passes(self):
        f = Fixture()
        f.write(TEST, SPEC_BASE.replace("import a.b", "import a.c"))
        f.commit("imports")
        self.ok(f)

    def test_refactor_fixture_change_passes(self):
        f = Fixture()
        f.write(TEST, SPEC_BASE.replace("Setup(1)", "Setup(2)"))
        f.commit("fixture")
        self.ok(f)

    def test_refactor_assertion_change_fails(self):
        f = Fixture()
        f.write(TEST, SPEC_BASE.replace("1 shouldBe 1", "1 shouldBe 2"))
        f.commit("assertion")
        self.bad(f, "step3")

    def test_refactor_timeout_and_eventually_fail(self):
        f = Fixture()
        f.write(TEST, SPEC_BASE.replace("val fixture", "eventually(timeout(5.seconds)) { }\n  val fixture"))
        f.commit("sched")
        self.bad(f, "step3")

    def test_refactor_new_test_file_needs_new_tests_trailer(self):
        f = Fixture()
        f.write("src/test/scala/x/NewSpec.scala", "class NewSpec { 1 shouldBe 1 }\n")
        f.commit("new")
        self.bad(f, "step3")
        g = Fixture()
        g.write("src/test/scala/x/NewSpec.scala", "class NewSpec { 1 shouldBe 1 }\n")
        g.commit("new\n\n# new-tests: src/test/scala/x/NewSpec.scala\n")
        self.ok(g)

    def test_new_tests_wildcard_rejected(self):
        f = Fixture()
        f.write("src/test/scala/x/NewSpec.scala", "class NewSpec\n")
        f.commit("new\n\n# new-tests: src/test/scala/x/*.scala\n")
        self.bad(f, "step3")

    def test_refactor_deleting_a_suite_fails(self):
        f = Fixture()
        (f.d / TEST).unlink()
        f.commit("rm")
        self.bad(f, "step3")

    def test_other_branch_is_exempt_from_test_hunks(self):
        f = Fixture(branch="ci/other")
        f.write(TEST, SPEC_BASE.replace("1 shouldBe 1", "1 shouldBe 2"))
        f.commit("assertion")
        self.ok(f)

    # ---- step 3 on test branches
    def test_test_branch_with_trailer_passes(self):
        f = Fixture(branch="test/snap-016-s0b")
        f.write(TEST, SPEC_BASE.replace("1 shouldBe 1", "1 shouldBe 2"))
        f.commit("test(snap): pin\n\n# test-files: src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/FooSpec.scala\n")
        self.ok(f)

    def test_test_branch_glob_trailer_rejected(self):
        f = Fixture(branch="test/snap-016-s0c")
        f.write(TEST, SPEC_BASE + "\n")
        f.commit("test(snap): g\n\n# test-files: src/test/scala/**/FooSpec.scala\n")
        out = self.bad(f, "step3")
        self.assertIn("exact paths only", out)

    def test_test_branch_without_trailer_fails(self):
        f = Fixture(branch="test/snap-016-s0b")
        f.write(TEST, SPEC_BASE + "\n")
        f.commit("test(snap): pin")
        self.bad(f, "step3")

    def test_test_branch_unlisted_file_fails(self):
        f = Fixture(branch="test/snap-016-s0b")
        f.write(TEST, SPEC_BASE + "\n")
        f.write("src/test/scala/x/Other.scala", "x\n")
        f.commit("test(snap): pin\n\n# test-files: src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/FooSpec.scala\n")
        out = self.bad(f, "step3")
        self.assertIn("Other.scala", out)

    def test_test_branch_unlisted_src_main_fails(self):
        f = Fixture(branch="test/snap-016-s0b")
        f.write(f"{SNAP}/Other.scala", "package x\n")
        f.commit("test(snap): seam\n\n# test-files: none\n")
        self.bad(f, "step3")

    def test_test_branch_explicit_allow_entry_still_works(self):
        f = Fixture(branch="test/snap-016-s0b")
        f.write(CORE, CORE_BASE + "\n// seam\n")
        f.commit("test(snap): seam\n\n# test-files: none\n")
        (f.cfg() / "test-branch-main-allow.txt").write_text(f"{CORE}\n")
        self.ok(f)

    # ---- step 4
    def test_resources_change_fails(self):
        f = Fixture()
        f.write("src/main/resources/application.conf", "a = 2\n")
        f.commit("conf")
        self.bad(f, "step4")

    def test_protected_path_fails_on_refactor_only(self):
        f = Fixture()
        f.write("src/test/scala/golden/G.scala", "x\n")
        f.commit("golden")
        self.bad(f, "step4")
        g = Fixture(branch="test/snap-016-s0c")
        g.write("src/test/scala/golden/G.scala", "x\n")
        g.commit("golden\n\n# test-files: src/test/scala/golden/G.scala\n")
        self.ok(g)

    # ---- review fixes
    def test_narrowing_with_moved_trailer_fails(self):
        f = Fixture()
        move_commit(f, partial=True)
        f.write(f"{CTRL}/HealingOrchestrator.scala", MODULE_NARROW)
        f.commit("narrow and move\n\n# moved: heal\n")
        out = self.bad(f, "step6")
        self.assertIn("both removes the last impl-class mention", out)
        self.assertIn("FR-016b", out)

    def test_unlisted_removed_member_fails(self):
        f = Fixture()
        move_commit(f, symbols="heal", partial=True)
        out = self.bad(f, "step2")
        self.assertIn("count", out)

    def test_move_commit_adding_logic_fails(self):
        f = Fixture()
        move_commit(f, module=MODULE_MOVE + "\n  def sneaky(): Int = 42\n", partial=True)
        out = self.bad(f, "step1")
        self.assertIn("sneaky", out)

    def test_extra_tokens_in_test_hunks_fail(self):
        for tok in ("x shouldEqual 1", "x mustBe 1", "awaitAssert { }", "fishForSpecificMessage() { }", "Thread.sleep(5)", "a != b", "x shouldNot be(1)", "receiveOne(1.second)"):
            f = Fixture()
            f.write(TEST, SPEC_BASE.replace("val fixture", f"{tok}\n  val fixture"))
            f.commit("tok")
            self.bad(f, "step3")

    def test_narrowing_changed_case_guard_fails(self):
        f = Fixture()
        mod = MODULE_MOVE.replace("    println(tmp)", "    tmp match\n      case 1 if a > 0 =>\n        println(tmp)\n      case _ =>\n        ()")
        move_commit(f, module=mod, partial=True)
        narrow = MODULE_NARROW.replace("    println(tmp)", "    tmp match\n      case 1 if a > 5 =>\n        println(tmp)\n      case _ =>\n        ()")
        f.write(f"{CTRL}/HealingOrchestrator.scala", narrow)
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST)
        f.commit(NARROW_MSG)
        out = self.bad(f, "step6")
        self.assertIn("case 1 if", out)

    def test_merge_commit_on_spec_branch_fails_but_not_elsewhere(self):
        for branch, good in (("refactor/snap-016-m2", False), ("ci/x", True)):
            f = Fixture(branch=branch)
            f.git("checkout", "-q", "-b", "side", "base")
            f.write("docs/s.md", "s\n")
            f.commit("side")
            f.git("checkout", "-q", branch)
            f.write("docs/b.md", "b\n")
            f.commit("b")
            f.git("merge", "-q", "--no-ff", "-m", "merge side", "side")
            if good:
                self.ok(f)
            else:
                self.bad(f, "merge")

    def test_module_change_on_nonconforming_branch_fails(self):
        f = Fixture(branch="feature/heal")
        f.write(f"{CTRL}/M.scala", "package x\n\ntrait M:\n  def a = 1\n")
        f.commit("add")
        self.bad(f, "branch")

    def test_snap_test_change_on_nonconforming_branch_fails_only_when_marked(self):
        f = Fixture(branch="feature/heal")
        f.write(TEST, SPEC_BASE.replace("1 shouldBe 1", "1 shouldBe 2"))
        f.commit("fix heal")
        self.ok(f)
        g = Fixture(branch="feature/heal")
        g.write(TEST, SPEC_BASE.replace("1 shouldBe 1", "1 shouldBe 2"))
        g.commit("pins (#1401)")
        self.bad(g, "branch")

    def test_final_state_requires_narrowing_or_partial_marker(self):
        f = Fixture()
        move_commit(f)
        out = self.bad(f, "final")
        self.assertIn("still mentions the impl class", out)
        g = Fixture()
        move_commit(g, partial=True)
        self.ok(g)

    def test_stray_impl_definition_in_module_dir_fails(self):
        f = Fixture()
        f.write(f"{CTRL}/Defs.scala", "package x\n\nclass SNAPSyncControllerImpl:\n  def a = 1\n")
        f.commit("defs")
        self.bad(f, "FR-016a")

    def test_partial_needs_reason_and_last_commit(self):
        f = Fixture()
        move_commit(f)
        f.git("commit", "-q", "--amend", "-m", "move\n\n# moved: heal count\n# partial:\n")
        self.bad(f, "final")
        g = Fixture()
        move_commit(g, partial=True)
        g.write("docs/x.md", "x\n")
        g.commit("later")
        out = self.bad(g, "final")
        self.assertIn("only on the PR's last commit", out)

    def test_test_branch_touching_seam_file_now_fails(self):
        f = Fixture(branch="test/snap-016-s0d")
        f.write(CORE, CORE_BASE + "\n// seam\n")
        f.commit("test(snap): seam\n\n# test-files: none\n")
        self.bad(f, "step3")

    def test_real_allow_list_is_empty_and_pins_protected(self):
        d = Path(__file__).resolve().parents[1]
        live = [l for l in (d / "test-branch-main-allow.txt").read_text().splitlines() if l.strip() and not l.startswith("#")]
        self.assertEqual(live, [])
        prot = (d / "protected-paths.txt").read_text()
        for n in ("SNAPSyncControllerPinSpec", "ChildFactoriesSpec", "SnapFrozenFormatsGoldenSpec"):
            self.assertIn(n, prot)

    def test_protected_nmp_spec_pin_listed(self):
        text = (Path(__file__).resolve().parents[1] / "protected-paths.txt").read_text()
        self.assertIn("NetworkPeerManagerSpec.scala", text)


    # ---- compile hook and cheapness
    def test_compile_cmd_runs_on_move_commit_and_failure_fails(self):
        f = Fixture()
        move_commit(f, partial=True)
        self.ok(f, "--compile", "--compile-cmd", "test -f src/main/scala/com/chipprbots/ethereum/blockchain/sync/snap/controller/HealingOrchestrator.scala")
        self.bad(f, "compile", "--compile", "--compile-cmd", "false")

    def test_compile_not_run_without_move_commit(self):
        f = Fixture()
        f.write("README.md", "x\n")
        f.commit("docs")
        self.ok(f, "--compile", "--compile-cmd", "false")

    def test_unrelated_commit_passes(self):
        f = Fixture(branch="docs/x")
        f.write("docs/a.md", "x\n")
        f.commit("docs")
        self.ok(f)


if __name__ == "__main__":
    unittest.main(verbosity=1)
