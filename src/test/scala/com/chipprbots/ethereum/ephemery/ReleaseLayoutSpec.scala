package com.chipprbots.ethereum.ephemery

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

import scala.jdk.CollectionConverters.*

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.ephemery.EphemeryTestInputs.*
import com.chipprbots.ethereum.testing.Tags.*

/** The writing of an iteration: replacing one is all or nothing, and a failure leaves no stage. */
class ReleaseLayoutSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  private val now = Instant.parse("2026-11-12T10:00:00Z")

  private var tmp: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit = tmp = Files.createTempDirectory("eph-layout-test")
  override def afterEach(): Unit = delete(tmp)

  private def delete(p: Path): Unit =
    if Files.exists(p, LinkOption.NOFOLLOW_LINKS) then
      if Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) then Files.list(p).iterator().asScala.toList.foreach(delete)
      Files.delete(p)

  private def read(p: Path): String = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  private lazy val release = EphemeryRelease.validate(raw(165)).fold(r => fail(r.message), identity)
  private def meta = ReleaseMetadata.of(release, "/src", None, "test", now)
  private def names(p: Path): List[String] = Files.list(p).iterator().asScala.map(_.getFileName.toString).toList.sorted

  private def write(out: Path, ops: ReleaseLayout.FileOps = ReleaseLayout.FileOps.Default) =
    ReleaseLayout.write(release, releaseDir(165), out, meta, ops)

  /** An old iteration directory: stale members and a datadir. */
  private def seedOld(out: Path): Path =
    val dest = out.resolve("ephemery-165")
    List("testnet", "chains", "conf").foreach(d => Files.createDirectories(dest.resolve(d)))
    List("testnet/old", "chains/old", "conf/old", "release.env", "release.json").foreach(f =>
      Files.write(dest.resolve(f), s"old $f".getBytes(StandardCharsets.UTF_8))
    )
    Files.createDirectories(dest.resolve("data"))
    Files.write(dest.resolve("data/keep"), "k".getBytes(StandardCharsets.UTF_8))
    dest

  /** Fails the `n`th move (1-based), the way a disk full or a permission error would. */
  private class FailingOps(n: Int, failRestore: Boolean = false) extends ReleaseLayout.FileOps:
    var moves = 0
    def move(from: Path, to: Path): Unit =
      moves += 1
      if moves == n || (failRestore && moves > n) then throw new java.io.IOException(s"injected failure on move $moves")
      ReleaseLayout.FileOps.Default.move(from, to)
    def delete(tree: Path): Unit = ReleaseLayout.FileOps.Default.delete(tree)

  "ReleaseLayout.write" should "replace an existing iteration, keep its data, and leave no stage or .old" taggedAs (
    UnitTest
  ) in {
    val dest = seedOld(tmp)
    write(tmp).isRight shouldBe true
    read(dest.resolve("data/keep")) shouldBe "k"
    Files.exists(dest.resolve("chains/old")) shouldBe false
    read(dest.resolve("chains/ephemery-chain.conf")) shouldBe release.chainConf
    names(tmp).filter(_.startsWith(".stage")) shouldBe empty
    (names(dest) should contain).allOf("chains", "conf", "data", "release.env", "release.json", "testnet")
    names(dest).filter(_.startsWith(".old")) shouldBe empty
  }

  it should "leave the old entry intact when a move fails between moving it aside and moving the new one in" taggedAs (
    UnitTest
  ) in {
    val dest = seedOld(tmp)
    // moves 1..5 put the old members aside, 6.. move the new ones in: fail the 8th (the third new member)
    val r = write(tmp, new FailingOps(8))
    r.left.getOrElse(fail("expected a failure")) should include("injected failure on move 8")
    List("testnet/old", "chains/old", "conf/old", "release.env", "release.json").foreach(f =>
      withClue(f)(read(dest.resolve(f)) shouldBe s"old $f")
    )
    read(dest.resolve("data/keep")) shouldBe "k"
    Files.exists(dest.resolve("chains/ephemery-chain.conf")) shouldBe false
    names(tmp).filter(_.startsWith(".stage")) shouldBe empty // a failed write leaves no stage
    Files.exists(tmp.resolve("current"), LinkOption.NOFOLLOW_LINKS) shouldBe false
  }

  it should "keep the stage, which holds the old copy, when the old members cannot be restored" taggedAs (UnitTest) in {
    val dest = seedOld(tmp)
    val r = write(tmp, new FailingOps(8, failRestore = true)).left.getOrElse(fail("expected a failure"))
    r should include("could not be restored")
    r should include("the previous copy is kept in")
    val stages = names(tmp).filter(_.startsWith(".stage"))
    stages should have size 1
    names(tmp.resolve(stages.head).resolve(".old")) should not be empty
    Files.exists(dest) shouldBe true
  }

  it should "leave no stage when the first write fails (a new iteration)" taggedAs (UnitTest) in {
    write(tmp, new FailingOps(1)).isLeft shouldBe true
    names(tmp).filter(_.startsWith(".stage")) shouldBe empty
    Files.exists(tmp.resolve("ephemery-165")) shouldBe false
  }

  it should "report the iteration as written, with a warning, when current cannot be pointed" taggedAs (UnitTest) in {
    val dest = tmp.resolve("ephemery-165")
    var firstMoveDone = false
    val ops = new ReleaseLayout.FileOps:
      def move(from: Path, to: Path): Unit =
        // the iteration's own move (stage -> dest) is the first; the `current` swap is the second
        if firstMoveDone then throw new java.io.IOException("cannot move current")
        firstMoveDone = true
        ReleaseLayout.FileOps.Default.move(from, to)
      def delete(tree: Path): Unit = ReleaseLayout.FileOps.Default.delete(tree)
    val r = write(tmp, ops)
    r.isRight shouldBe true
    r.toOption.get.notes.exists(_.contains("WARNING: cannot point")) shouldBe true
    Files.isDirectory(dest.resolve("chains")) shouldBe true
    names(tmp).filter(_.startsWith(".current")) shouldBe empty
  }

  it should "create ephemery-local.conf once and never overwrite it" taggedAs (UnitTest) in {
    Files.write(tmp.resolve("ephemery-local.conf"), "# mine\n".getBytes(StandardCharsets.UTF_8))
    write(tmp).isRight shouldBe true
    read(tmp.resolve("ephemery-local.conf")) shouldBe "# mine\n"
  }

  it should "sweep a .stage-* directory older than an hour, and keep a newer one" taggedAs (UnitTest) in {
    val old = Files.createDirectories(tmp.resolve(".stage-old"))
    val fresh = Files.createDirectories(tmp.resolve(".stage-fresh"))
    Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(now.minusSeconds(7200)))
    Files.setLastModifiedTime(fresh, java.nio.file.attribute.FileTime.from(now.minusSeconds(60)))
    write(tmp).isRight shouldBe true
    Files.exists(old) shouldBe false
    Files.exists(fresh) shouldBe true
  }

  it should "refuse an out that is inside the from-dir, also through a symlink" taggedAs (UnitTest) in {
    val src = Files.createDirectories(tmp.resolve("src"))
    val link = Files.createSymbolicLink(tmp.resolve("link"), src)
    val r = ReleaseLayout.write(release, src, link.resolve("out"), meta)
    r.left.getOrElse(fail("expected a refusal")) should include("is inside --from-dir")
  }

  // W2: a run that died in the middle of a replace leaves a stage with the old members in `.old` and `.target`
  private def leftoverStage(out: Path, name: String, target: Path, withTarget: Boolean = true): Path =
    val st = Files.createDirectories(out.resolve(name))
    val old = Files.createDirectories(st.resolve(".old"))
    List("testnet", "chains").foreach { d =>
      Files.createDirectories(old.resolve(d))
      Files.write(old.resolve(d).resolve("old"), s"old $d".getBytes(StandardCharsets.UTF_8))
    }
    if withTarget then Files.write(st.resolve(".target"), target.toString.getBytes(StandardCharsets.UTF_8))
    Files.setLastModifiedTime(st, java.nio.file.attribute.FileTime.from(now.minusSeconds(7200))) // old enough to sweep
    st

  it should "roll a crashed replace back on the next run, and then delete the stage" taggedAs (UnitTest) in {
    val target = tmp.resolve("ephemery-164")
    Files.createDirectories(target.resolve("chains")) // half-replaced: a new chains/, and no testnet/
    Files.write(target.resolve("chains/new"), "new".getBytes(StandardCharsets.UTF_8))
    val st = leftoverStage(tmp, ".stage-crashed", target)
    val r = write(tmp).fold(e => fail(e), identity) // a run for a different iteration (165)
    read(target.resolve("testnet/old")) shouldBe "old testnet"
    read(target.resolve("chains/old")) shouldBe "old chains"
    Files.exists(target.resolve("chains/new")) shouldBe false
    Files.exists(st) shouldBe false
    r.notes.exists(n => n.contains("restored") && n.contains("ephemery-164")) shouldBe true
  }

  it should "set aside, under .stage-kept-, a stage it cannot recover, and refuse; the next run refuses the same way" taggedAs (
    UnitTest
  ) in {
    val st = leftoverStage(tmp, ".stage-orphan", tmp.resolve("ephemery-164"), withTarget = false)
    val r = write(tmp).left.getOrElse(fail("expected a refusal"))
    val kept = tmp.resolve(".stage-kept-orphan")
    r shouldBe s"an earlier interrupted write left $kept; its .old holds the previous members of an unknown iteration " +
      s"under $tmp; move them back into that directory, or delete $kept, then run again"
    Files.exists(st) shouldBe false
    Files.exists(tmp.resolve(".stage-kept-orphan/.old/testnet/old")) shouldBe true // the old copy is untouched
    Files.exists(tmp.resolve("ephemery-165")) shouldBe false
    // the next run, with the kept stage present, refuses the same way and still touches nothing
    write(tmp).left.getOrElse(fail("expected a refusal")) shouldBe r
    Files.exists(tmp.resolve(".stage-kept-orphan/.old/testnet/old")) shouldBe true
    // and the sweep never removes it, however old
    Files.setLastModifiedTime(
      tmp.resolve(".stage-kept-orphan"),
      java.nio.file.attribute.FileTime.from(now.minusSeconds(100000))
    )
    write(tmp).isLeft shouldBe true
    Files.exists(tmp.resolve(".stage-kept-orphan")) shouldBe true
  }

  it should "refuse a .target outside the out directory, keeping the stage as .stage-kept-" taggedAs (UnitTest) in {
    leftoverStage(tmp, ".stage-evil", Path.of("/tmp/ephemery-1"))
    write(tmp).left.getOrElse(fail("expected a refusal")) should include(".stage-kept-evil")
    Files.exists(tmp.resolve(".stage-kept-evil/.old")) shouldBe true
  }

  it should "keep, renamed, a stage whose restore fails, and refuse" taggedAs (UnitTest) in {
    val target = tmp.resolve("ephemery-164")
    Files.createDirectories(target)
    leftoverStage(tmp, ".stage-failing", target)
    val ops = new ReleaseLayout.FileOps:
      def move(from: Path, to: Path): Unit = throw new java.io.IOException("disk gone")
      def delete(tree: Path): Unit = ReleaseLayout.FileOps.Default.delete(tree)
    write(tmp, ops).left.getOrElse(fail("expected a refusal")) should include(".stage-kept-failing")
    Files.exists(tmp.resolve(".stage-kept-failing/.old/testnet/old")) shouldBe true
    write(tmp).left.getOrElse(fail("expected a refusal")) should include("move them back into that directory")
  }

  it should "never let a crafted .old/data delete the target's data/ (F2)" taggedAs (UnitTest) in {
    val target = tmp.resolve("ephemery-164")
    Files.createDirectories(target.resolve("data"))
    Files.write(target.resolve("data/keep"), "k".getBytes(StandardCharsets.UTF_8))
    val st = leftoverStage(tmp, ".stage-crafted", target)
    Files.createDirectories(st.resolve(".old/data"))
    Files.write(st.resolve(".old/data/x"), "x".getBytes(StandardCharsets.UTF_8))
    write(tmp).isRight shouldBe true
    read(target.resolve("data/keep")) shouldBe "k"
    Files.exists(target.resolve("data/x")) shouldBe false
    read(target.resolve("testnet/old")) shouldBe "old testnet" // the real members were restored
    Files.exists(st) shouldBe false
  }

  it should "clean a finished stage whose .old was partly deleted without touching the target (F1)" taggedAs (UnitTest) in {
    val target = tmp.resolve("ephemery-164")
    Files.createDirectories(target.resolve("chains"))
    Files.write(target.resolve("chains/new"), "new".getBytes(StandardCharsets.UTF_8))
    val st = leftoverStage(tmp, ".stage-half", target)
    Files.write(st.resolve(".done"), "done".getBytes(StandardCharsets.UTF_8))
    ReleaseLayout.FileOps.Default.delete(st.resolve(".old/testnet")) // the cleanup got this far
    write(tmp).isRight shouldBe true
    read(target.resolve("chains/new")) shouldBe "new"
    Files.exists(target.resolve("testnet")) shouldBe false
    Files.exists(st) shouldBe false
  }

  it should "delete .old first when it cleans up after a replace, so .done outlives it (F1)" taggedAs (UnitTest) in {
    seedOld(tmp)
    val deleted = scala.collection.mutable.ArrayBuffer.empty[Path]
    val ops = new ReleaseLayout.FileOps:
      def move(from: Path, to: Path): Unit = ReleaseLayout.FileOps.Default.move(from, to)
      def delete(tree: Path): Unit =
        deleted += tree
        ReleaseLayout.FileOps.Default.delete(tree)
    write(tmp, ops).isRight shouldBe true
    val oldAt = deleted.indexWhere(_.getFileName.toString == ".old")
    val stageAt = deleted.indexWhere(_.getFileName.toString.startsWith(".stage-"))
    oldAt should be >= 0
    stageAt should be > oldAt
    // A stage with .old, and neither .done nor .target, cannot be what a finished replace leaves: .target is written
    // before .old is filled and is only removed with the whole stage, after .old is gone.
  }

  it should "only delete a finished leftover stage" taggedAs (UnitTest) in {
    val target = tmp.resolve("ephemery-164")
    val st = leftoverStage(tmp, ".stage-finished", target)
    Files.write(st.resolve(".done"), "done".getBytes(StandardCharsets.UTF_8))
    write(tmp).isRight shouldBe true
    Files.exists(st) shouldBe false
    Files.exists(target) shouldBe false // not rolled back
  }

  // W3
  it should "refuse, with another ephemery-fetch is writing to <out>, while another channel holds the lock" taggedAs (
    UnitTest
  ) in {
    val lockFile = tmp.resolve(".lock")
    val channel = java.nio.channels.FileChannel.open(
      lockFile,
      java.nio.file.StandardOpenOption.CREATE,
      java.nio.file.StandardOpenOption.WRITE
    )
    val held = channel.tryLock()
    try
      val r = write(tmp)
      r.left.getOrElse(fail("expected a refusal")) shouldBe s"another ephemery-fetch is writing to $tmp"
      Files.exists(tmp.resolve("ephemery-165")) shouldBe false
      names(tmp).filter(_.startsWith(".stage")) shouldBe empty
    finally
      held.release()
      channel.close()
    write(tmp).isRight shouldBe true // the lock is released at the end of a write, and free again
    write(tmp).isRight shouldBe true
  }

  it should "name the iteration from .target in the kept-stage refusal, and say when the rename failed (H4)" taggedAs (
    UnitTest
  ) in {
    val target = tmp.resolve("ephemery-164")
    Files.createDirectories(target)
    leftoverStage(tmp, ".stage-bad", target)
    Files.delete(tmp.resolve(".stage-bad/.old/testnet/old"))
    // make the restore fail, and the rename too: every move fails
    val ops = new ReleaseLayout.FileOps:
      def move(from: Path, to: Path): Unit = throw new java.io.IOException("no moves")
      def delete(tree: Path): Unit = ReleaseLayout.FileOps.Default.delete(tree)
    // the rename uses Files.move directly, so it succeeds: the message names the target iteration
    val r = write(tmp, ops).left.getOrElse(fail("expected a refusal"))
    r should include(s"previous members of $tmp/ephemery-164")
    r should include(s"or delete ${tmp.resolve(".stage-kept-bad")}, then run again")
  }

  it should "roll back an empty .old when the new copy was being moved in: what it added is removed (H1)" taggedAs (
    UnitTest
  ) in {
    val target = tmp.resolve("ephemery-164")
    Files.createDirectories(target.resolve("chains")) // moved in by the dead run; the old dir had no members
    Files.write(target.resolve("chains/new"), "new".getBytes(StandardCharsets.UTF_8))
    Files.createDirectories(target.resolve("data"))
    val st = Files.createDirectories(tmp.resolve(".stage-moving"))
    Files.createDirectories(st.resolve(".old"))
    Files.write(st.resolve(".target"), target.toString.getBytes(StandardCharsets.UTF_8))
    Files.write(st.resolve(".aside-done"), "aside".getBytes(StandardCharsets.UTF_8))
    write(tmp).isRight shouldBe true
    Files.exists(target.resolve("chains")) shouldBe false
    Files.isDirectory(target.resolve("data")) shouldBe true // not a member
    Files.exists(st) shouldBe false
  }

  it should "just clean a stage whose .old is empty and nothing had moved, even with no .target (H3)" taggedAs (UnitTest) in {
    val target = tmp.resolve("ephemery-164")
    Files.createDirectories(target.resolve("chains"))
    Files.write(target.resolve("chains/keep"), "old".getBytes(StandardCharsets.UTF_8))
    val st = Files.createDirectories(tmp.resolve(".stage-early"))
    Files.createDirectories(st.resolve(".old"))
    write(tmp).isRight shouldBe true
    Files.exists(st) shouldBe false
    read(target.resolve("chains/keep")) shouldBe "old" // an empty .old with no aside-done never deletes the target
  }

  it should "write .target before it creates .old (H3)" taggedAs (UnitTest) in {
    seedOld(tmp)
    var targetSeenBeforeOld = true
    val ops = new ReleaseLayout.FileOps:
      def move(from: Path, to: Path): Unit =
        // the first move aside: .old exists now, so .target must already
        if to.toString.contains("/.old/") && !Files.exists(to.getParent.getParent.resolve(".target")) then
          targetSeenBeforeOld = false
        ReleaseLayout.FileOps.Default.move(from, to)
      def delete(tree: Path): Unit = ReleaseLayout.FileOps.Default.delete(tree)
    write(tmp, ops).isRight shouldBe true
    targetSeenBeforeOld shouldBe true
  }

  it should "keep the Right, with a note, and release the lock when the stage cleanup fails (H2)" taggedAs (UnitTest) in {
    var failStageDelete = true
    val ops = new ReleaseLayout.FileOps:
      def move(from: Path, to: Path): Unit = ReleaseLayout.FileOps.Default.move(from, to)
      def delete(tree: Path): Unit =
        if failStageDelete && tree.getFileName.toString.startsWith(".stage-") then
          throw new java.io.IOException("cannot delete the stage")
        ReleaseLayout.FileOps.Default.delete(tree)
    seedOld(tmp) // a replace, so the stage is cleaned up
    val r = write(tmp, ops).fold(e => fail(e), identity)
    r.notes.exists(n => n.contains("could not remove the stage") && n.contains("cannot delete the stage")) shouldBe true
    failStageDelete = false
    write(tmp).isRight shouldBe true // the lock was released; the leftover stage is swept or recovered
  }

  it should "be idempotent: a recovery that dies partway through the restore loses no old member on the rerun (J1)" taggedAs (
    UnitTest
  ) in {
    val target = tmp.resolve("ephemery-164")
    Files.createDirectories(target.resolve("conf")) // added by the dead run's new copy
    Files.write(target.resolve("conf/new"), "new".getBytes(StandardCharsets.UTF_8))
    val st = leftoverStage(tmp, ".stage-partial", target) // .old has testnet/ and chains/
    Files.write(st.resolve(".aside-done"), "aside".getBytes(StandardCharsets.UTF_8))
    var moves = 0
    val dying = new ReleaseLayout.FileOps:
      def move(from: Path, to: Path): Unit =
        moves += 1
        if moves == 2 then throw new java.io.IOException("died on the second restore")
        ReleaseLayout.FileOps.Default.move(from, to)
      def delete(tree: Path): Unit = ReleaseLayout.FileOps.Default.delete(tree)
    write(tmp, dying).isLeft shouldBe true // one member back, one still in .old, the stage set aside
    val kept = tmp.resolve(".stage-kept-partial")
    Files.exists(kept) shouldBe true
    Files.exists(kept.resolve(".aside-done")) shouldBe false // gone before the first move back
    // the same state as a crash would leave: the stage under its own name, not set aside
    Files.move(kept, tmp.resolve(".stage-partial"))
    write(tmp).isRight shouldBe true
    read(target.resolve("testnet/old")) shouldBe "old testnet"
    read(target.resolve("chains/old")) shouldBe "old chains"
    Files.exists(target.resolve("conf")) shouldBe false
  }

  it should "fail the replace, and so roll back to the old copy, when the .done marker cannot be written (J2)" taggedAs (
    UnitTest
  ) in {
    val dest = seedOld(tmp)
    val ops = new ReleaseLayout.FileOps:
      def move(from: Path, to: Path): Unit = ReleaseLayout.FileOps.Default.move(from, to)
      def delete(tree: Path): Unit = ReleaseLayout.FileOps.Default.delete(tree)
      override def write(file: Path, text: String): Unit =
        if file.getFileName.toString == ".done" then throw new java.io.IOException("cannot write .done")
        else super.write(file, text)
    write(tmp, ops).left.getOrElse(fail("expected a failure")) should include("cannot write .done")
    List("testnet/old", "chains/old", "conf/old", "release.env", "release.json").foreach(f =>
      withClue(f)(read(dest.resolve(f)) shouldBe s"old $f")
    )
    Files.exists(dest.resolve("chains/ephemery-chain.conf")) shouldBe false
    names(tmp).filter(_.startsWith(".stage")) shouldBe empty
  }

  it should "remove a partial .done before it rolls back, so an interrupted rollback is never read as finished (K1)" taggedAs (
    UnitTest
  ) in {
    val dest = seedOld(tmp)
    // .done is created and then the write fails; the rollback is interrupted at its first move back
    val ops = new ReleaseLayout.FileOps:
      var failMoves = false
      def move(from: Path, to: Path): Unit =
        if failMoves then throw new java.io.IOException("rollback interrupted")
        ReleaseLayout.FileOps.Default.move(from, to)
      def delete(tree: Path): Unit = ReleaseLayout.FileOps.Default.delete(tree)
      override def write(file: Path, text: String): Unit =
        super.write(file, text)
        if file.getFileName.toString == ".done" then
          failMoves = true
          throw new java.io.IOException("partial .done")
    write(tmp, ops).left.getOrElse(fail("expected a refusal")) should include("could not be restored")
    val stages = names(tmp).filter(_.startsWith(".stage"))
    stages should have size 1
    Files.exists(tmp.resolve(stages.head).resolve(".done")) shouldBe false
    // the next run recovers the old copy from the kept stage's .old; it is not taken for finished
    names(tmp.resolve(stages.head).resolve(".old")) should not be empty
    Files.exists(dest) shouldBe true
  }
