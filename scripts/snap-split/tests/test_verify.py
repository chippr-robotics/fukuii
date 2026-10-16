#!/usr/bin/env python3
"""Fixture-based self-test for scripts/snap-split/verify.sh (spec 016, S0f).

Each case builds a throwaway git repository with the real snap/ directory layout, commits a base and
the commits under test, then runs verify.sh on base..HEAD and asserts pass/fail (and which check).

    python3 scripts/snap-split/tests/test_verify.py
"""

import json
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


COMPANION_BASE = """package x

object SNAPSyncController:

  /** Doc of a pure helper. */
  private[snap] def pure(a: Int): Int =
    a + 1

  private[snap] val Limit: Long = 3L

  def keep(): Int = 2
"""

COMPANION_AFTER_MOVE = """package x

object SNAPSyncController:

  // re-exported: call sites stay unchanged
  export controller.FooPolicy.{Limit, pure}

  def keep(): Int = 2
"""

POLICY = """package x.controller

/** Pure decisions. */
private[snap] object FooPolicy:

  /** Doc of a pure helper. */
  private[snap] def pure(a: Int): Int =
    a + 1

  private[snap] val Limit: Long = 3L
"""


_FIXTURES = []


def tearDownModule():
    for t in _FIXTURES:
        t.cleanup()


class Fixture:
    def __init__(self, branch="refactor/snap-016-m2", core_base=CORE_BASE, extra=None):
        self.tmp = tempfile.TemporaryDirectory()
        _FIXTURES.append(self.tmp)
        self.d = Path(self.tmp.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@t")
        self.git("config", "user.name", "t")
        self.write(CORE, core_base)
        self.write(TEST, SPEC_BASE)
        self.write("src/main/resources/application.conf", "a = 1\n")
        for path, text in (extra or {}).items():
            self.write(path, text)
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


EXT_CASE = '  "other" should "be 2" taggedAs UnitTest in {\n    s.other() shouldBe 2\n  }\n'

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

    def test_d_abstract_val_passes(self):
        self.ok(self.d_case("  val a: Int\n  val b: Map[String, Int]\n"))

    def test_d_member_val_with_type_fails(self):
        self.bad(self.d_case("  val a: Int = 1\n"), "FR-016d")
        self.bad(self.d_case("  val a: Int =\n    compute()\n"), "FR-016d")

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

    # ---- a later slice moves more code into an already-narrowed module (spec 016 M8c)
    def second_move(self, stub=STUB_TEST):
        """M2-style move + narrowing, then a second move of `other` into a temporary trait of the same file, whose
        self-type is the impl, appended after the narrowed trait so the narrowing only drops its two header lines."""
        f = Fixture()
        move_commit(f)
        f.write(f"{CTRL}/HealingOrchestrator.scala", MODULE_NARROW)
        f.write("src/test/scala/x/StubSpec.scala", stub)
        f.commit(NARROW_MSG)
        f.write(f"{CTRL}/HealingOrchestrator.scala", MODULE_NARROW + "\nprivate[snap] trait HealingMore:\n  self: SNAPSyncControllerImpl =>\n\n  def other(): Int = 2\n")
        f.write(CORE, CORE_AFTER_MOVE.replace("extends HealingOrchestrator:", "extends HealingOrchestrator with HealingMore:").replace("\n  def other(): Int = 2\n", ""))
        f.commit("refactor(snap): move other (#1401)\n\n# moved: other\n")
        f.write(f"{CTRL}/HealingOrchestrator.scala", MODULE_NARROW + "\n  def other(): Int = 2\n")
        f.write(CORE, CORE_AFTER_MOVE.replace("\n  def other(): Int = 2\n", ""))
        return f

    def ext_bad(self, addition, check="step3"):
        f = self.second_move()
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST + EXT_CASE + addition)
        f.commit(self.EXT_MSG)
        return self.bad(f, check)

    def test_extended_suite_rejects_skip_and_scheduling_tokens(self):
        for addition in (
            '  "skipped" should "x" ignore {\n    1 shouldBe 1\n  }\n',
            '  "later" should "x" in pending\n',
            '  "slow" should "x" in {\n    Thread.sleep(10)\n  }\n',
            '  "slow" should "x" taggedAs SlowTest in {\n    1 shouldBe 1\n  }\n',
            '  "waits" should "x" in {\n    eventually { 1 shouldBe 1 }\n  }\n',
        ):
            with self.subTest(addition=addition):
                out = self.ext_bad(addition)
                self.assertIn("skip/scheduling token", out)

    def test_extended_suite_rejects_assertion_inside_an_existing_case(self):
        f = self.second_move(stub=STUB_TEST + '  "old" should "work" taggedAs UnitTest in {\n    s.shared shouldBe 1\n  }\n')
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST + '  "old" should "work" taggedAs UnitTest in {\n    s.shared shouldBe 1\n    s.other() shouldBe 2\n  }\n')
        f.commit(self.EXT_MSG)
        out = self.bad(f, "step3")
        self.assertIn("outside a new case", out)

    def test_extended_tests_trailer_on_a_non_stub_suite_fails(self):
        f = self.second_move()
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST + EXT_CASE)
        f.write(TEST, SPEC_BASE + '  "bar" should "x" in {\n    2 shouldBe 2\n  }\n')
        f.commit(self.EXT_MSG.replace("StubSpec.scala", f"StubSpec.scala {TEST}"))
        out = self.bad(f, "step3")
        self.assertIn("not a module stub suite", out)

    EXT_MSG = "refactor(snap): narrow other (#1401)\n\n# extended-tests: src/test/scala/x/StubSpec.scala\n"

    def test_second_move_narrowed_with_extended_suite_passes(self):
        f = self.second_move()
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST + EXT_CASE)
        f.commit(self.EXT_MSG)
        self.ok(f)

    def test_extended_suite_without_trailer_fails(self):
        f = self.second_move()
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST + EXT_CASE)
        f.commit("refactor(snap): narrow other (#1401)")
        out = self.bad(f, "FR-016b")
        self.assertIn("[step3]", out)

    def test_extended_suite_without_new_assertion_fails(self):
        f = self.second_move()
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST + "  val t = 1\n")
        f.commit(self.EXT_MSG)
        self.bad(f, "FR-016b")

    def test_extended_suite_removing_an_assertion_fails(self):
        f = self.second_move(stub=STUB_TEST + "  1 shouldBe 1\n")
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST + EXT_CASE)
        f.commit(self.EXT_MSG)
        out = self.bad(f, "step3")
        self.assertIn("- line with an assertion", out)

    def test_extended_tests_trailer_on_non_narrowing_commit_fails(self):
        f = Fixture()
        f.write(TEST, SPEC_BASE.replace("val fixture", "\"bar\" should \"x\" in { 2 shouldBe 2 }\n  val fixture"))
        f.commit(f"refactor(snap): extend\n\n# extended-tests: {TEST}\n")
        out = self.bad(f, "step3")
        self.assertIn("only on a narrowing commit", out)

    def test_extended_tests_trailer_naming_an_unmodified_file_fails(self):
        f = self.second_move()
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST + EXT_CASE)
        f.commit(self.EXT_MSG.replace("StubSpec.scala", "StubSpec.scala src/test/scala/x/Other.scala"))
        out = self.bad(f, "step3")
        self.assertIn("does not modify", out)

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

    # ---- plan.md D2: companion helpers move into a policy object and are re-exported
    def companion_move(self, core=COMPANION_AFTER_MOVE, symbols="pure Limit"):
        f = Fixture(branch="refactor/snap-016-m1", core_base=COMPANION_BASE)
        f.write(f"{CTRL}/FooPolicy.scala", POLICY)
        f.write(CORE, core)
        f.commit(f"refactor(snap): move helpers (#1401)\n\n# moved: {symbols}\n")
        return f

    def test_companion_move_with_export_of_moved_symbols_passes(self):
        self.ok(self.companion_move())

    def test_companion_move_single_name_exports_pass(self):
        core = COMPANION_AFTER_MOVE.replace(
            "  export controller.FooPolicy.{Limit, pure}\n",
            "  export controller.FooPolicy.pure\n  export controller.FooPolicy.Limit\n",
        )
        self.ok(self.companion_move(core=core))

    def test_export_of_unmoved_symbol_fails(self):
        core = COMPANION_AFTER_MOVE.replace("{Limit, pure}", "{Limit, pure, other}")
        out = self.bad(self.companion_move(core=core), "step1")
        self.assertIn("export of `other`", out)

    def test_export_with_wrong_object_prefix_fails(self):
        # `pure` and `Limit` are moved names, but these clauses would forward to a different object
        for prefix in ("controller.BarPolicy", "controller.SNAPSyncController", "other.FooPolicy", "FooPolicy"):
            core = COMPANION_AFTER_MOVE.replace("controller.FooPolicy", prefix)
            out = self.bad(self.companion_move(core=core), "step1")
            self.assertIn("now lives in: x.controller.FooPolicy", out)

    def test_export_with_correct_object_prefix_passes(self):
        for prefix in ("controller.FooPolicy", "x.controller.FooPolicy"):
            core = COMPANION_AFTER_MOVE.replace("controller.FooPolicy", prefix)
            self.ok(self.companion_move(core=core))

    def test_narrowing_commit_with_export_line_fails(self):
        f = Fixture()
        move_commit(f)
        f.write(f"{CTRL}/HealingOrchestrator.scala", MODULE_NARROW.replace("  private var count = 0\n", "  private var count = 0\n  export controller.FooPolicy.pure\n"))
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST)
        f.commit(NARROW_MSG)
        out = self.bad(f, "step6")
        self.assertIn("non-signature line", out)
        self.assertIn("export controller.FooPolicy.pure", out)

    def test_wildcard_rename_and_multiline_exports_fail(self):
        for clause in (
            "export controller.FooPolicy.*",
            "export controller.FooPolicy.{Limit, pure => p}",
            "export controller.FooPolicy.{\n    Limit,\n    pure\n  }",
        ):
            core = COMPANION_AFTER_MOVE.replace("export controller.FooPolicy.{Limit, pure}", clause)
            out = self.bad(self.companion_move(core=core), "step1")
            self.assertIn("added line is neither moved", out)

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

    # A core whose constructor is longer than the 12-line header window: its `extends`/`with` clause sits past it.
    LONG_CTOR = "class SNAPSyncControllerImpl(\n" + "".join(f"    p{n}: Int,\n" for n in range(20)) + ")"

    def long_ctor_series(self, core_narrow_extends, param_edit=None):
        base = CORE_BASE.replace("class SNAPSyncControllerImpl:", self.LONG_CTOR + "\n    extends Base:")
        f = Fixture(core_base=base)
        after_move = CORE_AFTER_MOVE.replace(
            "class SNAPSyncControllerImpl extends HealingOrchestrator:",
            self.LONG_CTOR + "\n    extends Base\n    with HealingOrchestrator:",
        )
        move_commit(f, core=after_move)
        narrowed = after_move.replace("    with HealingOrchestrator:", core_narrow_extends)
        if param_edit:
            narrowed = narrowed.replace(*param_edit)
        f.write(CORE, narrowed)
        f.write(f"{CTRL}/HealingOrchestrator.scala", MODULE_NARROW)
        f.write("src/test/scala/x/StubSpec.scala", STUB_TEST)
        f.commit(NARROW_MSG)
        return f

    def test_narrowing_extends_clause_after_long_constructor_passes(self):
        f = self.long_ctor_series("    with HealingOrchestrator\n    with HealingState\n    with SnapSharedState:")
        self.ok(f)

    def test_narrowing_param_edit_in_long_constructor_fails(self):
        f = self.long_ctor_series(
            "    with HealingOrchestrator\n    with HealingState:", param_edit=("    p15: Int,", "    p15: Long,")
        )
        out = self.bad(f, "step6")
        self.assertIn("p15", out)

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

    def test_fix_branch_may_change_module_file(self):
        for branch in ("fix/snap-bug", "hotfix/snap-bug"):
            f = Fixture(branch=branch)
            f.write(f"{CTRL}/M.scala", "package x\n\ntrait M:\n  def a = 1\n")
            f.commit("seed")
            f.git("tag", "-f", "base")
            f.write(f"{CTRL}/M.scala", "package x\n\ntrait M:\n  def a = 2\n")
            f.commit("fix: change body, not a move")
            out = self.ok(f)
            self.assertIn("(fix branch)", out)
            self.assertIn("[counts]", out)

    def test_fix_branch_impl_mention_in_module_fails(self):
        f = Fixture(branch="fix/snap-bug")
        f.write(f"{CTRL}/M.scala", "package x\n\ntrait M:\n  self: SNAPSyncControllerImpl =>\n  def a = 1\n")
        f.commit("fix: reaches into the impl")
        self.bad(f, "FR-016a")

    def test_fix_branch_moved_trailer_fails(self):
        f = Fixture(branch="fix/snap-bug")
        move_commit(f)
        out = self.bad(f, "fix-branch")
        self.assertNotIn("[step1]", out)

    def test_fix_branch_still_gets_member_rules(self):
        f = Fixture(branch="fix/snap-bug")
        f.write(f"{CTRL}/M.scala", "package x\n\ntrait M:\n  def a: Int = 1\n  def b: Int\n  val c = new Object\n")
        f.commit("fix: state in a module trait")
        self.bad(f, "FR-016d")

    def test_fix_branch_narrowing_fails(self):
        f = Fixture(branch="fix/snap-bug")
        f.write(f"{CTRL}/M.scala", "package x\n\ntrait M:\n  self: SNAPSyncControllerImpl =>\n  def a = 1\n")
        f.git("add", "-A")
        f.git("commit", "-q", "-m", "seed", "--no-verify")
        f.git("tag", "-f", "base")
        f.write(f"{CTRL}/M.scala", "package x\n\ntrait M:\n  def a = 1\n")
        f.commit("fix: drops the last impl mention")
        self.bad(f, "fix-branch")

    def test_fix_branch_adding_or_deleting_module_file_fails(self):
        f = Fixture(branch="fix/snap-bug")
        f.write(f"{CTRL}/New.scala", "package x\n\ntrait New:\n  def a = 1\n")
        f.commit("fix: new module")
        out = self.bad(f, "fix-branch")
        self.assertIn("adds module file", out)
        g = Fixture(branch="fix/snap-bug")
        g.write(f"{CTRL}/Old.scala", "package x\n\ntrait Old:\n  def a = 1\n")
        g.commit("seed")
        g.git("tag", "-f", "base")
        g.git("rm", "-q", f"{CTRL}/Old.scala")
        g.commit("fix: delete module")
        self.assertIn("deletes module file", self.bad(g, "fix-branch"))

    def test_fix_branch_large_net_deletion_needs_fix_trailer(self):
        body = "package x\n\ntrait Big:\n" + "".join(f"  def m{i}: Int = {i}\n" for i in range(200))
        f = Fixture(branch="fix/snap-bug")
        f.write(f"{CTRL}/Big.scala", body)
        f.commit("seed")
        f.git("tag", "-f", "base")
        f.write(f"{CTRL}/Big.scala", "package x\n\ntrait Big:\n  def m0: Int = 0\n")
        f.commit("fix: shrink")
        self.assertIn("net deletion", self.bad(f, "fix-branch"))
        g = Fixture(branch="fix/snap-bug")
        g.write(f"{CTRL}/Big.scala", body)
        g.commit("seed")
        g.git("tag", "-f", "base")
        g.write(f"{CTRL}/Big.scala", "package x\n\ntrait Big:\n  def m0: Int = 0\n")
        g.commit("fix: shrink (#1555)\n\n# fix: #1555\n")
        self.ok(g)

    def test_fix_named_like_a_split_slice_is_kind_other(self):
        f = Fixture(branch="fix/snap-016-m9")
        f.write(f"{CTRL}/M.scala", "package x\n\ntrait M:\n  def a = 1\n")
        f.commit("add")
        self.bad(f, "branch")

    def test_fix_branch_pins_need_trailer_and_must_be_modified(self):
        pin = "src/test/scala/golden/pin.txt"
        f = Fixture(branch="fix/snap-bug")
        f.write(pin, "1\n")
        f.commit("seed")
        f.git("tag", "-f", "base")
        f.write(pin, "2\n")
        f.commit("fix: update pin")
        self.bad(f, "step4")
        g = Fixture(branch="fix/snap-bug")
        g.write(pin, "1\n")
        g.commit("seed")
        g.git("tag", "-f", "base")
        g.write(pin, "2\n")
        g.commit(f"fix: update pin\n\n# pins: {pin}\n")
        self.ok(g)
        h = Fixture(branch="fix/snap-bug")
        h.write("docs/x.md", "x\n")
        h.commit(f"fix: nothing\n\n# pins: {pin}\n")
        self.assertIn("does not modify", self.bad(h, "step4"))

    def test_fix_branch_resources_change_fails(self):
        f = Fixture(branch="fix/snap-bug")
        f.write("src/main/resources/application.conf", "a = 2\n")
        f.commit("fix: conf")
        self.bad(f, "step4")

    def test_fix_branch_merge_commit_fails(self):
        f = Fixture(branch="fix/snap-bug")
        f.git("checkout", "-q", "-b", "side", "base")
        f.write("docs/s.md", "s\n")
        f.commit("side")
        f.git("checkout", "-q", "fix/snap-bug")
        f.write("docs/b.md", "b\n")
        f.commit("b")
        f.git("merge", "-q", "--no-ff", "-m", "merge side", "side")
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

    def test_compile_checkout_is_a_full_repository_at_the_move_commit(self):
        # sbt-git (JGit) cannot load a linked worktree, whose `.git` is a file: the checkout needs a `.git` directory
        f = self.companion_move()
        f.write("README.md", "after the move\n")
        f.commit("docs")
        self.ok(f, "--compile", "--compile-cmd", "test -d .git && test ! -e README.md && git rev-parse HEAD")

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


# ---- step 8: storage ratchet (env freeze, 2026-10-11 decision)

ENV = f"{CTRL}/SnapControllerEnv.scala"
COORD = f"{SNAP}/actors/FooCoordinator.scala"
BASELINE = "scripts/snap-split/storage-baseline.json"

ENV_BASE = """package x.controller

private[snap] trait SnapControllerEnv:

  val ctx: Ctx
  def syncConfig: SyncConfig

  /** The state storage (a comment naming AppStateStorage does not count). */
  def stateStorage: StateStorage
  def validatorFactory: MptStorage => StateValidator
  def pathNodeStorageOpt: Option[PathNodeStorage]
"""

COORD_BASE = """package x.actors

import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.storage.FlatSlotStorage

class FooCoordinator(flatSlotStorage: FlatSlotStorage):
  // a comment naming WriteBatch and appStateStorage does not count
  def f(b: DataSourceBatchUpdate) = b.commit()
"""

ENV_REFS, COORD_REFS = 5, 7  # see STORAGE_REF_RE: comments excluded; `dataSource` in the import path counts


