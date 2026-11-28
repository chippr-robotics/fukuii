package com.chipprbots.ethereum.nodebuilder.tooling

import org.apache.pekko.util.ByteString

import com.typesafe.scalalogging.Logger
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.nodebuilder.tooling.StorageConsistencyChecker.*
import com.chipprbots.ethereum.testing.Tags.*

class StorageConsistencyCheckerSpec extends AnyWordSpec with Matchers:

  private class Store:
    val ds: EphemDataSource = EphemDataSource()
    val nums = new BlockNumberMappingStorage(ds)
    val heads = new BlockHeadersStorage(ds)
    val app = new AppStateStorage(ds)
    private val header = Fixtures.Blocks.Genesis.header

    private def hashOf(n: Int): ByteString = ByteString(s"hash-$n".getBytes("UTF-8"))

    /** Numbers 0 until `count` mapped to a hash with a header, except those in the two sets. */
    def fill(count: Int, noHash: Set[Int] = Set.empty, noHeader: Set[Int] = Set.empty): Unit =
      (0 until count).foreach { n =>
        if !noHash.contains(n) then
          nums.put(BigInt(n), hashOf(n)).commit()
          if !noHeader.contains(n) then heads.put(hashOf(n), header).commit()
      }

  private given Logger = Logger("StorageConsistencyCheckerSpec")

  "StorageConsistencyChecker.scan" should {

    "find nothing missing when every sampled point is present" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store; s.fill(11)
      val r = scan(BigInt(10), s.nums, s.heads, step = 1)
      r.checked shouldBe 11L
      r.total shouldBe 11L
      r.missingCount shouldBe 0L
      r.firstMissing shouldBe empty
      r.cancelled shouldBe false
    }

    "report a number with no hash" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store; s.fill(11, noHash = Set(4))
      val r = scan(BigInt(10), s.nums, s.heads, step = 1)
      r.missingCount shouldBe 1L
      r.firstMissing shouldBe Seq(Missing(BigInt(4), MissingKind.NoHashForNumber))
    }

    "report a hash with no header" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store; s.fill(11, noHeader = Set(7))
      val r = scan(BigInt(10), s.nums, s.heads, step = 1)
      r.missingCount shouldBe 1L
      r.firstMissing shouldBe Seq(Missing(BigInt(7), MissingKind.NoHeaderForHash))
    }

    "cap firstMissing while the count stays exact" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store // nothing stored: every point misses
      val n = FirstMissingCap + 50
      val r = scan(BigInt(n - 1), s.nums, s.heads, step = 1)
      r.missingCount shouldBe n.toLong
      r.firstMissing.size shouldBe FirstMissingCap
      r.firstMissing.map(_.number) shouldBe (0 until FirstMissingCap).map(BigInt(_))
    }

    "stop when cancelled and say how far it got" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store; s.fill(21)
      var asked = 0
      val r = scan(
        BigInt(20),
        s.nums,
        s.heads,
        step = 1,
        cancelled = () =>
          asked += 1; asked > 5
      )
      r.cancelled shouldBe true
      r.checked shouldBe 5L
      r.total shouldBe 21L
    }

    "sample every step-th number from 0 and best itself" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store // empty: misses name exactly the sampled points
      val r = scan(BigInt(10), s.nums, s.heads, step = 3)
      r.total shouldBe 5L
      r.firstMissing.map(_.number) shouldBe Seq(BigInt(0), BigInt(3), BigInt(6), BigInt(9), BigInt(10))
      val onMultiple = scan(BigInt(9), s.nums, s.heads, step = 3)
      onMultiple.total shouldBe 4L
      onMultiple.firstMissing.map(_.number) shouldBe Seq(BigInt(0), BigInt(3), BigInt(6), BigInt(9))
      val genesisOnly = scan(BigInt(0), s.nums, s.heads, step = 3)
      genesisOnly.total shouldBe 1L
      genesisOnly.firstMissing.map(_.number) shouldBe Seq(BigInt(0))
    }

    "count and name points above Int.MaxValue without wrapping" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store
      val best = BigInt(Int.MaxValue) + 5
      val step = 1000000000
      val r = scan(best, s.nums, s.heads, step = step)
      // 0, 1e9, 2e9, then 3e9 is above best: so 0, 1e9, 2e9, and best itself
      r.total shouldBe 4L
      r.firstMissing.map(_.number) shouldBe Seq(BigInt(0), BigInt(step), BigInt(2L * step), best)
    }

    "report progress monotonically, ending at the total" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store; s.fill(1100)
      val seen = scala.collection.mutable.ArrayBuffer.empty[(Long, Long)]
      scan(BigInt(1000), s.nums, s.heads, step = 1, onProgress = (c, t) => seen += ((c, t)))
      seen should not be empty
      seen.map(_._1) shouldBe seen.map(_._1).sorted
      seen.map(_._1).distinct.size shouldBe seen.size
      seen.map(_._2).distinct shouldBe Seq(1001L)
      seen.last shouldBe ((1001L, 1001L))
      seen.size should be <= (1001 / ProgressEvery + 1)
    }
  }

  "StorageConsistencyChecker.applicability" should {

    "be None on a plain node" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store
      applicability(s.app, engineApi = false) shouldBe None
    }

    "say SnapDone once SNAP sync is done" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store; s.app.snapSyncDone().commit()
      applicability(s.app, engineApi = false) shouldBe Some(SkipReason.SnapDone)
    }

    "say SnapInProgress when a SNAP pivot is recorded and it is not done" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store; s.app.putSnapSyncPivotBlock(BigInt(100)).commit()
      applicability(s.app, engineApi = false) shouldBe Some(SkipReason.SnapInProgress)
    }

    "say EngineApi in Engine API mode" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store
      applicability(s.app, engineApi = true) shouldBe Some(SkipReason.EngineApi)
    }

    "prefer SnapDone over EngineApi, as the start-up path does" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store; s.app.snapSyncDone().commit()
      applicability(s.app, engineApi = true) shouldBe Some(SkipReason.SnapDone)
    }

    "give a sentence for every reason" taggedAs (UnitTest, DatabaseTest) in {
      SkipReason.values.foreach(_.reason should not be empty)
    }
  }

  "StorageConsistencyChecker.checkStorageConsistency (start-up path)" should {

    "call shutdown once per miss and keep looping" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store; s.fill(11, noHash = Set(2), noHeader = Set(5))
      var shutdowns = 0
      checkStorageConsistency(BigInt(10), s.nums, s.heads, () => shutdowns += 1, step = 1)
      shutdowns shouldBe 2
    }

    "not call shutdown when nothing is missing" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store; s.fill(11)
      var shutdowns = 0
      checkStorageConsistency(BigInt(10), s.nums, s.heads, () => shutdowns += 1, step = 1)
      shutdowns shouldBe 0
    }

    "check the best block itself" taggedAs (UnitTest, DatabaseTest) in {
      val s = new Store; s.fill(10, noHash = Set(9))
      var shutdowns = 0
      checkStorageConsistency(BigInt(9), s.nums, s.heads, () => shutdowns += 1, step = 1)
      shutdowns shouldBe 1
    }
  }
