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
    def __init__(self, branch="refactor/snap-016-m2", core_base=CORE_BASE):
        self.tmp = tempfile.TemporaryDirectory()
        _FIXTURES.append(self.tmp)
        self.d = Path(self.tmp.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@t")
        self.git("config", "user.name", "t")
        self.write(CORE, core_base)
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


if __name__ == "__main__":
    unittest.main(verbosity=1)
