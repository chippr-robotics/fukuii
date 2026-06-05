package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.event.Logging._

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.testing.Tags._

/** Tests for NewBlockImportMessages log format.
  *
  * Verifies: reorg log levels (core-geth vocabulary), field presence, large-reorg threshold, importedToTheTop fields.
  */
class ImportMessagesSpec extends AnyWordSpec with Matchers {

  private val testPeer: PeerId = PeerId("test-peer")

  private def blockAt(number: Int): Block =
    Block(BlockHelpers.defaultHeader.copy(number = BigInt(number)), BlockBody(Nil, Nil))

  private def messages(tipNumber: Int = 100): NewBlockImportMessages =
    new NewBlockImportMessages(blockAt(tipNumber), testPeer)

  "NewBlockImportMessages.importedToTheTop" should {

    "produce InfoLevel with txs, gas, uncles, number, and peer fields" taggedAs (UnitTest, StateTest) in {
      val block =
        Block(BlockHelpers.defaultHeader.copy(number = BigInt(42), gasUsed = 1_000_000), BlockBody(Nil, Nil))
      val msgs = new NewBlockImportMessages(block, testPeer)

      val (level, msg) = msgs.importedToTheTop()

      level shouldBe InfoLevel
      msg should include("number=42")
      msg should include("txs=0")
      msg should include("gas=1000000")
      msg should include("uncles=0")
      msg should include(s"peer=$testPeer")
    }

    "report non-zero tx and uncle counts from block body" taggedAs (UnitTest, StateTest) in {
      val uncle = BlockHelpers.defaultHeader.copy(number = BigInt(41))
      val stx    = BlockHelpers.generateBlock(BlockHelpers.genesis).body.transactionList.head
      val block = Block(
        BlockHelpers.defaultHeader.copy(number = BigInt(42)),
        BlockBody(List(stx), List(uncle))
      )
      val msgs = new NewBlockImportMessages(block, testPeer)

      val (_, msg) = msgs.importedToTheTop()

      msg should include("txs=1")
      msg should include("uncles=1")
    }
  }

  "NewBlockImportMessages.reorganisedChain" should {

    "produce InfoLevel 'Chain reorg detected' for small reorg (≤ 63 dropped blocks)" taggedAs (UnitTest, StateTest) in {
      val oldBranch = (10 to 19).map(blockAt).toList   // 10 dropped blocks
      val newBranch = (10 to 15).map(blockAt).toList   // 6 added blocks

      val (level, msg) = messages().reorganisedChain(oldBranch, newBranch)

      level shouldBe InfoLevel
      msg should startWith("Chain reorg detected")
      msg should include("number=9")      // ancestorNumber = 10 - 1
      msg should include("drop=10")
      msg should include("dropfrom=10")
      msg should include("add=6")
      msg should include("addfrom=10")
      msg should include(s"peer=$testPeer")
    }

    "produce WarningLevel 'Large chain reorg detected' for large reorg (> 63 dropped blocks)" taggedAs (UnitTest, StateTest) in {
      val oldBranch = (10 to 73).map(blockAt).toList   // 64 dropped blocks — crosses the 63-block threshold
      val newBranch = (10 to 12).map(blockAt).toList

      val (level, msg) = messages().reorganisedChain(oldBranch, newBranch)

      level shouldBe WarningLevel
      msg should startWith("Large chain reorg detected")
      msg should include("number=9")
      msg should include("drop=64")
      msg should include("add=3")
    }

    "use '?' for ancestor hash when oldBranch is empty (pure extension case)" taggedAs (UnitTest, StateTest) in {
      val newBranch = List(blockAt(101))

      val (level, msg) = messages(tipNumber = 100).reorganisedChain(Nil, newBranch)

      level shouldBe InfoLevel
      msg should include("hash=?")
    }

    "treat exactly 63 dropped blocks as a small reorg (InfoLevel)" taggedAs (UnitTest, StateTest) in {
      val oldBranch = (10 to 72).map(blockAt).toList   // 63 blocks — just below the large-reorg threshold
      val newBranch = (10 to 10).map(blockAt).toList

      val (level, _) = messages().reorganisedChain(oldBranch, newBranch)

      level shouldBe InfoLevel
    }

    "treat exactly 64 dropped blocks as a large reorg (WarningLevel)" taggedAs (UnitTest, StateTest) in {
      val oldBranch = (10 to 73).map(blockAt).toList   // 64 blocks — first value above the threshold
      val newBranch = (10 to 10).map(blockAt).toList

      val (level, _) = messages().reorganisedChain(oldBranch, newBranch)

      level shouldBe WarningLevel
    }
  }
}
