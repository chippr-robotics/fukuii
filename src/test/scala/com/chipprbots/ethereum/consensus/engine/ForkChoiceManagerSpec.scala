package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager.BeaconHead
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.testing.Tags.*

/** Verifies the publisher pattern added in #1207: every `applyForkChoiceState` call must notify the registered listener
  * with a `BeaconHead` message — including the unknown-head (Left("SYNCING")) branch, which is the trigger SNAP needs
  * on post-merge chains.
  */
class ForkChoiceManagerSpec extends ScalaTestWithActorTestKit with AnyFlatSpecLike with Matchers:

  implicit private val classicActorSystem: org.apache.pekko.actor.ActorSystem = system.toClassic

  trait Fixture extends EphemBlockchainTestSetup:
    val fcm = new ForkChoiceManager(blockchainReader, blockchainWriter)

    val storedHeader: BlockHeader = BlockHeader(
      parentHash = BlockHash(ByteString(new Array[Byte](32))),
      ommersHash = BlockHash(BlockHeader.EmptyOmmers),
      beneficiary = ByteString(new Array[Byte](20)),
      stateRoot = TrieRoot(ByteString(Array.fill(32)(0x44.toByte))),
      transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
      receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
      logsBloom = BloomFilter.Empty,
      difficulty = Difficulty.Zero,
      number = BlockNumber(12345),
      gasLimit = GasAmount(30000000),
      gasUsed = GasAmount(0),
      unixTimestamp = Timestamp(1700000000),
      extraData = ByteString.empty,
      mixHash = BlockHash(ByteString(new Array[Byte](32))),
      nonce = ByteString(new Array[Byte](8)),
      extraFields = HefPostOlympia(BigInt("1000000000"))
    )
    blockchainWriter.storeBlockHeader(storedHeader).commit()

    val unknownHeadHash: ByteString = ByteString(Array.fill(32)(0x99.toByte))
    val knownHeadHash: ByteString = storedHeader.hash.value

  "ForkChoiceManager" should "publish BeaconHead with knownHeader=None when head is unknown (SYNCING branch)" taggedAs UnitTest in new Fixture:
    val probe: TestProbe = TestProbe()
    fcm.setListener(probe.ref)

    val state: ForkChoiceState = ForkChoiceState(unknownHeadHash, ByteString.empty, ByteString.empty)
    fcm.applyForkChoiceState(state) shouldBe Left("SYNCING")

    val received: BeaconHead = probe.expectMsgType[ForkChoiceManager.BeaconHead]
    received.headHash shouldBe unknownHeadHash
    received.knownHeader shouldBe None

  it should "publish BeaconHead with knownHeader=Some when head is locally known" taggedAs UnitTest in new Fixture:
    val probe: TestProbe = TestProbe()
    fcm.setListener(probe.ref)

    val state: ForkChoiceState = ForkChoiceState(knownHeadHash, ByteString.empty, ByteString.empty)
    fcm.applyForkChoiceState(state) shouldBe Right(())

    val received: BeaconHead = probe.expectMsgType[ForkChoiceManager.BeaconHead]
    received.headHash shouldBe knownHeadHash
    received.knownHeader.map(_.number) shouldBe Some(BigInt(12345))

  it should "not throw when no listener is registered" taggedAs UnitTest in new Fixture:
    // No listener — must still succeed
    val state: ForkChoiceState = ForkChoiceState(unknownHeadHash, ByteString.empty, ByteString.empty)
    fcm.applyForkChoiceState(state) shouldBe Left("SYNCING")

  it should "stop publishing after clearListener" taggedAs UnitTest in new Fixture:
    val probe: TestProbe = TestProbe()
    fcm.setListener(probe.ref)
    fcm.applyForkChoiceState(ForkChoiceState(knownHeadHash, ByteString.empty, ByteString.empty))
    probe.expectMsgType[ForkChoiceManager.BeaconHead]

    fcm.clearListener()
    fcm.applyForkChoiceState(ForkChoiceState(unknownHeadHash, ByteString.empty, ByteString.empty))
    probe.expectNoMessage()

  it should "replace the previously-registered listener on a second setListener" taggedAs UnitTest in new Fixture:
    val first: TestProbe = TestProbe()
    val second: TestProbe = TestProbe()
    fcm.setListener(first.ref)
    fcm.setListener(second.ref)

    fcm.applyForkChoiceState(ForkChoiceState(knownHeadHash, ByteString.empty, ByteString.empty))
    second.expectMsgType[ForkChoiceManager.BeaconHead]
    first.expectNoMessage()

  // Requested head vs executed head. The p2p fork choice (DesignatedHead) reads the REQUESTED head; everything that
  // means "validated" reads the executed one. These pin that the two stay separate.

  it should "leave the executed head untouched on notifyBeaconHead, but record it as requested" taggedAs UnitTest in new Fixture:
    fcm.applyForkChoiceState(ForkChoiceState(knownHeadHash, ByteString.empty, ByteString.empty)) shouldBe Right(())
    val executedState = fcm.getState

    fcm.notifyBeaconHead(ForkChoiceState(unknownHeadHash, ByteString.empty, ByteString.empty))

    fcm.getHeadBlockHash shouldBe Some(knownHeadHash)
    fcm.getState shouldBe executedState
    fcm.getRequestedHeadBlockHash shouldBe Some(unknownHeadHash)

  it should "not activate on notifyBeaconHead alone" taggedAs UnitTest in new Fixture:
    fcm.notifyBeaconHead(ForkChoiceState(knownHeadHash, ByteString.empty, ByteString.empty))
    fcm.isActive shouldBe false
    fcm.getHeadBlockHash shouldBe None
    fcm.getRequestedHeadBlockHash shouldBe Some(knownHeadHash)

  it should "record the requested head on applyForkChoiceState, head known or not" taggedAs UnitTest in new Fixture:
    fcm.applyForkChoiceState(ForkChoiceState(unknownHeadHash, ByteString.empty, ByteString.empty)) shouldBe Left(
      "SYNCING"
    )
    fcm.getRequestedHeadBlockHash shouldBe Some(unknownHeadHash)
    fcm.getHeadBlockHash shouldBe None

    fcm.applyForkChoiceState(ForkChoiceState(knownHeadHash, ByteString.empty, ByteString.empty)) shouldBe Right(())
    fcm.getRequestedHeadBlockHash shouldBe Some(knownHeadHash)
    fcm.getHeadBlockHash shouldBe Some(knownHeadHash)

  it should "clear both the executed and the requested head" taggedAs UnitTest in new Fixture:
    fcm.applyForkChoiceState(ForkChoiceState(knownHeadHash, ByteString.empty, ByteString.empty))
    fcm.notifyBeaconHead(ForkChoiceState(unknownHeadHash, ByteString.empty, ByteString.empty))
    fcm.clear()
    fcm.getHeadBlockHash shouldBe None
    fcm.getRequestedHeadBlockHash shouldBe None