def baseline(env_members=5, pinned=("stateStorage", "validatorFactory", "pathNodeStorageOpt"), refs=None, raw=None, just=None,
             header=":", total=None):
    refs = refs if refs is not None else {"controller/SnapControllerEnv.scala": ENV_REFS, "actors/FooCoordinator.scala": COORD_REFS}
    return json.dumps({
        "env": {"members": env_members, "header": header, "storage_members": list(pinned)},
        "storage_refs_total": total if total is not None else sum(refs.values()),
        "storage_refs": refs,
        "raw_imports": raw if raw is not None else {"actors/FooCoordinator.scala": ["com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate"]},
        "justifications": just or {},
    }, indent=2) + "\n"


def ratchet_fixture(branch="refactor/snap-016-r", with_baseline=True):
    extra = {ENV: ENV_BASE, COORD: COORD_BASE}
    if with_baseline:
        extra[BASELINE] = baseline()
    return Fixture(branch=branch, extra=extra)


class StorageRatchetTests(unittest.TestCase):
    ok = VerifyTests.ok
    bad = VerifyTests.bad

    def env_change(self, f, text, msg="refactor(snap): env", **bl):
        f.write(ENV, text)
        if bl:
            f.write(BASELINE, baseline(**bl))
        f.commit(msg)

    def test_unchanged_baseline_passes_and_prints_table(self):
        f = ratchet_fixture()
        f.write("README.md", "x\n")
        f.commit("docs")
        out = self.ok(f)
        self.assertIn(f"[storage] actors/FooCoordinator.scala: {COORD_REFS} (baseline {COORD_REFS})", out)
        self.assertIn("[storage] SnapControllerEnv: members=5 (baseline 5), storage-typed=3 (pinned 3)", out)

    # Rule 1: env freeze
    def test_new_storage_member_fails_even_with_baseline_raised_and_justified(self):
        f = ratchet_fixture()
        new = ENV_BASE + "  def evmCodeStorage: EvmCodeStorage\n"
        self.env_change(f, new, env_members=6, refs={"controller/SnapControllerEnv.scala": ENV_REFS + 2, "actors/FooCoordinator.scala": COORD_REFS},
                        just={"env.members": "needed", "controller/SnapControllerEnv.scala": "needed"})
        out = self.bad(f, "env-freeze")
        self.assertIn("storage-typed member `evmCodeStorage", out)

    def test_new_storage_type_nested_in_function_type_fails(self):
        f = ratchet_fixture()
        self.env_change(f, ENV_BASE + "  def batcher: Int => Option[WriteBatch]\n", env_members=6,
                        refs={"controller/SnapControllerEnv.scala": ENV_REFS + 1, "actors/FooCoordinator.scala": COORD_REFS},
                        just={"env.members": "x", "controller/SnapControllerEnv.scala": "x"})
        out = self.bad(f, "env-freeze")
        self.assertIn("`batcher", out)

    def test_retyping_a_pinned_member_into_another_storage_type_passes(self):
        # Decision: pinned by name. A member that already was a storage handle stays one; StateIngest removes it.
        f = ratchet_fixture()
        self.env_change(f, ENV_BASE.replace("def stateStorage: StateStorage", "def stateStorage: MptStorage"))
        self.ok(f)

    def test_retyping_a_non_storage_member_into_a_storage_type_fails(self):
        f = ratchet_fixture()
        self.env_change(f, ENV_BASE.replace("def syncConfig: SyncConfig", "def syncConfig: Option[BlockchainReader]"))
        out = self.bad(f, "env-freeze")
        self.assertIn("`syncConfig", out)

    def test_removing_a_pinned_member_outside_a_capability_pr_fails(self):
        # T054b changed this on purpose: it passed before (with a WARN). Removing a pinned storage handle from the env is
        # what a capability PR does; outside one, its call sites would move with no substitution check.
        f = ratchet_fixture()
        self.env_change(f, ENV_BASE.replace("  def stateStorage: StateStorage\n", ""), env_members=4,
                        refs={"controller/SnapControllerEnv.scala": ENV_REFS - 2, "actors/FooCoordinator.scala": COORD_REFS})
        out = self.bad(f, "capability")
        self.assertIn("removes pinned storage handle `stateStorage`", out)
        self.assertNotIn("[env-freeze]", out)
        self.assertIn("WARN pinned env member `stateStorage` is no longer storage-typed", out)

    def test_removing_a_member_without_lowering_the_baseline_fails(self):
        f = ratchet_fixture()
        self.env_change(f, ENV_BASE.replace("  def stateStorage: StateStorage\n", ""))
        out = self.bad(f, "env-freeze")
        self.assertIn("member count: measured 4, but", out)
        self.assertIn("[storage-refs]", out)

    def test_new_non_storage_member_fails_even_with_baseline_raised_and_justified(self):
        # "only goes down": no justification escape for env.members
        f = ratchet_fixture()
        self.env_change(f, ENV_BASE + "  def timeoutMs: Long\n", env_members=6, just={"env.members": "timeout for X, see #1"})
        out = self.bad(f, "env-freeze")
        self.assertIn("raises env.members from 5 to 6", out)
        self.assertIn("above the ceiling 5", out)

    def test_retyping_a_non_storage_member_keeps_the_count_and_passes(self):
        f = ratchet_fixture()
        self.env_change(f, ENV_BASE.replace("def syncConfig: SyncConfig", "def syncConfig: Long"))
        self.ok(f)

    def test_env_member_count_up_without_justification_fails(self):
        f = ratchet_fixture()
        self.env_change(f, ENV_BASE + "  def timeoutMs: Long\n", env_members=6)
        self.bad(f, "env-freeze")
        g = ratchet_fixture()
        self.env_change(g, ENV_BASE + "  def timeoutMs: Long\n")
        out = self.bad(g, "env-freeze")
        self.assertIn("above the ceiling 5", out)

    def test_baseline_cannot_grow_the_pinned_list(self):
        f = ratchet_fixture()
        f.write(BASELINE, baseline(pinned=("stateStorage", "validatorFactory", "pathNodeStorageOpt", "syncConfig")))
        f.commit("chore: pin")
        out = self.bad(f, "env-freeze")
        self.assertIn("adds `syncConfig` to env.storage_members", out)

    # per-file storage-reference counts
    def test_count_increase_fails(self):
        f = ratchet_fixture(branch="feat/coord")
        f.write(COORD, COORD_BASE + "  def g(s: StateStorage) = s\n")
        f.commit("feat: coordinator")
        out = self.bad(f, "storage-refs")
        self.assertIn(f"actors/FooCoordinator.scala: measured {COORD_REFS + 1}, but {BASELINE} records {COORD_REFS}", out)
        self.assertIn(f"total storage references {ENV_REFS + COORD_REFS + 1}, above the ceiling {ENV_REFS + COORD_REFS}", out)

    def test_count_increase_with_baseline_raised_and_stale_justification_fails(self):
        f = Fixture(branch="feat/coord", extra={ENV: ENV_BASE, COORD: COORD_BASE,
                                                 BASELINE: baseline(just={"storage_refs.total": "old reason"})})
        f.write(COORD, COORD_BASE + "  def g(s: StateStorage) = s\n")
        f.write(BASELINE, baseline(refs={"controller/SnapControllerEnv.scala": ENV_REFS, "actors/FooCoordinator.scala": COORD_REFS + 1},
                                   just={"storage_refs.total": "old reason"}))
        f.commit("feat: coordinator")
        out = self.bad(f, "storage-refs")
        self.assertIn("needs a new `justifications[\"storage_refs.total\"]` line", out)

    def test_count_decrease_with_lowered_baseline_passes(self):
        f = ratchet_fixture(branch="feat/coord")
        f.write(COORD, COORD_BASE.replace(" = b.commit()", " = b"))
        f.write(BASELINE, baseline(refs={"controller/SnapControllerEnv.scala": ENV_REFS, "actors/FooCoordinator.scala": COORD_REFS - 1}))
        f.commit("feat: coordinator")
        self.ok(f)

    def test_count_decrease_without_lowering_the_baseline_fails(self):
        f = ratchet_fixture(branch="feat/coord")
        f.write(COORD, COORD_BASE.replace(" = b.commit()", " = b"))
        f.commit("feat: coordinator")
        out = self.bad(f, "storage-refs")
        self.assertIn(f"set storage_refs[\"actors/FooCoordinator.scala\"] to {COORD_REFS - 1} in this PR (the ratchet only goes down", out)

    def test_comment_only_edit_does_not_move_the_count(self):
        f = ratchet_fixture(branch="feat/coord")
        f.write(COORD, COORD_BASE + "  // StateStorage, dataSource.commit() and WriteBatch in a comment\n")
        f.commit("docs: comment")
        self.ok(f)

    def test_new_file_with_storage_refs_fails(self):
        f = ratchet_fixture(branch="feat/coord")
        f.write(f"{SNAP}/actors/BarCoordinator.scala", "package x\nclass Bar(s: EvmCodeStorage)\n")
        f.commit("feat: bar")
        out = self.bad(f, "storage-refs")
        self.assertIn(f"actors/BarCoordinator.scala: measured 1, but {BASELINE} records 0", out)
        self.assertIn("above the ceiling", out)

    def test_new_file_added_to_baseline_with_justification_passes(self):
        refs = {"controller/SnapControllerEnv.scala": ENV_REFS, "actors/FooCoordinator.scala": COORD_REFS, "actors/BarCoordinator.scala": 1}
        f = ratchet_fixture(branch="feat/coord")
        f.write(f"{SNAP}/actors/BarCoordinator.scala", "package x\nclass Bar(s: EvmCodeStorage)\n")
        f.write(BASELINE, baseline(refs=refs))
        f.commit("feat: bar")
        self.bad(f, "storage-refs")  # no justification
        f.write(BASELINE, baseline(refs=refs, just={"actors/BarCoordinator.scala": "a per-file line does not license a higher total"}))
        f.commit("chore: justify the file")
        self.bad(f, "storage-refs")
        f.write(BASELINE, baseline(refs=refs, just={"storage_refs.total": "Bar talks to EvmCodeStorage until StateIngest, see #1"}))
        f.commit("chore: justify")
        self.ok(f)

    def test_deleted_file_must_drop_its_entry(self):
        f = ratchet_fixture(branch="feat/coord")
        f.git("rm", "-q", COORD)
        f.commit("feat: drop foo")
        out = self.bad(f, "storage-refs")
        self.assertIn("(remove the entry)", out)

    def test_deleting_the_baseline_fails_and_bootstrap_passes(self):
        f = ratchet_fixture()
        f.git("rm", "-q", BASELINE)
        f.commit("chore: drop")
        self.bad(f, "storage-refs")
        g = ratchet_fixture(with_baseline=False)
        g.write(BASELINE, baseline())
        g.commit("chore: add baseline")
        out = self.ok(g)
        self.assertIn("is new in this range", out)

    # Rule 2: raw storage imports
    def test_raw_import_in_controller_fails(self):
        f = ratchet_fixture()
        f.write(f"{CTRL}/FooPolicy.scala", "package x.controller\n\nimport org.rocksdb.RocksDB\n\nprivate[snap] object FooPolicy:\n  def a = 1\n")
        f.commit("refactor(snap): policy")
        out = self.bad(f, "raw-import")
        self.assertIn("controller/FooPolicy.scala: raw storage import `org.rocksdb.RocksDB`", out)

    def test_raw_import_in_actors_fails(self):
        f = ratchet_fixture(branch="feat/coord")
        f.write(f"{SNAP}/actors/BarCoordinator.scala",
                "package x\n\nimport com.chipprbots.ethereum.db.dataSource.{DataSource, DataUpdate as DU}\n\nclass Bar\n")
        f.write(BASELINE, baseline(refs={"controller/SnapControllerEnv.scala": ENV_REFS, "actors/FooCoordinator.scala": COORD_REFS,
                                         "actors/BarCoordinator.scala": 3}, just={"actors/BarCoordinator.scala": "x"}))
        f.commit("feat: bar")
        out = self.bad(f, "raw-import")
        self.assertIn("`com.chipprbots.ethereum.db.dataSource.DataSource`", out)
        self.assertIn("`com.chipprbots.ethereum.db.dataSource.DataUpdate`", out)

    def test_write_batch_and_inline_fqn_are_raw(self):
        f = ratchet_fixture(branch="feat/coord")
        f.write(f"{SNAP}/actors/BarCoordinator.scala",
                "package x\n\nimport com.chipprbots.ethereum.db.storage.WriteBatchHelper\n\nclass Bar:\n  def b = new org.rocksdb.Options()\n")
        f.commit("feat: bar")
        out = self.bad(f, "raw-import")
        self.assertIn("WriteBatchHelper`", out)
        self.assertIn("`inline org.rocksdb.Options`", out)

    def test_baselined_file_keeps_its_imports_but_cannot_add(self):
        f = ratchet_fixture(branch="feat/coord")
        f.write(COORD, COORD_BASE.replace("import com.chipprbots.ethereum.db.storage.FlatSlotStorage\n",
                                          "import com.chipprbots.ethereum.db.storage.FlatSlotStorage\nimport x.Other\n"))
        f.commit("feat: keep")
        self.ok(f)
        f.write(COORD, COORD_BASE.replace("dataSource.DataSourceBatchUpdate", "dataSource.{DataSourceBatchUpdate, DataUpdate}"))
        f.write(BASELINE, baseline(refs={"controller/SnapControllerEnv.scala": ENV_REFS, "actors/FooCoordinator.scala": COORD_REFS}))
        f.commit("feat: add")
        out = self.bad(f, "raw-import")
        self.assertIn("raw storage import `com.chipprbots.ethereum.db.dataSource.DataUpdate`", out)

    def test_baseline_cannot_grow_raw_imports(self):
        f = ratchet_fixture(branch="feat/coord")
        f.write(BASELINE, baseline(raw={"actors/FooCoordinator.scala": ["com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate",
                                                                        "org.rocksdb.WriteBatch"]}))
        f.commit("chore: widen")
        self.bad(f, "raw-import")

    def test_domain_storage_import_is_not_raw(self):
        f = ratchet_fixture(branch="feat/coord")
        f.write(f"{SNAP}/actors/BarCoordinator.scala", "package x\n\nimport com.chipprbots.ethereum.db.storage.RocksDbBfsQueueStorage\n\nclass Bar\n")
        f.commit("feat: bar")
        out = self.bad(f, "storage-refs")
        self.assertNotIn("[raw-import]", out)

    def test_coordinator_only_pr_runs_no_move_checks(self):
        # actors/*.scala is not a module dir: a coordinator PR on any branch gets the ratchet, not the move checks
        f = ratchet_fixture(branch="feat/coord")
        f.write(COORD, COORD_BASE + "  def h = 1\n")
        f.commit("feat: coordinator")
        out = self.ok(f)
        self.assertNotIn("[branch]", out)

    # move-aware total
    def test_refs_moving_into_a_new_file_pass_without_justification(self):
        f = ratchet_fixture(branch="refactor/snap-016-c1")
        f.write(COORD, COORD_BASE.replace("  def f(b: DataSourceBatchUpdate) = b.commit()\n", ""))
        f.write(f"{SNAP}/actors/storage/SlotWriter.scala", "package x\ntrait SlotWriter:\n  def f(b: DataSourceBatchUpdate) = b.commit()\n")
        f.write(BASELINE, baseline(refs={"controller/SnapControllerEnv.scala": ENV_REFS, "actors/FooCoordinator.scala": COORD_REFS - 2,
                                         "actors/storage/SlotWriter.scala": 2}))
        f.commit("refactor(snap): split slot writer")
        out = self.ok(f)
        self.assertIn("[storage] actors/storage/SlotWriter.scala: 2 (baseline 2, ceiling 0)", out)

    def test_total_increase_with_new_total_justification_passes(self):
        f = ratchet_fixture(branch="feat/coord")
        f.write(COORD, COORD_BASE + "  def g(s: StateStorage) = s\n")
        f.write(BASELINE, baseline(refs={"controller/SnapControllerEnv.scala": ENV_REFS, "actors/FooCoordinator.scala": COORD_REFS + 1},
                                   just={"storage_refs.total": "Foo needs StateStorage for X until StateIngest, see #1"}))
        f.commit("feat: coordinator")
        self.ok(f)

    def test_recorded_total_must_match_the_measurement(self):
        f = ratchet_fixture(branch="feat/coord")
        f.write(BASELINE, baseline(total=ENV_REFS + COORD_REFS + 3))
        f.commit("chore: total")
        out = self.bad(f, "storage-refs")
        self.assertIn(f"total: measured {ENV_REFS + COORD_REFS}", out)

    # the controller core is in the measured set (scope extension, T054c): a move out of it is a per-file trade
    def test_move_from_core_into_a_module_with_the_same_refs_passes_without_justification(self):
        write = "appStateStorage.put(tmp).commit()"  # 2 refs: the handle and `.commit()`
        core = CORE_BASE.replace("println(tmp)", write)
        refs = {"controller/SnapControllerEnv.scala": ENV_REFS, "actors/FooCoordinator.scala": COORD_REFS}
        f = Fixture(branch="refactor/snap-016-m5c",
                    core_base=core, extra={ENV: ENV_BASE, COORD: COORD_BASE,
                                           BASELINE: baseline(refs={**refs, "SNAPSyncController.scala": 2})})
        f.write(BASELINE, baseline(refs={**refs, "controller/HealingOrchestrator.scala": 2}))
        move_commit(f, module=MODULE_MOVE.replace("println(tmp)", write), partial=True)
        out = self.ok(f)
        self.assertIn("[storage] controller/HealingOrchestrator.scala: 2 (baseline 2, ceiling 0)", out)
        self.assertIn("[storage] SNAPSyncController.scala: 0 (baseline 0)", out)
        self.assertIn(f"[storage] total: {ENV_REFS + COORD_REFS + 2} (baseline {ENV_REFS + COORD_REFS + 2}, ceiling "
                      f"{ENV_REFS + COORD_REFS + 2})", out)
        self.assertNotIn("[storage-refs]", out)

    def test_core_only_increase_fails(self):
        f = ratchet_fixture(branch="feat/core")
        f.write(CORE, CORE_BASE + "  def g(s: StateStorage) = s\n")
        f.commit("feat: core")
        out = self.bad(f, "storage-refs")
        self.assertIn(f"SNAPSyncController.scala: measured 1, but {BASELINE} records 0", out)
        self.assertIn(f"total storage references {ENV_REFS + COORD_REFS + 1}, above the ceiling {ENV_REFS + COORD_REFS}", out)

    # env header and the widened storage-type predicate
    def test_env_header_change_fails(self):
        for hdr in ("private[snap] trait SnapControllerEnv extends HasStores:",
                    "private[snap] trait SnapControllerEnv:\n  self: StoreAccess =>"):
            f = ratchet_fixture()
            self.env_change(f, ENV_BASE.replace("private[snap] trait SnapControllerEnv:", hdr))
            out = self.bad(f, "env-freeze")
            self.assertIn("header", out)

    def test_widened_storage_type_predicate(self):
        for t in ("TrieNodeStore", "Option[StorageBackend]", "Int => KvStores", "RocksDBHandle"):
            f = ratchet_fixture()
            self.env_change(f, ENV_BASE.replace("def syncConfig: SyncConfig", f"def syncConfig: {t}"))
            out = self.bad(f, "env-freeze")
            self.assertIn("storage-typed member `syncConfig", out)

    def test_allow_listed_actor_and_protocol_types_are_not_storage(self):
        for t in ("ActorRef[StorageRangeCoordinator.Command]", "ActorRef[StorageRangeWorker.Command]", "StorageRanges => GetStorageRanges"):
            f = ratchet_fixture()
            self.env_change(f, ENV_BASE.replace("def syncConfig: SyncConfig", f"def syncConfig: {t}"))
            self.ok(f)

    def test_storage_wrappers_named_like_actors_are_flagged(self):
        for t in ("FooStorageWorker", "ActorRef[ImmutableStorageActor.Command]", "TrieStoreCoordinator"):
            f = ratchet_fixture()
            self.env_change(f, ENV_BASE.replace("def syncConfig: SyncConfig", f"def syncConfig: {t}"))
            out = self.bad(f, "env-freeze")
            self.assertIn("storage-typed member `syncConfig", out)
            self.assertIn("[storage-refs] controller/SnapControllerEnv.scala: measured 6", out)

    # bootstrap happens once
    def test_missing_baseline_at_merge_base_after_staging_has_it_fails(self):
        f = ratchet_fixture(with_baseline=False)
        f.git("checkout", "-q", "main")
        f.write(BASELINE, baseline())
        f.commit("chore: baseline lands on staging")
        f.git("tag", "-f", "base")
        f.git("checkout", "-q", f.branch)
        f.write(BASELINE, baseline())
        f.commit("chore: stale branch re-adds it")
        out = self.bad(f, "storage-refs")
        self.assertIn("rebase onto base", out)

    # the tokenizer and the count predicate
    def test_count_tokenizer(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location("snap_verify", Path(__file__).resolve().parents[1] / "verify.py")
        V = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(V)
        cases = {
            "/* c */ val a = env.stateStorage.get": 1,
            'log.info("a // b", env.stateStorage.get)': 1,
            "/* a /* b */ env.stateStorage */ val x = env.stateStorage": 1,
            'val s = """// x\n""" + env.stateStorage // env.stateStorage': 1,
            "val q = '\"'; val y = env.stateStorage // c": 1,
            "/**\n  * env.stateStorage\n  */\nval z = a\n  * env.stateStorage": 1,  # a `*` continuation line is code
            's"${env.stateStorage} // ${"x"}" + env.flatSlotStorage': 2,
            "val e = '\\\\'; env.stateStorage": 1,
            "env.stateStorageOf(root).get; val s = x.putSnapSyncStorageComplete(1)": 2,  # member accesses
            "x.storageRoot; import a.actors.StorageRangeCoordinator; msg.StorageRanges": 0,
            "val w: FooStorageWorker = ImmutableStorageActor(x)": 2,  # not allow-listed: counted
            "StorageRangeCoordinator.Command; StorageRangeWorker(x); StorageRangesResponseMsg; noStorageCoordinator": 0,
            "val stateStorages = env.stateStorages": 2,  # bare plural identifier, and its member access
        }
        for text, n in cases.items():
            self.assertEqual(V.storage_ref_count(text), n, text)

    # The real baseline must agree with the tree it sits in, not with today's numbers: T055 lowers env.members to about
    # 11 and empties env.storage_members, and the C-* slices keep moving the counts (T054b review).
    @staticmethod
    def env_baseline_problems(env_text, b):
        members = V().env_members(env_text)
        typed = {n for n, d in members.items() if V().storage_typed(d)}
        pinned = set(b["env"]["storage_members"])
        out = []
        if b["env"]["members"] != len(members):
            out.append(f"env.members {b['env']['members']} != measured {len(members)}")
        out += [f"pinned `{n}` is not declared on the env" for n in sorted(pinned - set(members))]
        out += [f"storage-typed env member `{n}` is not pinned" for n in sorted(typed - pinned)]
        return out

    def test_real_baseline_is_consistent_with_the_env_on_this_tree(self):
        root = Path(__file__).resolve().parents[3]
        b = json.loads((root / BASELINE).read_text())
        env_text = (root / ENV).read_text()
        self.assertEqual(self.env_baseline_problems(env_text, b), [])
        self.assertEqual(b["env"]["header"], V().env_header(env_text))

    def test_env_baseline_consistency_check_on_fixture_shapes(self):
        handles = ["appStateStorage: AppStateStorage", "stateStorage: StateStorage", "evmCodeStorage: EvmCodeStorage",
                   "flatSlotStorage: FlatSlotStorage", "pathNodeStorageOpt: Option[PathNodeStorage]",
                   "blockchainReader: BlockchainReader", "blockchainWriter: BlockchainWriter",
                   "validatorFactory: MptStorage => StateValidator"]
        others = [f"other{i}: Int" for i in range(10)]
        env = lambda ms: "package x\n\nprivate[snap] trait SnapControllerEnv:\n" + "".join(f"  def {m}\n" for m in ms)
        names = [h.split(":")[0] for h in handles]
        bl = lambda n, pinned: {"env": {"members": n, "header": ":", "storage_members": list(pinned)}}
        # the T054 shape (18 members, 8 pinned handles) and the post-T055 shape (11 members, none pinned) both hold
        self.assertEqual(self.env_baseline_problems(env(handles + others), bl(18, names)), [])
        self.assertEqual(self.env_baseline_problems(env(others + ["stateIngest: StateIngest"]), bl(11, [])), [])
        # a wrong count, a stale pin and an unpinned storage handle are each reported
        self.assertEqual(self.env_baseline_problems(env(handles + others), bl(17, names)), ["env.members 17 != measured 18"])
        self.assertEqual(self.env_baseline_problems(env(handles[1:] + others), bl(17, names)),
                         ["pinned `appStateStorage` is not declared on the env"])
        self.assertEqual(self.env_baseline_problems(env(handles + others), bl(18, names[1:])),
                         ["storage-typed env member `appStateStorage` is not pinned"])

# ---- capability mode (spec 016 T054b): a behaviour-neutral PR that introduces a capability and moves call sites onto it

import importlib.util as _ilu

_V_MOD = None


def V():
    global _V_MOD
    if _V_MOD is None:
        spec = _ilu.spec_from_file_location("snap_verify", Path(__file__).resolve().parents[1] / "verify.py")
        _V_MOD = _ilu.module_from_spec(spec)
        spec.loader.exec_module(_V_MOD)
    return _V_MOD


CAP = f"{CTRL}/StateIngest.scala"
FIN = f"{CTRL}/SnapFinalization.scala"
CAP_MSG = "refactor(snap): StateIngest (#1401)\n\n# capability: StateIngest\n"
PINNED = ("appStateStorage", "blockchainReader", "validatorFactory", "doneStorage")

ENV_CAP_BASE = """package x.controller

private[snap] trait SnapControllerEnv:

  val ctx: Ctx
  def syncConfig: SyncConfig

  /** App state (a comment). */
  def appStateStorage: AppStateStorage
  def blockchainReader: BlockchainReader
  def validatorFactory: MptStorage => StateValidator
"""

ENV_CAP_AFTER = """package x.controller

private[snap] trait SnapControllerEnv:

  val ctx: Ctx
  def syncConfig: SyncConfig

  /** SNAP's storage capability (spec 016 T055). */
  def stateIngest: StateIngest
"""

FIN_BASE = """package x.controller

import x.db.storage.SnapStorageDoneStorage

private[snap] trait SnapFinalizationState:
  def pivot: BigInt
  def doneStorage: SnapStorageDoneStorage

private[snap] trait SnapFinalization:
  self: SnapFinalizationState & SnapControllerEnv =>

  def finish(b: BigInt): Unit =
    appStateStorage.putSnapSyncPivotBlock(b).commit()
    val header = blockchainReader.getBlockHeaderByNumber(b)
    appStateStorage
      .putBestBlockNumber(b)
      .and(appStateStorage.putSnapSyncStateRoot(header.root))
      .commit()
    doneStorage.markDone(b)
    log.info(s"pivot ${appStateStorage.getSnapSyncPivotBlock()} done")

  def maybe(b: BigInt, on: Boolean): Unit =
    if on then
      appStateStorage.putSnapSyncPivotBlock(b).commit()
    log.info("done")

  def validate(s: MptStorage) =
    val v = validatorFactory(s)
    v.run()
"""

# the T055 shape: every handle use becomes a StateIngest call (method forwards, handle forwards, a multi-line chain, an
# interpolation), the State trait loses its storage handle, an import goes
FIN_AFTER = """package x.controller

private[snap] trait SnapFinalizationState:
  def pivot: BigInt

private[snap] trait SnapFinalization:
  self: SnapFinalizationState & SnapControllerEnv =>

  def finish(b: BigInt): Unit =
    stateIngest.putPivotBlock(b).commit()
    val header = stateIngest.headerByNumber(b)
    stateIngest.appStateStorage
      .putBestBlockNumber(b)
      .and(stateIngest.appStateStorage.putSnapSyncStateRoot(header.root))
      .commit()
    stateIngest.markDone(b)
    log.info(s"pivot ${stateIngest.pivotBlock()} done")

  def maybe(b: BigInt, on: Boolean): Unit =
    if on then
      stateIngest.putPivotBlock(b).commit()
    log.info("done")

  def validate(s: MptStorage) =
    val v = stateIngest.validatorFactory(s)
    v.run()
"""

CAP_FILE = """package x.controller

import x.db.dataSource.DataSourceBatchUpdate

/** SNAP's storage capability (spec 016 T055). The call-site map, checked by scripts/snap-split/verify.py (T054b):
  *
  * capability-map: appStateStorage.putSnapSyncPivotBlock -> putPivotBlock
  * capability-map: appStateStorage.getSnapSyncPivotBlock -> pivotBlock
  * capability-map: appStateStorage -> appStateStorage
  * capability-map: blockchainReader.getBlockHeaderByNumber -> headerByNumber
  * capability-map: validatorFactory -> validatorFactory
  * capability-map: doneStorage.markDone -> markDone
  */
private[snap] trait StateIngest:
  def appStateStorage: AppStateStorage
  def putPivotBlock(b: BigInt): DataSourceBatchUpdate
  def pivotBlock(): Option[BigInt]
  def headerByNumber(n: BigInt): Option[BlockHeader]
  def validatorFactory: MptStorage => StateValidator
  def markDone(b: BigInt): Unit

private[snap] object StateIngest:

  def apply(
      appStateStorage: AppStateStorage,
      blockchainReader: BlockchainReader,
      validatorFactory: MptStorage => StateValidator,
      doneStorage: SnapStorageDoneStorage
  ): StateIngest = new Production(appStateStorage, blockchainReader, validatorFactory, doneStorage)

  private final class Production(
      val appStateStorage: AppStateStorage,
      blockchainReader: BlockchainReader,
      val validatorFactory: MptStorage => StateValidator,
      doneStorage: SnapStorageDoneStorage
  ) extends StateIngest:
    def putPivotBlock(b: BigInt): DataSourceBatchUpdate = appStateStorage.putSnapSyncPivotBlock(b)
    def pivotBlock(): Option[BigInt] = appStateStorage.getSnapSyncPivotBlock()
    def headerByNumber(n: BigInt): Option[BlockHeader] =
      blockchainReader.getBlockHeaderByNumber(n)
    def markDone(b: BigInt): Unit = doneStorage.markDone(b)
"""
CAP_RAW = ["x.db.dataSource.DataSourceBatchUpdate"]

CORE_CAP_BASE = """package x

class SNAPSyncControllerImpl(
    val appStateStorage: AppStateStorage,
    val blockchainReader: BlockchainReader,
    val validatorFactory: MptStorage => StateValidator,
    val doneStorage: SnapStorageDoneStorage
) extends SnapControllerEnv
    with SnapFinalization:
  var shared = 1

  def other(): Int = appStateStorage.getBestBlockNumber()
"""

CORE_CAP_AFTER = CORE_CAP_BASE.replace(
    "  var shared = 1\n",
    "  var shared = 1\n\n  // Implements SnapControllerEnv.stateIngest (spec 016 T055).\n"
    "  val stateIngest: StateIngest =\n    StateIngest(appStateStorage, blockchainReader, validatorFactory, doneStorage)\n")


# The T055 shape (T054b review): members renamed from their handles, and a durability policy that is a constructor
# parameter typed by an enum the capability file declares.
DURABLE_CAP = """package x.controller

// capability-map: appStateStorage -> appState
// capability-map: blockchainReader -> chainReader
// capability-map: validatorFactory -> validatorFactory
// capability-map: doneStorage -> done
// capability-map: appStateStorage.putSnapSyncPivotBlock -> putPivotBlock
// capability-map: appStateStorage.getSnapSyncPivotBlock -> pivotBlock
private[snap] trait StateIngest:
  def appState: AppStateStorage
  def chainReader: BlockchainReader
  def validatorFactory: MptStorage => StateValidator
  def done: SnapStorageDoneStorage
  def putPivotBlock(b: BigInt): DataSourceBatchUpdate
  def pivotBlock(): Option[BigInt]
  def durability: StateIngest.Durability

private[snap] object StateIngest:

  /** The policies. */
  enum Durability:
    case Default

  def apply(
      appState: AppStateStorage,
      chainReader: BlockchainReader,
      validatorFactory: MptStorage => StateValidator,
      done: SnapStorageDoneStorage,
      durability: Durability
  ): StateIngest =
    new Stores(appState, chainReader, validatorFactory, done, durability)

  final private class Stores(
      val appState: AppStateStorage,
      val chainReader: BlockchainReader,
      val validatorFactory: MptStorage => StateValidator,
      val done: SnapStorageDoneStorage,
      val durability: Durability
  ) extends StateIngest:
    def putPivotBlock(b: BigInt): DataSourceBatchUpdate = appState.putSnapSyncPivotBlock(b)
    def pivotBlock(): Option[BigInt] = appState.getSnapSyncPivotBlock()
"""

DURABLE_CORE = CORE_CAP_BASE.replace(
    "  var shared = 1\n",
    "  var shared = 1\n\n  val stateIngest: StateIngest =\n"
    "    StateIngest(appStateStorage, blockchainReader, validatorFactory, doneStorage, StateIngest.Durability.Default)\n")

FIN_AFTER_RENAMED = FIN_AFTER.replace("stateIngest.appStateStorage", "stateIngest.appState").replace(
    "stateIngest.headerByNumber(b)", "stateIngest.chainReader.getBlockHeaderByNumber(b)").replace(
    "stateIngest.markDone(b)", "stateIngest.done.markDone(b)")


def measure(files, caps=None):
    """storage_refs for a fixture's measured files (keys relative to snap/), as the verifier counts them."""
    caps = caps or {}
    ex = [V().cap_exemption(n, files[k]) for k, n in caps.items()]
    return {k: n for k, t in files.items() if k not in caps and (n := V().storage_ref_count(t, ex))}


def cap_baseline(files, pinned=PINNED, env_members=5, caps=None, header=":", raw=None, just=None):
    refs = measure(files, {e["file"]: n for n, e in (caps or {}).items()})
    return json.dumps({
        "env": {"members": env_members, "header": header, "storage_members": list(pinned)},
        "storage_refs_total": sum(refs.values()),
        "storage_refs": refs,
        "raw_imports": raw or {},
        "capabilities": caps or {},
        "justifications": just or {},
    }, indent=2) + "\n"


def rel(path):
    return path[len(SNAP) + 1:]


BASE_FILES = {rel(ENV): ENV_CAP_BASE, rel(FIN): FIN_BASE, rel(CORE): CORE_CAP_BASE}


def cap_fixture(branch="refactor/snap-016-state-ingest"):
    return Fixture(branch=branch, core_base=CORE_CAP_BASE,
                   extra={ENV: ENV_CAP_BASE, FIN: FIN_BASE, BASELINE: cap_baseline(BASE_FILES)})


def cap_commit(f, fin=FIN_AFTER, env=ENV_CAP_AFTER, core=CORE_CAP_AFTER, cap=CAP_FILE, msg=CAP_MSG, members=6,
               raw=CAP_RAW, pinned=(), env_members=3, with_baseline=True, extra=None):
    for path, text in ((FIN, fin), (ENV, env), (CORE, core), (CAP, cap), *((extra or {}).items())):
        if text is not None:
            f.write(path, text)
    if with_baseline:
        files = {rel(ENV): env, rel(FIN): fin, rel(CORE): core, rel(CAP): cap,
                 **{rel(k): v for k, v in (extra or {}).items() if k.startswith(SNAP)}}
        f.write(BASELINE, cap_baseline(files, pinned=pinned, env_members=env_members,
                                       caps={"StateIngest": {"file": rel(CAP), "members": members, "raw_imports": list(raw)}}))
    f.commit(msg)


class CapabilityModeTests(unittest.TestCase):
    ok = VerifyTests.ok
    bad = VerifyTests.bad

    # ---- positive: the T055 shape
    def test_t055_shaped_substitution_passes(self):
        f = cap_fixture()
        cap_commit(f)
        out = self.ok(f)
        self.assertIn("[capability] StateIngest in " + CAP + ": accessor `stateIngest`, 6 map entries, 6 members", out)
        self.assertIn(f"[capability] {FIN}: 8 substitution(s)", out)
        self.assertIn(f"[capability] {CORE}: 0 substitution(s)", out)
        self.assertIn("capability traits: SnapControllerEnv=3 StateIngest=6", out)
        self.assertIn("[storage] capability StateIngest (controller/StateIngest.scala): members=6 (baseline 6, new)", out)
        self.assertIn("[storage] SnapControllerEnv: members=3 (baseline 3)", out)
        # hand count of SnapFinalization (STORAGE_REF_RE): before, `appStateStorage` x5, `.commit()` x3,
        # `blockchainReader`, `doneStorage` x2 (State member, call), `SnapStorageDoneStorage` x2 (import, type),
        # `MptStorage` = 14; after, `.commit()` x3, `stateIngest.appStateStorage` x2 and `stateIngest.validatorFactory`
        # (storage-typed members: a raw handle reached through the capability still counts, once), `MptStorage` = 7.
        # The StateIngest calls (putPivotBlock, headerByNumber, markDone, pivotBlock) are not storage references.
        self.assertEqual(V().storage_ref_count(FIN_BASE), 14)
        self.assertEqual(V().storage_ref_count(FIN_AFTER, [V().cap_exemption("StateIngest", CAP_FILE)]), 7)
        self.assertIn("[storage] controller/SnapFinalization.scala: 7 (baseline 7)", out)
        self.assertNotIn("[storage] controller/StateIngest.scala", out)  # the capability file is not a storage_refs row

    def test_capability_call_on_a_storage_named_member_is_not_a_storage_reference(self):
        cap = "trait StateIngest:\n  def putSnapSyncStorageComplete(b: Boolean): Unit\n  def flatSlotStorage: FlatSlotStorage\n"
        ex = [V().cap_exemption("StateIngest", cap)]
        self.assertEqual(V().storage_ref_count("stateIngest.putSnapSyncStorageComplete(true)", ex), 0)
        self.assertEqual(V().storage_ref_count("stateIngest\n  .putSnapSyncStorageComplete(true)", ex), 0)
        self.assertEqual(V().storage_ref_count("other.putSnapSyncStorageComplete(true)", ex), 1)  # keyed on the accessor
        self.assertEqual(V().storage_ref_count("stateIngest.flatSlotStorage.dataSource", ex), 2)  # storage-typed member
        # a storage-typed member counts once whatever its name: renaming a raw handle does not hide it
        cap2 = cap + "  def slotSource: DataSource\n"
        self.assertEqual(V().storage_ref_count("stateIngest.slotSource.get(k)", [V().cap_exemption("StateIngest", cap2)]), 1)

    def test_reformatted_chain_and_comment_edits_pass(self):
        f = cap_fixture()
        fin = FIN_AFTER.replace(
            "    stateIngest.putPivotBlock(b).commit()\n    val header",
            "    // now through the capability\n    stateIngest\n      .putPivotBlock(b)\n      .commit()\n    val header")
        cap_commit(f, fin=fin)
        self.ok(f)

    # ---- negatives: disguised logic changes
    def neg(self, fin, check="capability", **kw):
        f = cap_fixture()
        cap_commit(f, fin=fin, **kw)
        return self.bad(f, check)

    def test_changed_argument_fails(self):
        out = self.neg(FIN_AFTER.replace("stateIngest.headerByNumber(b)", "stateIngest.headerByNumber(b + 1)"))
        self.assertIn("not a mapped handle substitution", out)

    def test_extra_statement_fails(self):
        out = self.neg(FIN_AFTER.replace("    stateIngest.markDone(b)\n", "    stateIngest.markDone(b)\n    stateIngest.markDone(b)\n"))
        self.assertIn("SnapFinalization.scala: change is not a mapped handle substitution", out)

    def test_reordered_call_fails(self):
        out = self.neg(FIN_AFTER.replace(
            "    stateIngest.putPivotBlock(b).commit()\n    val header = stateIngest.headerByNumber(b)\n",
            "    val header = stateIngest.headerByNumber(b)\n    stateIngest.putPivotBlock(b).commit()\n"))
        self.assertIn("not a mapped handle substitution", out)

    def test_statement_moved_into_a_block_fails(self):
        out = self.neg(FIN_AFTER.replace('    log.info("done")', '      log.info("done")'))
        self.assertIn("statement indentation changed (4 -> 6)", out)

    def test_statements_merged_onto_one_line_fails(self):
        out = self.neg(FIN_AFTER.replace("      .commit()\n    stateIngest.markDone(b)", "      .commit(); stateIngest.markDone(b)"))
        self.assertIn("SnapFinalization.scala: change is not a mapped handle substitution", out)

    def test_new_storage_reference_fails(self):
        out = self.neg(FIN_AFTER.replace("header.root", "appStateStorage.getSnapSyncStateRoot()"))
        self.assertIn("not a mapped handle substitution", out)

    def test_kept_handle_where_the_map_is_new_code_fails(self):
        # an unmapped storage call is not a substitution, even of a handle the capability exposes
        out = self.neg(FIN_AFTER.replace("stateIngest.markDone(b)", "stateIngest.appStateStorage.markDone(b)"))
        self.assertIn("not a mapped handle substitution", out)

    def test_handle_replaced_by_the_wrong_member_fails(self):
        out = self.neg(FIN_AFTER.replace("stateIngest.putPivotBlock(b).commit()\n    val header",
                                         "stateIngest.pivotBlock(b).commit()\n    val header"))
        self.assertIn("not a mapped handle substitution", out)

    def test_forwarder_that_calls_another_operation_fails(self):
        cap = CAP_FILE.replace("= appStateStorage.putSnapSyncPivotBlock(b)", "= appStateStorage.putSnapSyncStateRoot(b)")
        out = self.neg(FIN_AFTER, cap=cap)
        self.assertIn("forwarder `putPivotBlock` is not a pure forwarder of `appStateStorage.putSnapSyncPivotBlock`", out)

    def test_forwarder_with_reordered_or_extra_work_fails(self):
        for body in ("= { appStateStorage.flush(); appStateStorage.putSnapSyncPivotBlock(b) }",
                     "= appStateStorage.putSnapSyncPivotBlock(b + 1)"):
            cap = CAP_FILE.replace("= appStateStorage.putSnapSyncPivotBlock(b)", body)
            out = self.neg(FIN_AFTER, cap=cap)
            self.assertIn("is not a pure forwarder", out)

    def test_mapped_member_with_a_default_parameter_fails(self):
        cap = CAP_FILE.replace("  def markDone(b: BigInt): Unit\n", "  def markDone(b: BigInt = 0): Unit\n", 1)
        out = self.neg(FIN_AFTER, cap=cap)
        self.assertIn("mapped member `markDone` declares a default parameter", out)

    def test_mapped_method_without_a_forwarder_fails(self):
        cap = CAP_FILE.replace("    def markDone(b: BigInt): Unit = doneStorage.markDone(b)\n", "")
        out = self.neg(FIN_AFTER, cap=cap)
        self.assertIn("mapped member `markDone` (`doneStorage.markDone`) has no forwarder body", out)

    def test_capability_file_redefining_a_handle_fails(self):
        cap = CAP_FILE.replace("    def markDone(b: BigInt)", "    private def blockchainReader = OtherReader\n    def markDone(b: BigInt)")
        out = self.neg(FIN_AFTER, cap=cap)
        self.assertIn("defines handle `blockchainReader` with a body", out)

    def test_map_root_that_is_not_a_storage_handle_fails(self):
        cap = CAP_FILE.replace("  */\n", "  * capability-map: ctx.log -> log\n  */\n", 1).replace(
            "  def markDone(b: BigInt): Unit\n", "  def markDone(b: BigInt): Unit\n  def log: Logger\n", 1)
        out = self.neg(FIN_AFTER, cap=cap, members=7)
        self.assertIn("`ctx` is not a storage handle", out)

    def test_wrong_import_kinds_fail(self):
        out = self.neg(FIN_AFTER.replace("package x.controller\n", "package x.controller\n\nimport x.Conversions.given\n", 1))
        self.assertIn("adds wildcard or given import", out)

    # ---- T054b review (prism, f43aa3309): the capability file's structure and its wiring
    def test_b1_statement_in_the_production_class_fails(self):
        cap = CAP_FILE.replace("    def markDone(b: BigInt): Unit = doneStorage.markDone(b)\n",
                               "    def markDone(b: BigInt): Unit = doneStorage.markDone(b)\n    appStateStorage.putBestBlockNumber(0).commit()\n")
        out = self.neg(FIN_AFTER, cap=cap)
        self.assertIn("production class body holds only forwarder `def`s of mapped members", out)

    def test_b1_unmapped_def_with_a_body_fails(self):
        cap = CAP_FILE.replace("    def markDone(b: BigInt): Unit = doneStorage.markDone(b)\n",
                               "    def markDone(b: BigInt): Unit = doneStorage.markDone(b)\n    def junk(): Unit = appStateStorage.flush()\n")
        out = self.neg(FIN_AFTER, cap=cap)
        self.assertIn("`def junk` in the production class is not a mapped member", out)

    def test_b1_extra_top_level_object_fails(self):
        out = self.neg(FIN_AFTER, cap=CAP_FILE + "\nobject Boot:\n  val x = sideEffect()\n")
        self.assertIn("top-level `object Boot:`: the capability file holds only", out)

    def test_b1_extra_member_in_the_companion_fails(self):
        cap = CAP_FILE.replace("  def apply(", "  val cache = Cache.build()\n\n  def apply(", 1)
        out = self.neg(FIN_AFTER, cap=cap)
        self.assertIn("object StateIngest holds only enums, type aliases, one `apply` and one production class", out)

    def test_b1_class_body_val_with_an_initializer_fails(self):
        # T055's first draft set `durability` in the class body; an unforwarded member is a constructor parameter instead
        cap = CAP_FILE.replace("    def markDone(b: BigInt): Unit = doneStorage.markDone(b)\n",
                               "    def markDone(b: BigInt): Unit = doneStorage.markDone(b)\n    val durability: Int = 0\n")
        out = self.neg(FIN_AFTER, cap=cap)
        self.assertIn("`val durability: Int = 0` is not one (an unforwarded member is a `val` constructor parameter)", out)

    def test_b1_concrete_trait_member_and_enum_with_a_body_fail(self):
        cap = CAP_FILE.replace("  def markDone(b: BigInt): Unit\n", "  def markDone(b: BigInt): Unit\n  def extra: Int = 1\n", 1)
        out = self.neg(FIN_AFTER, cap=cap, members=7)
        self.assertIn("trait StateIngest holds abstract `def`/`val` members only", out)
        cap = DURABLE_CAP.replace("    case Default\n", "    case Default\n    def flush(): Unit = sync()\n")
        out = self.neg(FIN_AFTER, cap=cap, core=DURABLE_CORE, members=7)
        self.assertIn("enum Durability holds plain `case A, B` lines only", out)

    def test_b1_capability_file_edited_alone_is_checked(self):
        # a later commit of the capability PR that edits only the capability file; and a later PR, via the head re-check
        f = cap_fixture()
        cap_commit(f)
        f.write(CAP, CAP_FILE + "\nobject Boot:\n  val x = sideEffect()\n")
        f.commit(CAP_MSG)
        self.assertIn("top-level `object Boot:`", self.bad(f, "capability"))
        g = cap_fixture()
        cap_commit(g)
        g.git("tag", "-f", "base")
        g.write(CAP, CAP_FILE + "\nobject Boot:\n  val x = sideEffect()\n")
        g.commit("feat: tweak StateIngest")
        self.assertIn("top-level `object Boot:`", self.bad(g, "capability"))

    def test_f1_given_import_inside_the_object_fails(self):
        cap = CAP_FILE.replace("private[snap] object StateIngest:\n", "private[snap] object StateIngest:\n  import x.Conversions.given\n")
        out = self.neg(FIN_AFTER, cap=cap)
        self.assertIn("the capability file imports only at the top of the file", out)
        self.assertIn("wildcard or given import `x.Conversions.given`", out)

    def test_f1_given_import_inside_the_production_class_fails(self):
        cap = CAP_FILE.replace("  ) extends StateIngest:\n", "  ) extends StateIngest:\n    import x.Conversions.given\n")
        out = self.neg(FIN_AFTER, cap=cap)
        self.assertIn("the capability file imports only at the top of the file", out)

    def test_f1_wildcard_or_given_import_at_the_top_fails(self):
        for imp in ("import x.Conversions.*", "import x.Conversions.{given, Foo}"):
            cap = CAP_FILE.replace("import x.db.dataSource.DataSourceBatchUpdate\n", f"import x.db.dataSource.DataSourceBatchUpdate\n{imp}\n")
            out = self.neg(FIN_AFTER, cap=cap)
            self.assertIn("wildcard or given import", out)

    def test_f1_named_top_level_import_passes(self):
        f = cap_fixture()
        cap_commit(f, cap=CAP_FILE.replace("import x.db.dataSource.DataSourceBatchUpdate\n",
                                           "import x.db.dataSource.DataSourceBatchUpdate\nimport x.domain.{BlockHeader, BlockchainReader}\n"))
        self.ok(f)

    def test_b2_apply_must_pass_its_own_parameters_in_order(self):
        for body in ("new Production(appStateStorage, blockchainReader.other, validatorFactory, doneStorage)",
                     "new Production(appStateStorage, blockchainReader, validatorFactory, new NoopDone())",
                     "new Production(appStateStorage, validatorFactory, blockchainReader, doneStorage)"):
            cap = CAP_FILE.replace("new Production(appStateStorage, blockchainReader, validatorFactory, doneStorage)", body)
            out = self.neg(FIN_AFTER, cap=cap)
            self.assertIn("`apply` body must be exactly `new Production ( appStateStorage , blockchainReader ,", out)

    def test_b2_apply_defaults_and_class_parameter_names_fail(self):
        cap = CAP_FILE.replace("      doneStorage: SnapStorageDoneStorage\n  ): StateIngest",
                               "      doneStorage: SnapStorageDoneStorage = NoopDone\n  ): StateIngest")
        self.assertIn("`apply` parameters take no defaults", self.neg(FIN_AFTER, cap=cap))
        cap = CAP_FILE.replace("      doneStorage: SnapStorageDoneStorage\n  ) extends", "      done: SnapStorageDoneStorage\n  ) extends")
        self.assertIn("production class `Production` parameters", self.neg(FIN_AFTER, cap=cap))

    def test_b3_core_swapping_two_handles_fails(self):
        core = CORE_CAP_AFTER.replace("StateIngest(appStateStorage, blockchainReader,", "StateIngest(blockchainReader, appStateStorage,")
        out = self.neg(FIN_AFTER, core=core)
        self.assertIn("passes `blockchainReader` as argument 1 (`appStateStorage`); it must be the handle `appStateStorage`", out)

    def test_b3_core_named_argument_must_name_the_parameter(self):
        core = CORE_CAP_AFTER.replace("StateIngest(appStateStorage, blockchainReader,",
                                      "StateIngest(blockchainReader = appStateStorage, blockchainReader,")
        out = self.neg(FIN_AFTER, core=core)
        self.assertIn("names argument 1 `blockchainReader`, but `apply`'s parameter 1 is `appStateStorage`", out)

    def test_b3_renamed_members_take_the_mapped_handle_and_the_enum_policy_passes(self):
        # the T055 shape: members renamed from their handles (`appStateStorage -> appState`), and a durability policy
        # that is a constructor parameter typed by a declared enum, passed by the core as a path to one of its cases
        f = cap_fixture()
        cap_commit(f, cap=DURABLE_CAP, core=DURABLE_CORE, fin=FIN_AFTER_RENAMED, members=7)
        out = self.ok(f)
        self.assertIn("accessor `stateIngest`, 6 map entries, 7 members", out)

    def test_b3_durability_must_be_a_case_of_the_declared_enum(self):
        for arg in ("makeDurability()", "StateIngest.Durability.Relaxed", "appStateStorage"):
            core = DURABLE_CORE.replace("StateIngest.Durability.Default", arg)
            out = self.neg(FIN_AFTER_RENAMED, cap=DURABLE_CAP, core=core, members=7)
            self.assertIn("for `durability: Durability`: it must be a case of the declared enum", out)

    def test_dropped_or_added_declaration_must_be_alone_on_its_line(self):
        env = ENV_CAP_AFTER.replace("  def stateIngest: StateIngest\n", "  def stateIngest: StateIngest; def extraCall = ctx.stop()\n")
        out = self.neg(FIN_AFTER, env=env)
        self.assertIn("shares its line with another statement", out)
        f = cap_fixture()
        cap_commit(f, env=ENV_CAP_BASE.replace("  def appStateStorage: AppStateStorage\n",
                                               "  def appStateStorage: AppStateStorage; def x: Int\n"), with_baseline=False)
        g = Fixture(branch="refactor/snap-016-state-ingest", core_base=CORE_CAP_BASE,
                    extra={ENV: ENV_CAP_BASE.replace("  def appStateStorage: AppStateStorage\n",
                                                     "  def appStateStorage: AppStateStorage; val y = 1\n"),
                           FIN: FIN_BASE, BASELINE: cap_baseline(BASE_FILES)})
        cap_commit(g)
        self.assertIn("shares its line with another statement", self.bad(g, "capability"))

    # ---- the wiring files
    def test_env_may_only_lose_pinned_handles_and_gain_the_accessor(self):
        out = self.neg(FIN_AFTER, env=ENV_CAP_AFTER + "  def extra: Int\n", env_members=4)
        self.assertIn("adds `extra`; a capability commit adds only the accessor `stateIngest`", out)
        out = self.neg(FIN_AFTER, env=ENV_CAP_AFTER.replace("def stateIngest: StateIngest", "def stateIngest: Option[StateIngest]"))
        self.assertIn("must be declared `: StateIngest`", out)
        out = self.neg(FIN_AFTER, env=ENV_CAP_AFTER.replace("  def syncConfig: SyncConfig\n", ""), env_members=2)
        self.assertIn("removes `syncConfig`, which is not a pinned storage member", out)

    def test_core_accessor_may_only_wire_handles(self):
        core = CORE_CAP_AFTER.replace("StateIngest(appStateStorage,", "StateIngest(new AppStateStorage(otherSource),")
        out = self.neg(FIN_AFTER, core=core)
        self.assertIn("as argument 1 (`appStateStorage`); it must be the handle `appStateStorage`", out)
        # a block that builds the capability and calls it is not a production default
        core = CORE_CAP_AFTER.replace(
            "StateIngest(appStateStorage, blockchainReader, validatorFactory, doneStorage)",
            "StateIngest(appStateStorage, blockchainReader, validatorFactory, doneStorage).markDone(0)")
        out = self.neg(FIN_AFTER, core=core)
        self.assertIn("continues after the constructor call", out)

    def test_core_logic_change_fails(self):
        out = self.neg(FIN_AFTER, core=CORE_CAP_AFTER.replace("appStateStorage.getBestBlockNumber()", "0"))
        self.assertIn(f"{CORE}: ", out)

    def test_other_src_main_change_fails(self):
        out = self.neg(FIN_AFTER, extra={"src/main/scala/x/db/storage/AppStateStorage.scala": "package x\nclass AppStateStorage\n"})
        self.assertIn("outside snap/: a capability commit changes no storage or other code", out)

    def test_second_new_module_file_fails(self):
        out = self.neg(FIN_AFTER, extra={f"{CTRL}/Helper.scala": "package x.controller\nobject Helper\n"})
        self.assertIn(f"adds {CTRL}/Helper.scala: a capability commit adds only the capability file", out)

    # ---- selection: the trailer cannot disguise a move or a narrowing, nor hide beside a plain commit
    def test_capability_and_moved_trailers_together_fail(self):
        out = self.neg(FIN_AFTER, msg=CAP_MSG + "# moved: finish\n")
        self.assertIn("`# capability:` and `# moved:` on one commit", out)

    def test_move_commit_in_a_capability_pr_fails(self):
        f = cap_fixture()
        cap_commit(f)
        move_commit(f, partial=True)
        out = self.bad(f, "capability")
        self.assertIn("in a capability PR without a `# capability:` trailer", out)

    def test_plain_commit_beside_the_capability_commit_fails(self):
        f = cap_fixture()
        cap_commit(f)
        f.write(FIN, FIN_AFTER.replace("v.run()", "v.run(); v.run()"))
        f.commit("refactor(snap): tidy (#1401)")
        out = self.bad(f, "capability")
        self.assertIn("in a capability PR without a `# capability:` trailer", out)

    def test_trailer_on_test_fix_and_other_branches_fails(self):
        for branch in ("test/snap-016-state-ingest", "fix/state-ingest", "feat/state-ingest"):
            f = cap_fixture(branch=branch)
            cap_commit(f, msg=CAP_MSG + "# test-files: none\n")
            out = self.bad(f, "capability")
            self.assertIn("a capability PR is a refactor/snap-016-* branch", out)

    def test_two_capabilities_or_a_bad_name_fail(self):
        out = self.neg(FIN_AFTER, msg=CAP_MSG + "# capability: StateAccess\n")
        self.assertIn("one capability per PR", out)
        out = self.neg(FIN_AFTER, msg="refactor(snap): x\n\n# capability: stateIngest\n")
        self.assertIn("takes one trait name", out)

    # ---- step 8 in a capability PR
    def test_capability_needs_its_baseline_entry(self):
        f = cap_fixture()
        cap_commit(f, with_baseline=False)
        out = self.bad(f, "capability")
        self.assertIn("has no `capabilities[\"StateIngest\"]` entry", out)

    def test_capability_member_count_is_exact(self):
        out = self.neg(FIN_AFTER, check="capability", members=5)
        self.assertIn("StateIngest member count: measured 6", out)

    def test_stale_pinned_member_in_a_capability_pr_fails(self):
        out = self.neg(FIN_AFTER, check="env-freeze", pinned=PINNED)
        self.assertIn("capability PR removes pinned env member `appStateStorage`", out)

    def test_capability_raw_imports_are_measured(self):
        # a DataSource-returning member needs a raw import, which the capability's own list must name
        cap = CAP_FILE.replace("import x.db.dataSource.DataSourceBatchUpdate\n",
                               "import x.db.dataSource.DataSourceBatchUpdate\nimport x.db.dataSource.DataSource\n").replace(
            "  def markDone(b: BigInt): Unit\n", "  def markDone(b: BigInt): Unit\n  def rawSource: DataSource\n", 1)
        out = self.neg(FIN_AFTER, check="raw-import", cap=cap, members=7)
        self.assertIn("raw storage import `x.db.dataSource.DataSource` in capability StateIngest", out)

    def test_capability_added_outside_a_capability_pr_fails(self):
        f = cap_fixture()
        f.write(CAP, CAP_FILE)
        files = {**BASE_FILES, rel(CAP): CAP_FILE}
        f.write(BASELINE, cap_baseline(files, caps={"StateIngest": {"file": rel(CAP), "members": 6, "raw_imports": CAP_RAW}}))
        f.commit("refactor(snap): add StateIngest (#1401)")
        out = self.bad(f, "capability")
        self.assertIn("adds capability `StateIngest` outside a `# capability: StateIngest` PR", out)

    def test_later_pr_keeps_the_capability_entry_and_its_caps(self):
        # once StateIngest is on staging, its member cap and raw-import list only shrink, like the env's
        f = cap_fixture()
        cap_commit(f)
        f.git("tag", "-f", "base")
        cap2 = CAP_FILE.replace("  def markDone(b: BigInt): Unit\n", "  def markDone(b: BigInt): Unit\n  def extra(): Unit\n", 1)
        f.write(CAP, cap2)
        files = {rel(ENV): ENV_CAP_AFTER, rel(FIN): FIN_AFTER, rel(CORE): CORE_CAP_AFTER, rel(CAP): cap2}
        f.write(BASELINE, cap_baseline(files, pinned=(), env_members=3,
                                       caps={"StateIngest": {"file": rel(CAP), "members": 7, "raw_imports": CAP_RAW}}))
        f.commit("feat: grow StateIngest")
        out = self.bad(f, "capability")
        self.assertIn("StateIngest has 7 members, above the ceiling 6", out)


class SubstitutionUnitTests(unittest.TestCase):
    """The token-level substitution check of the capability mode, without git (T054b)."""

    class Cap:
        maps = {("appStateStorage", ".", "get"): ("stateIngest", ".", "get"),
                ("appStateStorage",): ("stateIngest", ".", "appStateStorage")}

    def check(self, old, new):
        return V().substitution_check("F.scala", old, new, self.Cap)[0]

    def test_single_expression_body_may_join_its_equals_line(self):
        self.assertEqual(self.check("  def f =\n    val x =\n      appStateStorage.get()\n    x\n",
                                    "  def f =\n    val x = stateIngest.get()\n    x\n"), [])

    def test_two_statement_body_may_not_join_its_equals_line(self):
        probs = self.check("  def f =\n    val x =\n      appStateStorage.get()\n      b()\n    x\n",
                           "  def f =\n    val x = stateIngest.get()\n      b()\n    x\n")
        self.assertIn("a statement starts a line on one side only", probs[0])

    def test_argument_lists_may_be_rewrapped(self):
        self.assertEqual(self.check("  foo(\n    a,\n    appStateStorage.get()\n  )\n", "  foo(a, stateIngest.get())\n"), [])

    def test_interpolations_are_code(self):
        self.assertEqual(self.check('  log(s"x ${appStateStorage.get()} $y")\n', '  log(s"x ${stateIngest.get()} $y")\n'), [])
        self.assertTrue(self.check('  log(s"x ${appStateStorage.get()} $y")\n', '  log(s"x ${stateIngest.get()} $z")\n'))
        self.assertTrue(self.check('  log("appStateStorage.get()")\n', '  log("stateIngest.get()")\n'))  # a plain string is data

    def test_only_a_bare_handle_is_substituted(self):
        self.assertTrue(self.check("  other.appStateStorage.get()\n", "  other.stateIngest.get()\n"))
        self.assertEqual(self.check("  f(appStateStorage = appStateStorage)\n", "  f(appStateStorage = stateIngest.appStateStorage)\n"), [])

    def test_keeping_a_handle_is_allowed_and_a_comment_or_import_change_is_ignored(self):
        self.assertEqual(self.check("import a.B\n  appStateStorage.get() // x\n", "import a.C\n  // y\n  appStateStorage.get()\n"), [])


# ---- adoption mode (spec 016 T060b): a coordinator, its factory, its spawn sites and the recovery actors move from raw
# storage parameters onto StateIngest

ACT = f"{SNAP}/actors"
BCC = f"{ACT}/ByteCodeCoordinator.scala"
BCC_MOD = f"{ACT}/bytecode/ByteCodeResponseHandling.scala"
CF = f"{CTRL}/ChildFactories.scala"
DS = f"{CTRL}/DownloadSupervisor.scala"
HO = f"{CTRL}/HealingOrchestrator.scala"
PS = f"{CTRL}/PivotSelector.scala"
SYNC = "src/main/scala/com/chipprbots/ethereum/blockchain/sync"
BRA = f"{SYNC}/BytecodeRecoveryActor.scala"
SC = f"{SYNC}/SyncController.scala"
CFS = "src/test/scala/com/chipprbots/ethereum/blockchain/sync/snap/ChildFactoriesSpec.scala"
ADOPT_MSG = "refactor(snap): ByteCodeCoordinator takes StateIngest (#1401)\n\n# adopts: StateIngest ByteCodeCoordinator\n"
RECOVERY_MSG = "refactor(sync): recovery actors take StateIngest (#1401)\n\n# adopts: StateIngest RecoveryActors\n"

# the real StateIngest's shape (T055): nine members, eight bare map lines, an enum policy, apply and Stores
SI_FILE = """package x.controller

// capability-map: appStateStorage -> appState
// capability-map: stateStorage -> stateTries
// capability-map: flatSlotStorage -> flatSlots
// capability-map: pathNodeStorageOpt -> pathNodes
// capability-map: evmCodeStorage -> bytecode
// capability-map: blockchainReader -> chainReader
// capability-map: blockchainWriter -> chainWriter
// capability-map: validatorFactory -> validatorFactory
private[snap] trait StateIngest:
  def appState: AppStateStorage
  def stateTries: StateStorage
  def flatSlots: FlatSlotStorage
  def pathNodes: Option[PathNodeStorage]
  def bytecode: EvmCodeStorage
  def chainReader: BlockchainReader
  def chainWriter: BlockchainWriter
  def validatorFactory: MptStorage => StateValidator
  def durability: StateIngest.Durability

private[snap] object StateIngest:

  enum Durability:
    case Default

  def apply(
      appState: AppStateStorage,
      stateTries: StateStorage,
      flatSlots: FlatSlotStorage,
      pathNodes: Option[PathNodeStorage],
      bytecode: EvmCodeStorage,
      chainReader: BlockchainReader,
      chainWriter: BlockchainWriter,
      validatorFactory: MptStorage => StateValidator,
      durability: Durability
  ): StateIngest =
    new Stores(appState, stateTries, flatSlots, pathNodes, bytecode, chainReader, chainWriter, validatorFactory, durability)

  final private class Stores(
      val appState: AppStateStorage,
      val stateTries: StateStorage,
      val flatSlots: FlatSlotStorage,
      val pathNodes: Option[PathNodeStorage],
      val bytecode: EvmCodeStorage,
      val chainReader: BlockchainReader,
      val chainWriter: BlockchainWriter,
      val validatorFactory: MptStorage => StateValidator,
      val durability: Durability
  ) extends StateIngest
"""

# the controller core: the accessor (T055) and the derivations SyncController mirrors (D-T060-2)
SI_CORE = """package x

class SNAPSyncControllerImpl(
    val appStateStorage: AppStateStorage,
    val stateStorage: StateStorage,
    val evmCodeStorage: EvmCodeStorage,
    val flatSlotStorage: FlatSlotStorage,
    val blockchainReader: BlockchainReader,
    val blockchainWriter: BlockchainWriter,
    val validatorFactory: MptStorage => StateValidator,
    val snapSyncConfig: SNAPSyncConfig
) extends SnapControllerEnv:

  val pathNodeStorageOpt: Option[PathNodeStorage] =
    if snapSyncConfig.storageScheme == StorageScheme.Path then Some(new PathNodeStorage(flatSlotStorage.dataSource))
    else None

  val stateIngest: StateIngest = StateIngest(
    appStateStorage,
    stateStorage,
    flatSlotStorage,
    pathNodeStorageOpt,
    evmCodeStorage,
    blockchainReader,
    blockchainWriter,
    validatorFactory,
    StateIngest.Durability.Default
  )

  val storageDoneStorage = new SnapStorageDoneStorage(flatSlotStorage.dataSource)

  def other(): Int = stateIngest.appState.getBestBlockNumber()

object SNAPSyncControllerImpl:

  def apply(
      appStateStorage: AppStateStorage,
      validatorFactory: MptStorage => StateValidator = new StateValidator(_)
  ): Behavior[Command] = ???
"""

BCC_BASE = """package x.actors

import x.db.storage.EvmCodeStorage

/** Bytecode download coordinator (fixture of the real shape). */
private class ByteCodeCoordinatorImpl(
    context: ActorContext[ByteCodeCoordinator.Command],
    timers: TimerScheduler[ByteCodeCoordinator.Command],
    evmCodeStorage: EvmCodeStorage,
    batchSize: Int,
    intakeBudget: Option[SnapIntakeBudget] = None
):

  private val log = context.log

  def start(received: Seq[Hash], skipPresent: Boolean): Behavior[Command] =
    val codeHashes = if skipPresent then received.filter(h => evmCodeStorage.get(h).isEmpty) else received
    log.info(s"queued ${codeHashes.size}")
    Behaviors.same

  def store(codesByHash: Map[Hash, Code]): Unit =
    val updates = codesByHash.foldLeft(evmCodeStorage.emptyBatchUpdate) { case (batchUpdate, (codeHash, code)) =>
      batchUpdate.and(evmCodeStorage.put(codeHash, code))
    }
    updates.commit()

object ByteCodeCoordinator:

  sealed trait Command

  def apply(
      evmCodeStorage: EvmCodeStorage,
      batchSize: Int,
      backpressureHighWatermark: Int = 50000,
      intakeBudget: Option[SnapIntakeBudget] = None
  ): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new ByteCodeCoordinatorImpl(
          context,
          timers,
          evmCodeStorage,
          batchSize,
          intakeBudget = intakeBudget
        ).start(Nil, false)
      }
    }
"""

# the C-B1 shape: Impl constructor, apply and its `new` call take `stateIngest`; the body reads `stateIngest.bytecode`
BCC_AFTER = (BCC_BASE
             .replace("import x.db.storage.EvmCodeStorage", "import x.controller.StateIngest")
             .replace("    evmCodeStorage: EvmCodeStorage,\n    batchSize", "    stateIngest: StateIngest,\n    batchSize")
             .replace("evmCodeStorage.get(h)", "stateIngest.bytecode.get(h)")
             .replace("evmCodeStorage.emptyBatchUpdate", "stateIngest.bytecode.emptyBatchUpdate")
             .replace("evmCodeStorage.put(codeHash", "stateIngest.bytecode.put(codeHash")
             .replace("      evmCodeStorage: EvmCodeStorage,\n      batchSize", "      stateIngest: StateIngest,\n      batchSize")
             .replace("          evmCodeStorage,\n", "          stateIngest,\n"))

CF_BASE = """package x.controller

trait ChildFactories:

  def byteCodeCoordinator(
      evmCodeStorage: EvmCodeStorage,
      batchSize: Int,
      backpressureHighWatermark: Int = 50000,
      intakeBudget: Option[SnapIntakeBudget] = None
  ): Behavior[ByteCodeCoordinator.Command] =
    ByteCodeCoordinator(
      evmCodeStorage = evmCodeStorage,
      batchSize = batchSize,
      backpressureHighWatermark = backpressureHighWatermark,
      intakeBudget = intakeBudget
    )

  def storageRangeCoordinator(
      mptStorage: MptStorage,
      flatSlotStorage: FlatSlotStorage
  ): Behavior[StorageRangeCoordinator.Command] =
    StorageRangeCoordinator(mptStorage = mptStorage, flatSlotStorage = flatSlotStorage)
"""

CF_AFTER = (CF_BASE
            .replace("      evmCodeStorage: EvmCodeStorage,\n", "      stateIngest: StateIngest,\n")
            .replace("      evmCodeStorage = evmCodeStorage,\n", "      stateIngest = stateIngest,\n"))

DS_BASE = """package x.controller

private[snap] trait DownloadSupervisor:
  self: DownloadSupervisorState & SnapControllerEnv =>

  def launchBytecode(): Unit =
    bytecodeCoordinator = Some(
      ctx.spawn(
        Behaviors
          .supervise(
            childFactories.byteCodeCoordinator(
              evmCodeStorage = stateIngest.bytecode,
              batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE,
              intakeBudget = Some(intakeBudget)
            )
          )
          .onFailure[Throwable](SupervisorStrategy.restart),
        s"bytecode-coordinator-$coordinatorGeneration"
      )
    )
"""
DS_AFTER = DS_BASE.replace("evmCodeStorage = stateIngest.bytecode,", "stateIngest = stateIngest,")

HO_BASE = """package x.controller

private[snap] trait HealingOrchestrator:
  self: HealingOrchestratorState & SnapControllerEnv =>

  def healCode(): Unit =
    ctx.spawn(childFactories.byteCodeCoordinator(evmCodeStorage = stateIngest.bytecode, batchSize = 16), "bcc")
    ctx.spawn(childFactories.trieNodeHealingCoordinator(evmCodeStorage = Some(stateIngest.bytecode)), "heal")
"""
HO_AFTER = HO_BASE.replace("byteCodeCoordinator(evmCodeStorage = stateIngest.bytecode,", "byteCodeCoordinator(stateIngest = stateIngest,")

# the D-T060-2 shape: SyncController builds one StateIngest and hands it to the recovery actor
BRA_BASE = """package x.sync

object BytecodeRecoveryActor:

  def apply(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: PeerManager,
      pivotBlockNumber: BigInt
  ): Behavior[Command] = scanning(
    stateRoot,
    stateStorage,
    evmCodeStorage,
    appStateStorage,
    networkPeerManager,
    pivotBlockNumber,
    preloaded = None
  )

  private def scanning(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      appStateStorage: AppStateStorage,
      networkPeerManager: PeerManager,
      pivotBlockNumber: BigInt,
      preloaded: Option[Seq[ByteString]]
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      val missing = preloaded.getOrElse(scanForMissingBytecodes(stateRoot, stateStorage, evmCodeStorage, pivotBlockNumber))
      if missing.isEmpty then
        appStateStorage.bytecodeRecoveryDone().commit()
        Behaviors.stopped
      else
        val coordinator = ctx.spawn(
          snap.actors.ByteCodeCoordinator(
            evmCodeStorage = evmCodeStorage,
            batchSize = snap.ByteCodeTask.DEFAULT_BATCH_SIZE
          ),
          "bytecode-recovery-coordinator"
        )
        downloading(ctx, coordinator, missing.size, appStateStorage)
    }

  private def downloading(ctx: Ctx, coordinator: Ref, total: Int, appStateStorage: AppStateStorage): Behavior[Command] =
    Behaviors.receiveMessage { case Done =>
      appStateStorage.bytecodeRecoveryDone().commit()
      Behaviors.stopped
    }

  private def scanForMissingBytecodes(
      stateRoot: ByteString,
      stateStorage: StateStorage,
      evmCodeStorage: EvmCodeStorage,
      pivot: BigInt
  ): Seq[ByteString] = stateStorage.getReadOnlyStorage.missing(evmCodeStorage)
"""

BRA_AFTER = (BRA_BASE
             .replace("      stateStorage: StateStorage,\n      evmCodeStorage: EvmCodeStorage,\n      appStateStorage: AppStateStorage,\n"
                      "      networkPeerManager", "      stateIngest: StateIngest,\n      networkPeerManager")
             .replace("    stateStorage,\n    evmCodeStorage,\n    appStateStorage,\n", "    stateIngest,\n")
             .replace("scanForMissingBytecodes(stateRoot, stateStorage, evmCodeStorage, pivotBlockNumber)",
                      "scanForMissingBytecodes(stateRoot, stateIngest.stateTries, stateIngest.bytecode, pivotBlockNumber)")
             .replace("        appStateStorage.bytecodeRecoveryDone()", "        stateIngest.appState.bytecodeRecoveryDone()")
             .replace("evmCodeStorage = evmCodeStorage,", "evmCodeStorage = stateIngest.bytecode,")
             .replace("downloading(ctx, coordinator, missing.size, appStateStorage)", "downloading(ctx, coordinator, missing.size, stateIngest)")
             .replace("total: Int, appStateStorage: AppStateStorage)", "total: Int, stateIngest: StateIngest)")
             .replace("      appStateStorage.bytecodeRecoveryDone()", "      stateIngest.appState.bytecodeRecoveryDone()"))

# after C-B1 the recovery actor passes the capability itself
BRA_CB1 = BRA_AFTER.replace("evmCodeStorage = stateIngest.bytecode,", "stateIngest = stateIngest,")

SC_BASE = """package x.sync

object SyncController:

  private class Impl(
      blockchainReader: BlockchainReader,
      blockchainWriter: BlockchainWriter,
      appStateStorage: AppStateStorage,
      evmCodeStorage: EvmCodeStorage,
      stateStorage: StateStorage,
      flatSlotStorage: FlatSlotStorage,
      networkPeerManager: PeerManager
  ):

    def startRecovery(stateRoot: ByteString, pivotBlock: BigInt): Behavior[Command] =
      val snapSyncConfig = loadSnapSyncConfig()
      val bytecodeActor = ctx.spawn(
        BytecodeRecoveryActor(
          stateRoot,
          stateStorage,
          evmCodeStorage,
          appStateStorage,
          networkPeerManager,
          pivotBlock
        ),
        s"bytecode-recovery-$syncGeneration"
      )
      runningRecovery(bytecodeActor, snapSyncConfig)

    def runningRecovery(actor: Ref, snapSyncConfig: SNAPSyncConfig): Behavior[Command] =
      Behaviors.same
"""

SC_INGEST = """      val pathNodeStorageOpt: Option[PathNodeStorage] =
        if snapSyncConfig.storageScheme == StorageScheme.Path then Some(new PathNodeStorage(flatSlotStorage.dataSource))
        else None
      val validatorFactory: MptStorage => StateValidator = new StateValidator(_)
      val stateIngest: StateIngest = StateIngest(
        appStateStorage,
        stateStorage,
        flatSlotStorage,
        pathNodeStorageOpt,
        evmCodeStorage,
        blockchainReader,
        blockchainWriter,
        validatorFactory,
        StateIngest.Durability.Default
      )
"""

SC_AFTER = (SC_BASE
            .replace("      val snapSyncConfig = loadSnapSyncConfig()\n", "      val snapSyncConfig = loadSnapSyncConfig()\n" + SC_INGEST)
            .replace("          stateStorage,\n          evmCodeStorage,\n          appStateStorage,\n", "          stateIngest,\n")
            .replace("runningRecovery(bytecodeActor, snapSyncConfig)", "runningRecovery(bytecodeActor, snapSyncConfig, stateIngest)")
            .replace("snapSyncConfig: SNAPSyncConfig): Behavior", "snapSyncConfig: SNAPSyncConfig, stateIngest: StateIngest): Behavior"))

SI_SYNC = SI_FILE.replace("private[snap] trait StateIngest:", "private[sync] trait StateIngest:").replace(
    "private[snap] object StateIngest:", "private[sync] object StateIngest:")

ADOPT_FILES = {CORE: SI_CORE, CAP: SI_FILE, BCC: BCC_BASE, CF: CF_BASE, DS: DS_BASE, HO: HO_BASE, BRA: BRA_BASE, SC: SC_BASE,
               PS: "package x.controller\n\nprivate[snap] trait PivotSelector:\n  def pick(): Int = 1\n"}


def adopt_baseline(files, members=9, just=None, counter=None, caps_members=None):
    measured = {rel(k): v for k, v in files.items() if k.startswith(SNAP + "/")}
    b = json.loads(cap_baseline(measured, pinned=(), env_members=0, header="", just=just,
                                caps={"StateIngest": {"file": rel(CAP), "members": caps_members or members, "raw_imports": []}}))
    if counter:
        b["counter"] = counter
        refs = {k: n for k, t in measured.items() if k != rel(CAP) and (n := V().storage_ref_count(
            t, [V().cap_exemption("StateIngest", measured[rel(CAP)])], counter))}
        b["storage_refs"], b["storage_refs_total"] = refs, sum(refs.values())
    return json.dumps(b, indent=2) + "\n"


def adopt_fixture(branch="refactor/snap-016-bcc", files=None, protect=False):
    files = dict(files or ADOPT_FILES)
    f = Fixture(branch=branch, core_base=files[CORE], extra={**{k: v for k, v in files.items() if k != CORE},
                                                              BASELINE: adopt_baseline(files)})
    f.files = files
    if protect:
        (f.cfg() / "protected-paths.txt").write_text(CFS + "\n")
    return f


def adopt_commit(f, changes, msg=ADOPT_MSG, **bl):
    f.files = {**f.files, **{k: v for k, v in changes.items() if v is not None}}
    for path, text in changes.items():
        f.write(path, text)
    f.write(BASELINE, adopt_baseline(f.files, **bl))
    f.commit(msg)


CB1 = {BCC: BCC_AFTER, CF: CF_AFTER, DS: DS_AFTER, HO: HO_AFTER}


class AdoptionModeTests(unittest.TestCase):
    ok = VerifyTests.ok
    bad = VerifyTests.bad

    def recovery_first(self, f):
        """D-T060-2 lands first: SyncController builds StateIngest and the recovery actor takes it."""
        adopt_commit(f, {SC: SC_AFTER, BRA: BRA_AFTER, CAP: SI_SYNC}, msg=RECOVERY_MSG)

    # ---- positives
    def test_cb1_shaped_adoption_passes_and_lowers_the_total(self):
        f = adopt_fixture()
        self.recovery_first(f)
        adopt_commit(f, {**CB1, BRA: BRA_CB1})
        out = self.ok(f)
        self.assertIn("[adopts] StateIngest ByteCodeCoordinator (standalone commit)", out)
        self.assertIn("[adopts] ByteCodeCoordinator: drops evmCodeStorage -> stateIngest.bytecode", out)
        self.assertIn(f"[adopts] {BCC}: 3 adopted declaration/call list(s), 3 substitution(s)", out)
        self.assertIn(f"[adopts] {CF}: 2 adopted declaration/call list(s), 0 substitution(s)", out)
        self.assertIn(f"[adopts] {DS}: 1 adopted declaration/call list(s)", out)
        self.assertIn(f"[adopts] {BRA}: 1 adopted declaration/call list(s)", out)
        # BCC 10 -> 4, ChildFactories 12 -> 8, HealingOrchestrator 4 -> 2, DownloadSupervisor 2 -> 0
        self.assertIn("[storage] total: 47 (baseline 47, ceiling 61)", out)

    def test_recovery_actors_shaped_adoption_passes(self):
        f = adopt_fixture(branch="refactor/snap-016-recovery-ingest")
        self.recovery_first(f)
        out = self.ok(f)
        self.assertIn("[adopts] StateIngest RecoveryActors (standalone commit)", out)
        self.assertIn(f"[adopts] {CAP}: visibility widened on 2 line(s)", out)
        self.assertIn(f"[adopts] {BRA}: 5 adopted declaration/call list(s), 5 substitution(s)", out)
        self.assertIn(f"[adopts] {SC}: 3 adopted declaration/call list(s), 0 substitution(s)", out)

    def narrowing_fixture(self, module_body=None):
        """D-T060-2 already merged; the slice's move commit puts store() into a module with the temporary self-type."""
        store = BCC_BASE[BCC_BASE.index("  def store("):BCC_BASE.index("object ByteCodeCoordinator:")]
        f = adopt_fixture(files={**ADOPT_FILES, SC: SC_AFTER, BRA: BRA_AFTER, CAP: SI_SYNC})
        moved_core = BCC_BASE.replace("):\n\n  private val log", ") extends ByteCodeResponseHandling:\n\n  private val log").replace(store, "")
        module = ("package x.actors.bytecode\n\nprivate[actors] trait ByteCodeResponseHandling:\n"
                  "  self: ByteCodeCoordinatorImpl =>\n\n" + (module_body or store))
        adopt_commit(f, {BCC: moved_core, BCC_MOD: module}, msg="refactor(snap): move store (#1401)\n\n# moved: store\n")
        return f, store

    def test_adoption_in_the_narrowing_commit_passes(self):
        # the narrowing commit swaps the self-type for a State trait and adopts StateIngest in the same commit
        f, store = self.narrowing_fixture()
        store_after = BCC_AFTER[BCC_AFTER.index("  def store("):BCC_AFTER.index("object ByteCodeCoordinator:")]
        narrowed_core = BCC_AFTER.replace("):\n\n  private val log", ") extends ByteCodeResponseHandling\n    with ByteCodeResponseHandlingState:\n\n  private val log").replace(
            store_after, "").replace("    stateIngest: StateIngest,\n    batchSize", "    val stateIngest: StateIngest,\n    batchSize")
        narrowed_module = ("package x.actors.bytecode\n\nprivate[actors] trait ByteCodeResponseHandlingState:\n"
                           "  def stateIngest: StateIngest\n\nprivate[actors] trait ByteCodeResponseHandling:\n"
                           "  self: ByteCodeResponseHandlingState =>\n\n" + store_after)
        f.write("src/test/scala/x/StubSpec.scala",
                "package x\nclass StubSpec:\n  val s = new StubByteCodeResponseHandlingState with ByteCodeResponseHandling\n")
        adopt_commit(f, {**CB1, BCC: narrowed_core, BCC_MOD: narrowed_module, BRA: BRA_CB1},
                     msg=ADOPT_MSG + "# new-tests: src/test/scala/x/StubSpec.scala\n")
        out = self.ok(f)
        self.assertIn("narrowing commit: step 6 runs inside the adoption check", out)
        self.assertIn(f"[adopts] {BCC_MOD}: 0 adopted declaration/call list(s), 2 substitution(s)", out)
        self.assertIn(f"[adopts] {BCC}: 3 adopted declaration/call list(s), 1 substitution(s)", out)
        self.assertIn("[counts] ByteCodeResponseHandling: State=ByteCodeResponseHandlingState(1)", out)

    # ---- companion-qualified defaults (spec 016 C-H1): `apply` sits in the companion and writes `X`, ChildFactories
    # writes `<Coordinator>.X`; both name the same constant
    def qualified_fixture(self, cf_default):
        def q(t):
            head, obj = t.split("object ByteCodeCoordinator:\n")
            assert "backpressureHighWatermark: Int = 50000," in obj
            obj = obj.replace("backpressureHighWatermark: Int = 50000,", "backpressureHighWatermark: Int = DefaultHighWatermark,")
            return (head + "object ByteCodeCoordinator:\n\n  val DefaultHighWatermark: Int = 50000\n  val OtherMark: Int = 7\n"
                    + obj)
        c = lambda t: t.replace("backpressureHighWatermark: Int = 50000,", f"backpressureHighWatermark: Int = {cf_default},")
        f = adopt_fixture(files={**ADOPT_FILES, BCC: q(BCC_BASE), CF: c(CF_BASE)})
        self.recovery_first(f)
        adopt_commit(f, {**CB1, BCC: q(BCC_AFTER), CF: c(CF_AFTER), BRA: BRA_CB1})
        return f

    def test_companion_qualified_default_matches_apply(self):
        self.ok(self.qualified_fixture("ByteCodeCoordinator.DefaultHighWatermark"))

    def test_other_qualified_default_still_differs(self):
        for d in ("ByteCodeCoordinator.OtherMark", "Elsewhere.DefaultHighWatermark", "50000"):
            out = self.bad(self.qualified_fixture(d), "adopts")
            self.assertIn("`byteCodeCoordinator` default of `backpressureHighWatermark` differs from `apply`'s", out, d)

    def test_qualified_default_unit(self):
        members = V().companion_members("package x\n\nobject C:\n  val A: Int = 1\n  def b(): Int = 2\n\nclass D\n", "C")
        self.assertEqual(members, {"A", "b"})
        self.assertEqual(V().qualified_default(("C", ".", "A"), "C", members), ("A",))
        self.assertEqual(V().qualified_default(("C", ".", "Z"), "C", members), ("C", ".", "Z"))
        self.assertEqual(V().qualified_default(("E", ".", "A"), "C", members), ("E", ".", "A"))
        self.assertIsNone(V().qualified_default(None, "C", members))

    # ---- negatives: logic hidden in the adoption
    def neg(self, changes, check="adopts", msg=ADOPT_MSG, recovery=True, fixture=None, **bl):
        f = fixture or adopt_fixture()
        if recovery:
            self.recovery_first(f)
        adopt_commit(f, {**CB1, BRA: BRA_CB1, **changes}, msg=msg, **bl)
        return self.bad(f, check)

    def test_logic_change_in_apply_fails(self):
        out = self.neg({BCC: BCC_AFTER.replace("          batchSize,\n", "          batchSize * 2,\n")})
        self.assertIn("a call to `new ByteCodeCoordinatorImpl` changes an argument it keeps", out)

    def test_logic_change_in_a_spawn_site_fails(self):
        out = self.neg({DS: DS_AFTER.replace("batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE,", "batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE + 1,")})
        self.assertIn("a call to `byteCodeCoordinator` changes an argument it keeps", out)

    def test_spawn_site_dropping_a_non_storage_argument_fails(self):
        out = self.neg({DS: DS_AFTER.replace("              intakeBudget = Some(intakeBudget)\n", "").replace(
            "batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE,", "batchSize = ByteCodeTask.DEFAULT_BATCH_SIZE")})
        self.assertIn("changes an argument it keeps", out)

    def test_spawn_site_passing_another_capability_fails(self):
        out = self.neg({DS: DS_AFTER.replace("stateIngest = stateIngest,", "stateIngest = otherIngest,")})
        self.assertIn("pass the capability `stateIngest` itself", out)

    def test_logic_change_beside_a_substitution_fails(self):
        out = self.neg({BCC: BCC_AFTER.replace("updates.commit()", "updates.commitSync()")})
        self.assertIn("not an adoption rewrite", out)

    def test_substitution_to_the_wrong_member_fails(self):
        out = self.neg({BCC: BCC_AFTER.replace("stateIngest.bytecode.get(h)", "stateIngest.stateTries.get(h)")})
        self.assertIn("not an adoption rewrite", out)

    def test_adopt_map_overriding_a_bare_capability_entry_fails(self):
        # prism 6: `evmCodeStorage` already maps to `bytecode` through the capability's bare entry
        out = self.neg({BCC: "// adopt-map: evmCodeStorage -> appState\n" + BCC_AFTER})
        self.assertIn("adopt-map `evmCodeStorage -> appState` overrides the capability's bare entry", out)

    def test_adopt_map_to_a_member_of_another_type_fails(self):
        base = BCC_BASE.replace("evmCodeStorage", "codeStore")
        f = adopt_fixture(files={**ADOPT_FILES, BCC: base})
        self.recovery_first(f)
        after = "// adopt-map: codeStore -> appState\n" + BCC_AFTER.replace("stateIngest.bytecode", "stateIngest.appState")
        adopt_commit(f, {**CB1, BCC: after, BRA: BRA_CB1})
        self.assertIn("drops `codeStore: EvmCodeStorage`, but `stateIngest.appState` is `AppStateStorage`", self.bad(f, "adopts"))

    # ---- prism review of 4470da23c
    def test_p1_imports_that_can_rebind_names_fail(self):
        for imp, why in (("import scala.concurrent.ExecutionContext.Implicits.global", "is new to the file"),
                         ("import x.other.Hash", "would rebind its existing bare uses"),
                         ("import x.Syntax.given", "wildcard or given import"),
                         ("import x.Evil.*", "wildcard or given import"),
                         ("import x.evil.StateIngest", "which is not the capability"),
                         ("import x.db.{EvmCodeStorage => Hash}", "renaming import")):
            out = self.neg({BCC: BCC_AFTER.replace("import x.controller.StateIngest", "import x.controller.StateIngest\n" + imp)})
            self.assertIn(why, out, imp)

    def test_p1_capability_import_and_a_once_qualified_name_pass(self):
        f = adopt_fixture(files={**ADOPT_FILES, BCC: BCC_BASE.replace("Behaviors.same\n", "x.util.Clock.now()\n    Behaviors.same\n")})
        self.recovery_first(f)
        after = BCC_AFTER.replace("Behaviors.same\n", "x.util.Clock.now()\n    Behaviors.same\n").replace(
            "import x.controller.StateIngest", "import x.controller.StateIngest\nimport x.util.Clock")
        adopt_commit(f, {**CB1, BCC: after, BRA: BRA_CB1})
        self.ok(f)

    def test_p4_rewrite_under_a_shadowing_binding_fails(self):
        helper = "\n  def probe(h: Hash, evmCodeStorage: EvmCodeStorage): Boolean =\n    evmCodeStorage.get(h).isEmpty\n"
        local = "\n  def local(h: Hash): Boolean =\n    val evmCodeStorage = backup\n    evmCodeStorage.get(h).isEmpty\n"
        for extra, rewritten in ((helper, helper.replace("    evmCodeStorage.get", "    stateIngest.bytecode.get")),
                                 (local, local.replace("    evmCodeStorage.get", "    stateIngest.bytecode.get"))):
            anchor = "\nobject ByteCodeCoordinator:"
            f = adopt_fixture(files={**ADOPT_FILES, BCC: BCC_BASE.replace(anchor, extra + anchor)})
            self.recovery_first(f)
            adopt_commit(f, {**CB1, BCC: BCC_AFTER.replace(anchor, rewritten + anchor), BRA: BRA_CB1})
            self.assertIn("binds another `evmCodeStorage` in this file", self.bad(f, "adopts"))
        # file-wide in the coordinator (prism round 2): even a helper keeping its own parameter unrewritten rejects
        f = adopt_fixture(files={**ADOPT_FILES, BCC: BCC_BASE.replace(anchor, helper + anchor)})
        self.recovery_first(f)
        adopt_commit(f, {**CB1, BCC: BCC_AFTER.replace(anchor, helper + anchor), BRA: BRA_CB1})
        self.assertIn("binds another `evmCodeStorage` in this file", self.bad(f, "adopts"))

    def test_p4b_every_binding_form_of_a_dropped_name_rejects(self):
        anchor = "\nobject ByteCodeCoordinator:"
        for form in ("  def a(o: Option[EvmCodeStorage]): Int = o match\n    case Some(evmCodeStorage) => 1\n    case None => 0\n",
                     "  def b(xs: Seq[EvmCodeStorage]) = for evmCodeStorage <- xs yield 1\n",
                     "  def c(t: (Int, EvmCodeStorage)) =\n    val (a, evmCodeStorage) = t\n    a\n",
                     "  def d()(using evmCodeStorage: EvmCodeStorage): Int = 1\n",
                     "  def e(xs: Seq[EvmCodeStorage]) = xs.map(evmCodeStorage => 1)\n"):
            extra = "\n" + form
            f = adopt_fixture(files={**ADOPT_FILES, BCC: BCC_BASE.replace(anchor, extra + anchor)})
            self.recovery_first(f)
            adopt_commit(f, {**CB1, BCC: BCC_AFTER.replace(anchor, extra + anchor), BRA: BRA_CB1})
            self.assertIn("binds another `evmCodeStorage` in this file", self.bad(f, "adopts"), form)
        helper = "\nclass Helper(val evmCodeStorage: EvmCodeStorage):\n  def g(h: Hash) = evmCodeStorage.get(h)\n"
        f = adopt_fixture(files={**ADOPT_FILES, BCC: BCC_BASE + helper})
        self.recovery_first(f)
        adopt_commit(f, {**CB1, BCC: BCC_AFTER + helper, BRA: BRA_CB1})
        self.assertIn("binds another `evmCodeStorage` in this file", self.bad(f, "adopts"))

    def test_p4b_recovery_member_scope(self):
        # a binding in the same member as a rewrite rejects; an unchanged helper's own parameter (another member) does not
        ins = "      preloaded match\n        case Some(appStateStorage) => ()\n        case None => ()\n      if missing.isEmpty then\n"
        f = adopt_fixture(branch="refactor/snap-016-recovery-ingest",
                          files={**ADOPT_FILES, BRA: BRA_BASE.replace("      if missing.isEmpty then\n", ins)})
        adopt_commit(f, {SC: SC_AFTER, BRA: BRA_AFTER.replace("      if missing.isEmpty then\n", ins), CAP: SI_SYNC}, msg=RECOVERY_MSG)
        self.assertIn("binds another `appStateStorage` in the same member", self.bad(f, "adopts"))

    def p2_move(self, closer, mod_name="ByteCodeResponseHandling", path=None):
        store = BCC_BASE[BCC_BASE.index("  def store("):BCC_BASE.index("object ByteCodeCoordinator:")]
        f = adopt_fixture(files={**ADOPT_FILES, SC: SC_AFTER, BRA: BRA_AFTER, CAP: SI_SYNC})
        core = BCC_BASE.replace("):\n\n  private val log", closer + "\n\n  private val log").replace(store, "")
        module = (f"package x.actors.bytecode\n\nprivate[actors] trait {mod_name}:\n"
                  "  self: ByteCodeCoordinatorImpl =>\n\n" + store)
        adopt_commit(f, {path or BCC: core, f"{ACT}/bytecode/{mod_name}.scala": module},
                     msg="refactor(snap): move store (#1401)\n\n# moved: store\n# partial: narrowed in a later commit\n")
        return f

    def test_p2_move_commit_closer_admits_module_parents_only(self):
        self.ok(self.p2_move(") extends ByteCodeResponseHandling:"))
        for closer in (") extends ByteCodeResponseHandling with Audit(sideEffect()):", ") extends X(sideEffect(1))",
                       ") extends Unrelated:"):
            out = self.bad(self.p2_move(closer), "step1")
            self.assertIn("removed line is not shown as moved: `):`", out)

    def test_p2b_steps_1_and_6_admit_bare_identifier_parent_arguments_only(self):
        # prism round 2 (optional, taken: the replay of #1560's `with SyncLifecycle(heapWatchdogStart)` is unchanged)
        ok = CORE_AFTER_MOVE.replace("extends HealingOrchestrator:", "extends HealingOrchestrator with SyncLifecycle(heapWatchdogStart):")
        f = Fixture()
        move_commit(f, core=ok, partial=True)
        self.ok(f)
        for bad in ("with Audit(sideEffect()):", "with Audit(a + 1):"):
            f = Fixture()
            move_commit(f, core=CORE_AFTER_MOVE.replace("extends HealingOrchestrator:", "extends HealingOrchestrator " + bad), partial=True)
            self.assertIn("added line is neither moved nor a header", self.bad(f, "step1"), bad)

    def test_p2_narrowing_header_with_a_constructor_call_fails(self):
        f, store = self.narrowing_fixture()
        store_after = BCC_AFTER[BCC_AFTER.index("  def store("):BCC_AFTER.index("object ByteCodeCoordinator:")]
        core = BCC_AFTER.replace("):\n\n  private val log", ") extends ByteCodeResponseHandling\n    with Audit(sideEffect()):\n\n  private val log").replace(
            store_after, "").replace("    stateIngest: StateIngest,\n    batchSize", "    val stateIngest: StateIngest,\n    batchSize")
        module = ("package x.actors.bytecode\n\nprivate[actors] trait ByteCodeResponseHandlingState:\n  def stateIngest: StateIngest\n\n"
                  "private[actors] trait ByteCodeResponseHandling:\n  self: ByteCodeResponseHandlingState =>\n\n" + store_after)
        f.write("src/test/scala/x/StubSpec.scala", "package x\nclass S:\n  val s = new StubByteCodeResponseHandlingState with ByteCodeResponseHandling\n")
        adopt_commit(f, {**CB1, BCC: core, BCC_MOD: module, BRA: BRA_CB1}, msg=ADOPT_MSG + "# new-tests: src/test/scala/x/StubSpec.scala\n")
        self.assertIn("change is not an adoption rewrite", self.bad(f, "adopts"))

    def test_reordered_or_retyped_or_extra_parameter_fails(self):
        out = self.neg({BCC: BCC_AFTER.replace("      backpressureHighWatermark: Int = 50000,\n", "      backpressureHighWatermark: Int = 40000,\n")})
        self.assertIn("`apply` changes or reorders a parameter it keeps", out)
        out = self.neg({BCC: BCC_AFTER.replace("    stateIngest: StateIngest,\n    batchSize: Int,\n", "    batchSize: Int,\n    stateIngest: StateIngest,\n")})
        self.assertIn("takes `stateIngest` at position 4; it goes where the first dropped parameter `evmCodeStorage` was (position 3)", out)
        out = self.neg({BCC: BCC_AFTER.replace("      batchSize: Int,\n      backpressure", "      batchSize: Int,\n      extra: Int = 1,\n      backpressure")})
        self.assertIn("`apply` adds stateIngest, extra", out)

    def test_child_factories_out_of_step_with_apply_fails(self):
        out = self.neg({CF: CF_AFTER.replace("backpressureHighWatermark: Int = 50000", "backpressureHighWatermark: Int = 1")})
        self.assertIn("`byteCodeCoordinator` changes or reorders a parameter it keeps", out)

    def test_child_factories_spec_edit_fails(self):
        f = adopt_fixture(protect=True)
        f.write(CFS, "package x\nclass ChildFactoriesSpec\n")
        f.commit("test: seam")
        f.git("tag", "-f", "base")
        self.recovery_first(f)
        f.write(CFS, "package x\nclass ChildFactoriesSpec // edited\n")
        adopt_commit(f, {**CB1, BRA: BRA_CB1})
        self.assertIn("touches protected golden/pin file " + CFS, self.bad(f, "step4"))

    def test_change_outside_the_adoption_files_fails(self):
        out = self.neg({PS: "package x.controller\n\nprivate[snap] trait PivotSelector:\n  def pick(): Int = 2\n"})
        self.assertIn(f"changes {PS}, which is not part of the `ByteCodeCoordinator` adoption", out)

    def test_trailer_naming_a_coordinator_the_commit_does_not_adopt_fails(self):
        out = self.neg({}, msg=ADOPT_MSG.replace("ByteCodeCoordinator\n", "AccountRangeCoordinator\n"))
        self.assertIn("changes " + BCC + ", which is not part of the `AccountRangeCoordinator` adoption", out)

    def test_adoption_without_the_trailer_fails(self):
        out = self.neg({}, msg="refactor(snap): ByteCodeCoordinator takes StateIngest (#1401)\n")
        self.assertIn("drops storage parameter(s) evmCodeStorage outside an adoption commit", out)

    def test_trailer_on_a_move_commit_or_another_branch_fails(self):
        out = self.neg({}, msg=ADOPT_MSG + "# moved: store\n")
        self.assertIn("`# adopts:` on a `# moved:` commit", out)
        f = adopt_fixture(branch="test/snap-016-bcc")
        adopt_commit(f, CB1, msg=ADOPT_MSG + "# test-files: none\n")
        self.assertIn("an adoption is a refactor/snap-016-* commit", self.bad(f, "adopts"))

    def test_spawn_site_passing_both_the_store_and_the_capability_fails(self):
        out = self.neg({DS: DS_AFTER.replace("stateIngest = stateIngest,", "stateIngest = stateIngest,\n              evmCodeStorage = stateIngest.bytecode,")})
        self.assertIn("a call to `byteCodeCoordinator` whose arguments do not bind to its parameters", out)

    def test_narrowing_commit_with_a_body_change_fails(self):
        f, store = self.narrowing_fixture()
        store_after = BCC_AFTER[BCC_AFTER.index("  def store("):BCC_AFTER.index("object ByteCodeCoordinator:")]
        core = BCC_AFTER.replace("):\n\n  private val log", ") extends ByteCodeResponseHandling\n    with ByteCodeResponseHandlingState:\n\n  private val log").replace(
            store_after, "").replace("    stateIngest: StateIngest,\n    batchSize", "    val stateIngest: StateIngest,\n    batchSize")
        module = ("package x.actors.bytecode\n\nprivate[actors] trait ByteCodeResponseHandlingState:\n  def stateIngest: StateIngest\n\n"
                  "private[actors] trait ByteCodeResponseHandling:\n  self: ByteCodeResponseHandlingState =>\n\n"
                  + store_after.replace("updates.commit()", "if codesByHash.nonEmpty then updates.commit()"))
        f.write("src/test/scala/x/StubSpec.scala", "package x\nclass S:\n  val s = new StubByteCodeResponseHandlingState with ByteCodeResponseHandling\n")
        adopt_commit(f, {**CB1, BCC: core, BCC_MOD: module, BRA: BRA_CB1}, msg=ADOPT_MSG + "# new-tests: src/test/scala/x/StubSpec.scala\n")
        self.assertIn(f"{BCC_MOD}: change is not an adoption rewrite", self.bad(f, "adopts"))

    # ---- RecoveryActors (D-T060-2)
    def rneg(self, changes):
        f = adopt_fixture(branch="refactor/snap-016-recovery-ingest")
        adopt_commit(f, {SC: SC_AFTER, BRA: BRA_AFTER, CAP: SI_SYNC, **changes}, msg=RECOVERY_MSG)
        return self.bad(f, "adopts")

    def test_recovery_accessor_not_mirroring_the_core_fails(self):
        out = self.rneg({SC: SC_AFTER.replace("        stateStorage,\n        flatSlotStorage,\n", "        flatSlotStorage,\n        stateStorage,\n")})
        self.assertIn("is not SNAPSyncController's", out)

    def test_recovery_handle_derived_differently_fails(self):
        out = self.rneg({SC: SC_AFTER.replace("Some(new PathNodeStorage(flatSlotStorage.dataSource))", "Some(new PathNodeStorage(appStateStorage.dataSource))")})
        self.assertIn("which is not derived exactly as SNAPSyncController derives `pathNodeStorageOpt`", out)

    def test_recovery_logic_change_fails(self):
        out = self.rneg({BRA: BRA_AFTER.replace("if missing.isEmpty then", "if missing.size < 2 then")})
        self.assertIn(f"{BRA}: change is not an adoption rewrite", out)
        out = self.rneg({SC: SC_AFTER.replace("          pivotBlock\n", "          pivotBlock + 1\n")})
        self.assertIn("a call to `BytecodeRecoveryActor.apply` changes an argument it keeps", out)

    def test_recovery_dropping_a_handle_for_the_wrong_member_fails(self):
        out = self.rneg({SC: SC_AFTER.replace("      val bytecodeActor", "      val other = stateIngest\n      val bytecodeActor")})
        self.assertIn(f"{SC}: change is not an adoption rewrite", out)

    def test_p5_mirror_must_use_plain_vals(self):
        for a, bb in (("      val stateIngest: StateIngest = StateIngest(", "      lazy val stateIngest: StateIngest = StateIngest("),
                      ("      val pathNodeStorageOpt: Option[PathNodeStorage] =", "      def pathNodeStorageOpt: Option[PathNodeStorage] ="),
                      ("      val validatorFactory: MptStorage => StateValidator", "      lazy val validatorFactory: MptStorage => StateValidator")):
            out = self.rneg({SC: SC_AFTER.replace(a, bb)})
            self.assertIn("plain `val", out)

    def test_p1_sync_controller_may_import_the_mirrored_types_only(self):
        core = SI_CORE.replace("package x\n", "package x\n\nimport x.db.PathNodeStorage\nimport x.snap.StateValidator\n")
        fix = lambda: adopt_fixture(branch="refactor/snap-016-recovery-ingest", files={**ADOPT_FILES, CORE: core})
        sc = lambda imps: SC_AFTER.replace("object SyncController:", imps + "\n\nobject SyncController:")
        f = fix()
        adopt_commit(f, {SC: sc("import x.db.PathNodeStorage\nimport x.snap.StateValidator"), BRA: BRA_AFTER, CAP: SI_SYNC}, msg=RECOVERY_MSG)
        self.ok(f)
        # a type the core uses from its own package (package `x`, no import there) may be imported by that path only
        f = adopt_fixture(branch="refactor/snap-016-recovery-ingest")
        adopt_commit(f, {SC: sc("import x.StateValidator\nimport x.PathNodeStorage"), BRA: BRA_AFTER, CAP: SI_SYNC}, msg=RECOVERY_MSG)
        self.ok(f)
        # prism round 2: the import must be the core's own entry, and never a standard name
        for imp, why in (("import x.evil.StateValidator", "adds import `x.evil.StateValidator`"),
                         ("import x.evil.PathNodeStorage", "adds import `x.evil.PathNodeStorage`"),
                         ("import x.evil.Some", "rebinds the standard name `Some`"),
                         ("import x.other.Thing", "adds import `x.other.Thing`")):
            f = fix()
            adopt_commit(f, {SC: sc(imp), BRA: BRA_AFTER, CAP: SI_SYNC}, msg=RECOVERY_MSG)
            self.assertIn(why, self.bad(f, "adopts"), imp)

    def test_p5_trait_and_object_widen_together(self):
        out = self.rneg({CAP: SI_FILE.replace("private[snap] trait StateIngest:", "private[sync] trait StateIngest:")})
        self.assertIn("widens only one of `trait StateIngest` and `object StateIngest`", out)

    def test_capability_change_beyond_visibility_fails(self):
        out = self.rneg({CAP: SI_SYNC.replace("final private class Stores(", "final class Stores(")})
        self.assertIn("a RecoveryActors adoption only widens", out)

    def test_visibility_widening_without_the_trailer_fails(self):
        f = adopt_fixture(branch="refactor/snap-016-recovery-ingest")
        adopt_commit(f, {CAP: SI_SYNC}, msg="refactor: widen\n")
        self.assertIn("changes the visibility of StateIngest outside", self.bad(f, "adopts"))

    def test_recovery_actor_signature_change_without_the_trailer_fails(self):
        f = adopt_fixture(branch="refactor/snap-016-recovery-ingest")
        adopt_commit(f, {SC: SC_AFTER, BRA: BRA_AFTER}, msg="refactor: recovery\n")
        self.assertIn("drops storage parameter(s) stateStorage, evmCodeStorage, appStateStorage outside an adoption commit",
                      self.bad(f, "adopts"))


class AdoptionCallUnitTests(unittest.TestCase):
    """`Adoption.call_problem` without git: named and positional calls, partial and pure-addition cases."""

    def decl(self, old, new, member):
        p = lambda s: V().parse_param([t.t for t in V().scala_tokens(s)])
        d = V().AdoptDecl(("F", "f"), [p(x) for x in old], [p(x) for x in new])
        d.member = member
        return d

    def problem(self, d, old_call, new_call, equiv=None):
        a = V().Adoption.__new__(V().Adoption)
        items = lambda s: V().group_items([t.t for t in V().scala_tokens(s)])
        return a.call_problem(d, items(old_call), items(new_call), equiv or {})

    def test_named_positional_partial_and_pure_addition_calls(self):
        d = self.decl(["a: Int", "pathNodeStorage: Option[PathNodeStorage] = None", "storageDone: Option[SnapStorageDoneStorage] = None"],
                      ["a: Int", "stateIngest: StateIngest"], {"pathNodeStorage": "pathNodes", "storageDone": "storageDone"})
        ok = "a = 1, pathNodeStorage = stateIngest.pathNodes, storageDone = stateIngest.storageDone"
        self.assertIsNone(self.problem(d, ok, "a = 1, stateIngest = stateIngest"))
        self.assertIsNone(self.problem(d, "1, stateIngest.pathNodes, stateIngest.storageDone", "1, stateIngest"))
        # a call that relied on one default would now read it from the capability
        self.assertIn("took their defaults", self.problem(d, "a = 1, pathNodeStorage = stateIngest.pathNodes", "a = 1, stateIngest = stateIngest"))
        # a call that relied on every default may not start passing the capability
        self.assertIn("a behaviour change", self.problem(d, "a = 1", "a = 1, stateIngest = stateIngest"))
        # a dropped argument that is not the member it maps to
        self.assertIn("not `stateIngest.storageDone`", self.problem(
            d, "a = 1, pathNodeStorage = stateIngest.pathNodes, storageDone = None", "a = 1, stateIngest = stateIngest"))
        # a name equal to the member in this file (a dropped parameter, or a handle the file's capability is built from)
        self.assertIsNone(self.problem(d, "1, pns, done", "1, stateIngest", {("pns",): "pathNodes", ("done",): "storageDone"}))
        pure = self.decl(["a: Int"], ["a: Int", "stateIngest: StateIngest"], {})
        self.assertIsNone(self.problem(pure, "a", "a, stateIngest"))
        self.assertIn("exactly once", self.problem(pure, "a", "a"))


class CapabilityExtensionTests(unittest.TestCase):
    """D-T060-1: a later capability-mode PR adds members to StateIngest (a per-pivot trie-store method and a derived
    store), with the core's accessor, SyncController's mirror and the member cap moving together."""
    ok = VerifyTests.ok
    bad = VerifyTests.bad

    EXT_CAP = (SI_SYNC
               .replace("// capability-map: validatorFactory -> validatorFactory\n",
                        "// capability-map: validatorFactory -> validatorFactory\n"
                        "// capability-map: stateStorage.getBackingStorage -> trieStore\n"
                        "// capability-map: storageDoneStorage -> storageDone\n")
               .replace("  def durability: StateIngest.Durability\n",
                        "  def durability: StateIngest.Durability\n  def trieStore(pivot: BigInt): MptStorage\n"
                        "  def storageDone: SnapStorageDoneStorage\n")
               .replace("      durability: Durability\n  ): StateIngest =", "      durability: Durability,\n      storageDone: SnapStorageDoneStorage\n  ): StateIngest =")
               .replace("validatorFactory, durability)\n", "validatorFactory, durability, storageDone)\n")
               .replace("      val durability: Durability\n  ) extends StateIngest\n",
                        "      val durability: Durability,\n      val storageDone: SnapStorageDoneStorage\n  ) extends StateIngest:\n"
                        "    def trieStore(pivot: BigInt): MptStorage = stateTries.getBackingStorage(pivot)\n"))
    EXT_CORE = (SI_CORE
                .replace("    StateIngest.Durability.Default\n  )\n\n  val storageDoneStorage = new SnapStorageDoneStorage(flatSlotStorage.dataSource)\n",
                         "    StateIngest.Durability.Default,\n    storageDoneStorage\n  )\n")
                .replace("  val stateIngest: StateIngest = StateIngest(",
                         "  val storageDoneStorage = new SnapStorageDoneStorage(flatSlotStorage.dataSource)\n\n  val stateIngest: StateIngest = StateIngest(")
                .replace("stateIngest.appState.getBestBlockNumber()", "stateIngest.trieStore(1).root"))
    BASE_CORE = SI_CORE.replace("stateIngest.appState.getBestBlockNumber()", "stateIngest.stateTries.getBackingStorage(1).root")
    EXT_SC = SC_AFTER.replace("        StateIngest.Durability.Default\n      )\n",
                              "        StateIngest.Durability.Default,\n        storageDoneStorage\n      )\n").replace(
        "      val stateIngest: StateIngest", "      val storageDoneStorage = new SnapStorageDoneStorage(flatSlotStorage.dataSource)\n      val stateIngest: StateIngest")
    MSG = "refactor(snap): StateIngest derived stores (#1401)\n\n# capability: StateIngest\n"
    JUST = {"capabilities.StateIngest.members": "D-T060-1: trieStore (per-pivot MptStorage) and storageDone, same instances"}

    def fixture(self):
        files = {**ADOPT_FILES, CORE: self.BASE_CORE, CAP: SI_SYNC, SC: SC_AFTER, BRA: BRA_AFTER}
        f = Fixture(branch="refactor/snap-016-ingest-derived", core_base=files[CORE],
                    extra={**{k: v for k, v in files.items() if k != CORE}, BASELINE: adopt_baseline(files)})
        f.files = files
        return f

    def commit(self, f, changes, just=JUST, members=11):
        adopt_commit(f, changes, msg=self.MSG, just=just, members=members)

    def test_extension_shaped_like_d_t060_1_passes(self):
        f = self.fixture()
        self.commit(f, {CAP: self.EXT_CAP, CORE: self.EXT_CORE, SC: self.EXT_SC})
        out = self.ok(f)
        self.assertIn("(extension)", out)
        self.assertIn("[capability] extension: +2 member(s), new `apply` parameter(s): storageDone", out)
        self.assertIn(f"[capability] {SC}: mirrors the core's `stateIngest`", out)
        self.assertIn(f"[capability] {CORE}: 1 substitution(s)", out)
        self.assertIn("capability StateIngest (controller/StateIngest.scala): members=11 (baseline 11, ceiling 9)", out)

    def test_member_growth_without_a_justification_fails(self):
        f = self.fixture()
        self.commit(f, {CAP: self.EXT_CAP, CORE: self.EXT_CORE, SC: self.EXT_SC}, just={})
        self.assertIn("StateIngest has 11 members, above the ceiling 9", self.bad(f, "capability"))

    def test_impure_trie_store_forwarder_fails(self):
        f = self.fixture()
        self.commit(f, {CAP: self.EXT_CAP.replace("getBackingStorage(pivot)\n", "getBackingStorage(pivot + 1)\n"),
                        CORE: self.EXT_CORE, SC: self.EXT_SC})
        self.assertIn("forwarder `trieStore` is not a pure forwarder", self.bad(f, "capability"))

    def test_changing_an_existing_core_argument_fails(self):
        f = self.fixture()
        core = self.EXT_CORE.replace("    stateStorage,\n    flatSlotStorage,\n", "    stateStorage,\n    flatSlotStorage2,\n")
        self.commit(f, {CAP: self.EXT_CAP, CORE: core, SC: self.EXT_SC})
        self.assertIn("changes the existing argument for `flatSlots`", self.bad(f, "capability"))

    def test_sync_controller_out_of_step_with_the_core_fails(self):
        f = self.fixture()
        self.commit(f, {CAP: self.EXT_CAP, CORE: self.EXT_CORE})
        self.assertIn("is not SNAPSyncController's", self.bad(f, "capability"))

    def test_changing_an_existing_member_fails(self):
        f = self.fixture()
        self.commit(f, {CAP: self.EXT_CAP.replace("  def bytecode: EvmCodeStorage\n", "  def bytecode: Option[EvmCodeStorage]\n"),
                        CORE: self.EXT_CORE, SC: self.EXT_SC})
        self.assertIn("changes existing member `bytecode", self.bad(f, "capability"))


class LazyHandleTests(unittest.TestCase):
    """#1584 (spec 016 C-H1): a capability extension whose new members are fed, by-name, by a controller trait's lazy
    healing handles (HealingOrchestrator's frontier store and BFS queue). The handle widens to `private[snap]` or is the
    Option lift of a removed lazy val; SyncController's mirror declares it `None` and does not read the member."""
    ok = VerifyTests.ok
    bad = VerifyTests.bad

    HO_LAZY = """package x.controller

private[snap] trait HealingOrchestrator:
  self: HealingOrchestratorState & SnapControllerEnv =>

  private lazy val healingFrontierStorageOpt: Option[HealingFrontierStorage] =
    if snapSyncConfig.healingFrontierPersistence then Some(new HealingFrontierStorage(stateIngest.flatSlots.dataSource))
    else None

  private lazy val bfsQueueStorage: BfsQueueStorage =
    new RocksDbBfsQueueStorage(stateIngest.flatSlots.dataSource, Namespaces.BfsQueueNamespace)

  def heal(): Unit =
    ctx.spawn(
      childFactories.trieNodeHealingCoordinator(
        healingFrontierStorage = healingFrontierStorageOpt,
        bfsQueueStorageOpt = Some(bfsQueueStorage)
      ),
      "heal"
    )
"""
    HO_AFTER = (HO_LAZY
                .replace("  private lazy val healingFrontierStorageOpt:", "  private[snap] lazy val healingFrontierStorageOpt:")
                .replace("  private lazy val bfsQueueStorage: BfsQueueStorage =\n    new RocksDbBfsQueueStorage(stateIngest.flatSlots.dataSource, Namespaces.BfsQueueNamespace)\n",
                         "  private[snap] lazy val bfsQueueStorageOpt: Option[BfsQueueStorage] =\n"
                         "    Some(new RocksDbBfsQueueStorage(stateIngest.flatSlots.dataSource, Namespaces.BfsQueueNamespace))\n")
                .replace("healingFrontierStorage = healingFrontierStorageOpt,", "healingFrontierStorage = stateIngest.healingFrontier,")
                .replace("bfsQueueStorageOpt = Some(bfsQueueStorage)\n", "bfsQueueStorageOpt = stateIngest.bfsQueue\n"))
    CAP_AFTER = (SI_SYNC
                 .replace("// capability-map: validatorFactory -> validatorFactory\n",
                          "// capability-map: validatorFactory -> validatorFactory\n"
                          "// capability-map: healingFrontierStorageOpt -> healingFrontier\n"
                          "// capability-map: bfsQueueStorageOpt -> bfsQueue\n")
                 .replace("  def durability: StateIngest.Durability\n",
                          "  def durability: StateIngest.Durability\n  def healingFrontier: Option[HealingFrontierStorage]\n"
                          "  def bfsQueue: Option[BfsQueueStorage]\n")
                 .replace("      durability: Durability\n  ): StateIngest =",
                          "      durability: Durability,\n      healingFrontierStorageOpt: => Option[HealingFrontierStorage],\n"
                          "      bfsQueueStorageOpt: => Option[BfsQueueStorage]\n  ): StateIngest =")
                 .replace("validatorFactory, durability)\n",
                          "validatorFactory, durability, healingFrontierStorageOpt, bfsQueueStorageOpt)\n")
                 .replace("      val durability: Durability\n  ) extends StateIngest\n",
                          "      val durability: Durability,\n      healingFrontierStorageOpt: => Option[HealingFrontierStorage],\n"
                          "      bfsQueueStorageOpt: => Option[BfsQueueStorage]\n  ) extends StateIngest:\n"
                          "    def healingFrontier: Option[HealingFrontierStorage] = healingFrontierStorageOpt\n"
                          "    def bfsQueue: Option[BfsQueueStorage] = bfsQueueStorageOpt\n"))
    CORE_AFTER = SI_CORE.replace("    StateIngest.Durability.Default\n  )\n",
                                 "    StateIngest.Durability.Default,\n    healingFrontierStorageOpt,\n    bfsQueueStorageOpt\n  )\n")
    SC_NONE = ("      val healingFrontierStorageOpt: Option[HealingFrontierStorage] = None\n"
               "      val bfsQueueStorageOpt: Option[BfsQueueStorage] = None\n")
    SC_LAZY = (SC_AFTER
               .replace("        StateIngest.Durability.Default\n      )\n",
                        "        StateIngest.Durability.Default,\n        healingFrontierStorageOpt,\n        bfsQueueStorageOpt\n      )\n")
               .replace("      val stateIngest: StateIngest", SC_NONE + "      val stateIngest: StateIngest"))
    MSG = "refactor(snap): StateIngest healing stores (#1401)\n\n# capability: StateIngest\n"
    JUST = {"capabilities.StateIngest.members": "C-H1: healingFrontier and bfsQueue, the healing stores, by-name from lazy vals",
            "storage_refs.total": "C-H1 capability: the core's accessor names the two lazy healing handles (+2)"}

    def fixture(self, ho=None):
        files = {**ADOPT_FILES, CAP: SI_SYNC, SC: SC_AFTER, BRA: BRA_AFTER, HO: ho or self.HO_LAZY}
        f = Fixture(branch="refactor/snap-016-ingest-healing", core_base=files[CORE],
                    extra={**{k: v for k, v in files.items() if k != CORE}, BASELINE: adopt_baseline(files)})
        f.files = files
        return f

    def commit(self, f, **over):
        changes = {CAP: self.CAP_AFTER, CORE: self.CORE_AFTER, SC: self.SC_LAZY, HO: self.HO_AFTER}
        for k, v in over.items():
            changes[{"cap": CAP, "core": CORE, "sc": SC, "ho": HO}[k]] = v
        adopt_commit(f, changes, msg=self.MSG, just=self.JUST, members=11)

    def test_lazy_healing_handles_shaped_like_c_h1_pass(self):
        f = self.fixture()
        self.commit(f)
        out = self.ok(f)
        self.assertIn(f"[capability] lazy healing handle `healingFrontierStorageOpt` ({HO}) feeds by-name "
                      "`healingFrontierStorageOpt` -> `stateIngest.healingFrontier`", out)
        self.assertIn(f"[capability] {HO}: `healingFrontierStorageOpt` widens from `private` to `private[snap]`", out)
        self.assertIn(f"[capability] {HO}: `bfsQueueStorage` lifts into `bfsQueueStorageOpt`; `Some(bfsQueueStorage)` "
                      "stands as `stateIngest.bfsQueue`", out)
        self.assertIn(f"[capability] {HO}: 2 substitution(s)", out)
        self.assertIn(f"[capability] {SC}: mirrors the core's `stateIngest`", out)

    def test_strict_parameter_fed_by_a_lazy_handle_fails(self):
        f = self.fixture()
        cap = self.CAP_AFTER.replace("      healingFrontierStorageOpt: => Option[HealingFrontierStorage],\n      bfsQueueStorageOpt: => Option[BfsQueueStorage]\n  ): StateIngest =",
                                     "      healingFrontierStorageOpt: Option[HealingFrontierStorage],\n      bfsQueueStorageOpt: => Option[BfsQueueStorage]\n  ): StateIngest =")
        self.commit(f, cap=cap)
        self.assertIn("is fed by the lazy val `healingFrontierStorageOpt`", self.bad(f, "capability"))

    def test_lazy_handle_changed_beyond_widening_fails(self):
        f = self.fixture()
        self.commit(f, ho=self.HO_AFTER.replace("if snapSyncConfig.healingFrontierPersistence then", "if true then"))
        self.assertIn("changes the lazy healing handle `healingFrontierStorageOpt` beyond widening", self.bad(f, "capability"))

    def test_option_lift_with_another_initializer_fails(self):
        f = self.fixture()
        self.commit(f, ho=self.HO_AFTER.replace("Namespaces.BfsQueueNamespace))", "Namespaces.Other))"))
        self.assertIn("the Option lift replaces exactly one", self.bad(f, "capability"))

    def test_other_use_of_the_lifted_handle_fails(self):
        # only `Some(g)` stands as the member: any other use of the removed lazy val is a change
        f = self.fixture(ho=self.HO_LAZY + "\n  def size(): Int = bfsQueueStorage.size\n")
        self.commit(f, ho=self.HO_AFTER + "\n  def size(): Int = bfsQueueStorageOpt.get.size\n")
        self.assertIn(f"{HO}: change is not a mapped handle substitution", self.bad(f, "capability"))

    def test_mirror_building_a_healing_store_fails(self):
        f = self.fixture()
        sc = self.SC_LAZY.replace("val healingFrontierStorageOpt: Option[HealingFrontierStorage] = None",
                                  "val healingFrontierStorageOpt: Option[HealingFrontierStorage] = "
                                  "Some(new HealingFrontierStorage(flatSlotStorage.dataSource))")
        self.commit(f, sc=sc)
        self.assertIn("mirrored as `val healingFrontierStorageOpt: Option [ HealingFrontierStorage ] = None`",
                      self.bad(f, "capability"))

    def test_mirror_reading_a_healing_member_fails(self):
        f = self.fixture()
        sc = self.SC_LAZY.replace("      runningRecovery(bytecodeActor, snapSyncConfig, stateIngest)",
                                  "      stateIngest.healingFrontier.foreach(_.clearComplete())\n"
                                  "      runningRecovery(bytecodeActor, snapSyncConfig, stateIngest)")
        self.commit(f, sc=sc)
        self.assertIn("reads `stateIngest.healingFrontier`, which its capability mirrors as `None`", self.bad(f, "capability"))


class CounterV2Tests(unittest.TestCase):
    """Counter version 2 (T060b, G10): the coordinators' false positives no longer count and `storageDone` does; the
    baseline's `counter` field selects it, only goes up, and a change needs a fresh total justification."""
    ok = VerifyTests.ok
    bad = VerifyTests.bad

    def test_v2_drops_false_positives_and_counts_storage_done(self):
        text = ("  self ! StorageRangeCoordinator.ForceCompleteStorage\n  if isNodeInStorage(h) && entry.isStorage then\n"
                "    SNAPSyncMetrics.setStoragePendingTries(1)\n  replayQueuedStorage += StorageTask.createStorageTask(a, b).size\n"
                "  storageDone.fold(x)(_.unfinished(x))\n  mptStorage.get(k)\n")
        self.assertEqual(V().storage_ref_count(text, counter=1), 7)  # 6 false positives + mptStorage
        self.assertEqual(V().storage_ref_count(text, counter=2), 2)  # storageDone + mptStorage

    def ratchet(self, counter, just):
        f = adopt_fixture()
        adopt_commit(f, {}, msg="test: re-baseline\n", counter=counter, just=just)
        return f

    def test_counter_upgrade_needs_a_fresh_justification(self):
        self.assertIn("raises counter from 1 to 2", self.bad(self.ratchet(2, None), "storage-refs"))
        out = self.ok(self.ratchet(2, {"storage_refs.total": "counter v2: false positives out, storageDone in"}))
        self.assertIn("counter version 1 -> 2 (re-baseline)", out)

    def test_p3_counter_bump_cannot_hide_new_references(self):
        # the v1 -> v2 gap is 6 on this fixture's ForceCompleteStorage-style names; 6 new real refs keep the v2 total
        # under the v1 ceiling, so the ceiling is the merge-base re-measured under v2
        fp = "\n  def fp(): Unit =\n" + "".join(f"    self ! ForceCompleteStorage{''}\n    isNodeInStorage({i})\n" for i in range(3))
        f = adopt_fixture(files={**ADOPT_FILES, PS: ADOPT_FILES[PS] + fp})
        f.git("tag", "-f", "base")
        real = "\n  def leak(): Unit =\n" + "".join(f"    mptStorage.get({i})\n" for i in range(6))
        f.write(PS, ADOPT_FILES[PS] + fp + real)
        f.files = {**f.files, PS: ADOPT_FILES[PS] + fp + real}
        f.write(BASELINE, adopt_baseline(f.files, counter=2, just={"storage_refs.total": "counter v2: false positives out, storageDone in"}))
        f.commit("test: re-baseline\n")
        out = self.bad(f, "storage-refs")
        self.assertIn("above the merge-base re-measured under counter 2", out)
        self.assertIn("a counter change is a commit of its own, with no src/main change", out)

    def test_p3_counter_bump_needs_a_substantive_justification(self):
        out = self.bad(self.ratchet(2, {"storage_refs.total": "v2"}), "storage-refs")
        self.assertIn("40+ characters naming `counter v2`", out)

    def test_counter_downgrade_fails(self):
        f = adopt_fixture()
        adopt_commit(f, {}, msg="test: v2\n", counter=2, just={"storage_refs.total": "v2"})
        f.git("tag", "-f", "base")
        adopt_commit(f, {}, msg="test: back to v1\n", just={"storage_refs.total": "v1 again"})
        self.assertIn("lowers counter from 2 to 1", self.bad(f, "storage-refs"))

    def test_real_baseline_matches_this_tree(self):
        root = Path(__file__).resolve().parents[3]
        b = json.loads((root / BASELINE).read_text())
        snap = root / SNAP
        files = {str(p.relative_to(snap)): p.read_text() for d in ("controller", "actors") for p in (snap / d).rglob("*.scala")}
        files["SNAPSyncController.scala"] = (snap / "SNAPSyncController.scala").read_text()
        caps = {e["file"]: n for n, e in b["capabilities"].items()}
        ex = [V().cap_exemption(n, files[k]) for k, n in caps.items()]
        refs = {k: n for k, t in files.items() if k not in caps and (n := V().storage_ref_count(t, ex, b.get("counter", 1)))}
        self.assertEqual(refs, b["storage_refs"])
        self.assertEqual(sum(refs.values()), b["storage_refs_total"])

    def test_coordinator_env_trait_is_declaration_only_and_reported_as_a_capability(self):
        text = "package x\n\nprivate[actors] trait ByteCodeEnv:\n  def stateIngest: StateIngest\n  def log: Logger\n"
        self.assertEqual(V().declaration_only_regions(text), {4, 5})
        f = adopt_fixture()
        f.write(f"{ACT}/bytecode/ByteCodeEnv.scala", text)
        adopt_commit(f, {f"{ACT}/bytecode/ByteCodeEnv.scala": text}, msg="refactor: env (#1401)\n")
        self.assertIn("ByteCodeEnv=2", [l for l in self.ok(f).splitlines() if "[counts] capability traits:" in l][0])


if __name__ == "__main__":
    unittest.main(verbosity=1)
