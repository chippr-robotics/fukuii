package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags._

/** Codec round-trip + robustness for resumable recovery progress.
  *
  * The load-bearing property for "recoverable on flaky systems": serialize→deserialize must reproduce the EXACT gap set
  * and completed-shard set (no truncation, no delimiter collision, order-independent), AND any malformed/truncated/
  * wrong-version input must deserialise to None — so a torn write degrades to a fresh (correct) scan, never to a
  * silently-incomplete gap list that then gets marked done.
  */
class RecoveryProgressSpec extends AnyFunSuite with ScalaCheckPropertyChecks {

  private def hash(seed: Int): ByteString = ByteString(Array.fill[Byte](32)(seed.toByte))

  test("round-trips a populated progress exactly", UnitTest, DatabaseTest) {
    val p = RecoveryProgress(
      scanRoot = hash(0xaa),
      shardCount = 16,
      completedShards = Set(0, 3, 7, 15),
      missingBytecodes = Vector(hash(1), hash(2)),
      missingStorageTries = Vector(hash(10) -> hash(11), hash(12) -> hash(13))
    )
    assert(RecoveryProgress.deserialize(RecoveryProgress.serialize(p)).contains(p))
  }

  test("round-trips an all-empty progress (no shards done, no gaps)", UnitTest, DatabaseTest) {
    val p = RecoveryProgress(hash(0xbb), shardCount = 16, Set.empty, Vector.empty, Vector.empty)
    val back = RecoveryProgress.deserialize(RecoveryProgress.serialize(p))
    assert(back.contains(p))
    assert(back.get.completedShards.isEmpty)
    assert(back.get.missingBytecodes.isEmpty)
    assert(back.get.missingStorageTries.isEmpty)
  }

  test("completed-shard set is order-independent (it's a Set)", UnitTest, DatabaseTest) {
    val a = RecoveryProgress(hash(1), 16, Set(15, 0, 8, 3), Vector.empty, Vector.empty)
    val b = RecoveryProgress(hash(1), 16, Set(0, 3, 8, 15), Vector.empty, Vector.empty)
    assert(RecoveryProgress.serialize(a) == RecoveryProgress.serialize(b))
  }

  test("survives a large gap list with no truncation or collision", UnitTest, DatabaseTest) {
    val storage = (0 until 2000).map(i => hash(i) -> hash(i + 1)).toVector
    val bytecodes = (0 until 500).map(i => hash(i * 3)).toVector
    val p = RecoveryProgress(hash(0xcc), shardCount = 256, (0 until 256).toSet, bytecodes, storage)
    val back = RecoveryProgress.deserialize(RecoveryProgress.serialize(p))
    assert(back.contains(p))
    assert(back.get.missingStorageTries.size == 2000)
    assert(back.get.missingBytecodes.size == 500)
    assert(back.get.completedShards.size == 256)
  }

  test("garbage input deserialises to None (not an exception, not a wrong result)", UnitTest, DatabaseTest) {
    assert(RecoveryProgress.deserialize("not a real value").isEmpty)
    assert(RecoveryProgress.deserialize("").isEmpty)
    assert(RecoveryProgress.deserialize("\n\n\n\n\n").isEmpty)
  }

  test("wrong version deserialises to None", UnitTest, DatabaseTest) {
    val good = RecoveryProgress.serialize(RecoveryProgress(hash(1), 16, Set(1), Vector.empty, Vector.empty))
    val tampered = good.replaceFirst("^v1", "v2")
    assert(tampered.startsWith("v2"))
    assert(RecoveryProgress.deserialize(tampered).isEmpty)
  }

  test("truncated value (too few fields) deserialises to None", UnitTest, DatabaseTest) {
    val good = RecoveryProgress.serialize(RecoveryProgress(hash(1), 16, Set(1), Vector(hash(2)), Vector.empty))
    val truncated = good.split("\n").take(3).mkString("\n")
    assert(RecoveryProgress.deserialize(truncated).isEmpty)
  }

  test("non-hex token in a gap list deserialises to None", UnitTest, DatabaseTest) {
    val good = RecoveryProgress.serialize(RecoveryProgress(hash(1), 16, Set.empty, Vector(hash(2)), Vector.empty))
    val corrupted = good.replace("0202", "ZZZZ")
    assert(RecoveryProgress.deserialize(corrupted).isEmpty)
  }

  test("isComplete and remainingShards reflect the completed set", UnitTest, DatabaseTest) {
    val partial = RecoveryProgress(hash(1), 4, Set(0, 2), Vector.empty, Vector.empty)
    assert(!partial.isComplete)
    assert(partial.remainingShards == Seq(1, 3))

    val whole = RecoveryProgress(hash(1), 4, Set(0, 1, 2, 3), Vector.empty, Vector.empty)
    assert(whole.isComplete)
    assert(whole.remainingShards.isEmpty)
  }

  test("property: serialize→deserialize is identity for arbitrary progress", UnitTest, DatabaseTest) {
    val genHash: Gen[ByteString] = Gen.listOfN(32, Arbitrary.arbitrary[Byte]).map(bs => ByteString(bs.toArray))
    val gen: Gen[RecoveryProgress] = for {
      root <- genHash
      shardCount <- Gen.chooseNum(0, 256)
      completed <- Gen.someOf(0 until math.max(shardCount, 1)).map(_.toSet)
      bytecodes <- Gen.listOf(genHash).map(_.toVector)
      storage <- Gen.listOf(Gen.zip(genHash, genHash)).map(_.toVector)
    } yield RecoveryProgress(root, shardCount, completed, bytecodes, storage)

    forAll(gen) { p =>
      assert(RecoveryProgress.deserialize(RecoveryProgress.serialize(p)).contains(p))
    }
  }
}
