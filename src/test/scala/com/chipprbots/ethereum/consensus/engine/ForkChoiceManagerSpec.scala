package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.{TestKit, TestProbe}
import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields._
import com.chipprbots.ethereum.testing.Tags._

/** Verifies the publisher pattern added in #1207: every `applyForkChoiceState` call must notify the registered listener
  * with a `BeaconHead` message — including the unknown-head (Left("SYNCING")) branch, which is the trigger SNAP needs
  * on post-merge chains.
  */
class ForkChoiceManagerSpec extends TestKit(ActorSystem("ForkChoiceManagerSpec")) with AnyFlatSpecLike with Matchers {

  trait Fixture extends EphemBlockchainTestSetup {
    val fcm = new ForkChoiceManager(blockchainReader, blockchainWriter)

    val storedHeader: BlockHeader = BlockHeader(
      parentHash = ByteString(new Array[Byte](32)),
      ommersHash = BlockHeader.EmptyOmmers,
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = ByteString(Array.fill(32)(0x44.toByte)),
      transactionsRoot = BlockHeader.EmptyMpt,
      receiptsRoot = BlockHeader.EmptyMpt,
      logsBloom = ByteString(new Array[Byte](256)),
      difficulty = 0,
      number = 12345,
      gasLimit = 30000000,
      gasUsed = 0,
      unixTimestamp = 1700000000,
      extraData = ByteString.empty,
      mixHash = ByteString(new Array[Byte](32)),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = HefPostOlympia(BigInt("1000000000"))
    )
    blockchainWriter.storeBlockHeader(storedHeader).commit()

    val unknownHeadHash: ByteString = ByteString(Array.fill(32)(0x99.toByte))
    val knownHeadHash: ByteString = storedHeader.hash
  }

  "ForkChoiceManager" should "publish BeaconHead with knownHeader=None when head is unknown (SYNCING branch)" taggedAs UnitTest in new Fixture {
    val probe = TestProbe()
    fcm.setListener(probe.ref)

    val state = ForkChoiceState(unknownHeadHash, ByteString.empty, ByteString.empty)
    fcm.applyForkChoiceState(state) shouldBe Left("SYNCING")

    val received = probe.expectMsgType[ForkChoiceManager.BeaconHead]
    received.headHash shouldBe unknownHeadHash
    received.knownHeader shouldBe None
  }

  it should "publish BeaconHead with knownHeader=Some when head is locally known" taggedAs UnitTest in new Fixture {
    val probe = TestProbe()
    fcm.setListener(probe.ref)

    val state = ForkChoiceState(knownHeadHash, ByteString.empty, ByteString.empty)
    fcm.applyForkChoiceState(state) shouldBe Right(())

    val received = probe.expectMsgType[ForkChoiceManager.BeaconHead]
    received.headHash shouldBe knownHeadHash
    received.knownHeader.map(_.number) shouldBe Some(BigInt(12345))
  }

  it should "not throw when no listener is registered" taggedAs UnitTest in new Fixture {
    // No listener — must still succeed
    val state = ForkChoiceState(unknownHeadHash, ByteString.empty, ByteString.empty)
    fcm.applyForkChoiceState(state) shouldBe Left("SYNCING")
  }

  it should "stop publishing after clearListener" taggedAs UnitTest in new Fixture {
    val probe = TestProbe()
    fcm.setListener(probe.ref)
    fcm.applyForkChoiceState(ForkChoiceState(knownHeadHash, ByteString.empty, ByteString.empty))
    probe.expectMsgType[ForkChoiceManager.BeaconHead]

    fcm.clearListener()
    fcm.applyForkChoiceState(ForkChoiceState(unknownHeadHash, ByteString.empty, ByteString.empty))
    probe.expectNoMessage()
  }

  it should "replace the previously-registered listener on a second setListener" taggedAs UnitTest in new Fixture {
    val first = TestProbe()
    val second = TestProbe()
    fcm.setListener(first.ref)
    fcm.setListener(second.ref)

    fcm.applyForkChoiceState(ForkChoiceState(knownHeadHash, ByteString.empty, ByteString.empty))
    second.expectMsgType[ForkChoiceManager.BeaconHead]
    first.expectNoMessage()
  }

  it should "update number→hash mapping and best-block pointer atomically on a reorg (B3 fix)" taggedAs UnitTest in new Fixture {
    // forkHeader: same number as storedHeader, different hash (different difficulty).
    // Stored by hash only — NOT inserted into number→hash mapping — simulating a
    // sidechain block that becomes canonical via engine_forkchoiceUpdated.
    val forkHeader = storedHeader.copy(difficulty = 999999)
    storagesInstance.storages.blockHeadersStorage.put(forkHeader.hash, forkHeader).commit()

    // Pre-condition: forkHeader is not yet canonical at its number.
    blockchainReader.getBlockHeaderByNumber(forkHeader.number).map(_.hash) should not be Some(forkHeader.hash)

    // Apply fork choice — promoteBranchAndSetBest must rewrite canonical number→hash
    // AND advance bestBlockInfo in a single commit (the B3 invariant).
    val state = ForkChoiceState(forkHeader.hash, ByteString.empty, ByteString.empty)
    fcm.applyForkChoiceState(state) shouldBe Right(())

    // Both mappings must be consistent (proof that the single-commit write landed).
    blockchainReader.getBlockHeaderByNumber(forkHeader.number).map(_.hash) shouldBe Some(forkHeader.hash)
    val bestInfo = storagesInstance.storages.appStateStorage.getBestBlockInfo()
    bestInfo.hash shouldBe forkHeader.hash
    bestInfo.number shouldBe BigInt(12345)
  }
}
