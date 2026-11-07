package com.chipprbots.ethereum.blockchain.sync.snap.actors.storage

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

class StorageOrderedApplyCommitSpec extends AnyFlatSpec with Matchers:

  "StorageOrderedApply.commitBuffered" should "keep the buffered nodes when the write throws (#1549)" taggedAs (
    UnitTest,
    DatabaseTest
  ) in {
    val buffer = mutable.ArrayBuffer(1, 2, 3)
    val boom = new RuntimeException("disk full")

    val thrown = the[RuntimeException] thrownBy
      StorageOrderedApply.commitBuffered(buffer)(_ => throw boom)

    thrown shouldBe boom
    buffer.toList shouldEqual List(1, 2, 3)
  }

  it should "write the whole buffer once, then clear it, on success" taggedAs (UnitTest, DatabaseTest) in {
    val buffer = mutable.ArrayBuffer(1, 2, 3)
    val written = mutable.ArrayBuffer.empty[Seq[Int]]

    StorageOrderedApply.commitBuffered(buffer)(written += _)

    written.toList shouldEqual List(List(1, 2, 3))
    buffer shouldBe empty
  }

  it should "not call the write for an empty buffer" taggedAs (UnitTest, DatabaseTest) in {
    var calls = 0
    StorageOrderedApply.commitBuffered(mutable.ArrayBuffer.empty[Int])(_ => calls += 1)
    calls shouldEqual 0
  }
