package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.ReorgStateHandler
import com.chipprbots.ethereum.consensus.engine.ForkChoiceManager.BeaconHead
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.*

/** Verifies the publisher pattern added in #1207: every `applyForkChoiceState` call must notify the registered listener
  * with a `BeaconHead` message — including the unknown-head (Left(Refusal.Syncing)) branch, which is the trigger SNAP
  * needs on post-merge chains.
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
    fcm.applyForkChoiceState(state) shouldBe Left(ForkChoiceManager.Refusal.Syncing)

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

  // B-511: on the hash layout the fork choice holds the head's lock over its move AND the reference counts that follow
  // it, so that a branch the p2p import settles is never between the move and the counts: it would re-adopt or abandon
  // against an index the counts do not match yet. The move's own lock (promoteToCanonicalHead) covers the move alone.
  it should "take the reference counts that follow a move of the head under the head's lock" taggedAs UnitTest in (
    new Fixture:
      @volatile var countedUnderTheLock: List[Boolean] = Nil
      val recording: ReorgStateHandler = new com.chipprbots.ethereum.consensus.ReorgStateHandler:
        override def abandonBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit =
          countedUnderTheLock :+= com.chipprbots.ethereum.domain.CanonicalHeadLock.isHeldByCurrentThread
        override def readoptBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit =
          countedUnderTheLock :+= com.chipprbots.ethereum.domain.CanonicalHeadLock.isHeldByCurrentThread
      val counted = new ForkChoiceManager(blockchainReader, blockchainWriter, recording)
      counted.applyForkChoiceState(ForkChoiceState(knownHeadHash, ByteString.empty, ByteString.empty)) shouldBe Right(
        ()
      )
      countedUnderTheLock shouldBe List(true, true)
  )

  // B-511 (vault): the blocks a move adopts are counted again before its write, and those it drops are taken out after.
  // A stop between the two then leaves a block counted that is not indexed, which only keeps nodes longer; never one
  // indexed that is not counted, whose nodes the prune would delete under the head.
  it should "put back the counts of what a move adopts before its write, and take out what it drops after" taggedAs UnitTest in (
    new Fixture:
      @volatile var bestAt: List[(String, Option[ByteString])] = Nil
      def best: Option[ByteString] = blockchainReader.getBestBlockHeader.map(_.hash.value)
      val recording: ReorgStateHandler = new com.chipprbots.ethereum.consensus.ReorgStateHandler:
        override def abandonBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit = bestAt :+= ("abandon" -> best)
        override def readoptBlockStates(blocks: Seq[(BigInt, ByteString)]): Unit = bestAt :+= ("readopt" -> best)
      val before = best
      before should not be Some(knownHeadHash)
      val counted = new ForkChoiceManager(blockchainReader, blockchainWriter, recording)
      counted.applyForkChoiceState(ForkChoiceState(knownHeadHash, ByteString.empty, ByteString.empty)) shouldBe Right(
        ()
      )
      bestAt shouldBe List("readopt" -> before, "abandon" -> Some(knownHeadHash))
  )

  it should "not throw when no listener is registered" taggedAs UnitTest in new Fixture:
    // No listener — must still succeed
    val state: ForkChoiceState = ForkChoiceState(unknownHeadHash, ByteString.empty, ByteString.empty)
    fcm.applyForkChoiceState(state) shouldBe Left(ForkChoiceManager.Refusal.Syncing)

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
      ForkChoiceManager.Refusal.Syncing
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

  // spec 018 C5a, B-297: a head refused -38002 is not left as the head the p2p import path follows
  it should "put the requested head back to the head last followed when a refused head is forgotten" taggedAs UnitTest in new Fixture:
    fcm.applyForkChoiceState(ForkChoiceState(unknownHeadHash, unknownHeadHash, unknownHeadHash)) shouldBe Left(
      ForkChoiceManager.Refusal.Syncing
    )
    fcm.getRequestedHeadBlockHash shouldBe Some(unknownHeadHash)
    fcm.forgetRefused(unknownHeadHash)
    // none was followed yet
    fcm.getRequestedHeadBlockHash shouldBe None

  it should "not forget a head named since the refused one" taggedAs UnitTest in new Fixture:
    val newer: ByteString = ByteString(Array.fill(32)(0x77.toByte))
    fcm.notifyBeaconHead(ForkChoiceState(unknownHeadHash, unknownHeadHash, unknownHeadHash))
    fcm.notifyBeaconHead(ForkChoiceState(newer, newer, newer))
    fcm.forgetRefused(unknownHeadHash)
    fcm.getRequestedHeadBlockHash shouldBe Some(newer)

  // B-462 (beacon Note A): a move of the head refused because another thread's adoption holds it wrote nothing and is
  // no fault of this node. SYNCING, never a LocalFault, which the escalation counts: not even on a store that failed
  // closed, where a StateUnavailable would be answered as one
  it should "answer a fork choice refused while another adoption holds the head SYNCING, never as a local fault" taggedAs UnitTest in new Fixture:
    val busyWriter: com.chipprbots.ethereum.domain.BlockchainWriter =
      new com.chipprbots.ethereum.domain.BlockchainWriter(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        pathState = Some(null)
      ):
        override def stateFailedClosed: Boolean = true
        override def finalizedOfTheState: Option[com.chipprbots.ethereum.db.storage.pathstate.FinalizedBlock] = None
        override def moveHeadInPlace(
            target: BlockHeader,
            reader: com.chipprbots.ethereum.domain.BlockchainReader,
            refuse: com.chipprbots.ethereum.db.storage.pathstate.HeadMovePlan.Chunk => Option[String],
            finalized: Option[com.chipprbots.ethereum.db.storage.pathstate.FinalizedBlock],
            admit: com.chipprbots.ethereum.db.storage.pathstate.HeadMovePlan => Option[String],
            forkChoice: Boolean
        ): com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage.MoveOutcome =
          throw new com.chipprbots.ethereum.db.storage.pathstate.StateBusy(
            "the adoption on thread 'adopter'",
            s"the move of the head to block ${target.number.value} was not made"
          )
    val busy = new ForkChoiceManager(blockchainReader, busyWriter)
    busy.applyForkChoiceState(
      ForkChoiceState(knownHeadHash, ForkChoiceManager.ZeroHash, ForkChoiceManager.ZeroHash)
    ) shouldBe Left(ForkChoiceManager.Refusal.Syncing)
    busy.getHeadBlockHash shouldBe None

  // B-126 (#1654 follow-up). A payload stored by its hash only keeps the weight newPayload derived from its parent's
  // weight then. ChainWeightReconcile repairs canonical weights only (its finish checks the mapped heights), so a side
  // payload stored while it repairs, and made canonical by a later fork choice, would keep its parent's unrepaired
  // weight under the marker. The fork choice re-derives the weights of the blocks it adopts.

  /** Genesis and `a1` canonical; `s1` on genesis and `s2` on `s1` stored by their hashes only, as side payloads, each
    * with the weight derived from genesis's weight BEFORE a repair. Then genesis's weight is repaired.
    */
  trait WeightFixture extends EphemBlockchainTestSetup:
    val writtenWeights = new java.util.concurrent.CountDownLatch(1)

    override lazy val blockchainWriter: BlockchainWriter =
      val s = storagesInstance.storages
      new BlockchainWriter(
        s.blockHeadersStorage,
        s.blockBodiesStorage,
        s.blockNumberMappingStorage,
        s.transactionMappingStorage,
        s.receiptStorage,
        s.chainWeightStorage,
        s.appStateStorage,
        s.blockAccessListStorage,
        pathState = s.pathStateStorage
      ):
        override def storeChainWeights(weights: Seq[(BlockHash, ChainWeight)]): DataSourceBatchUpdate =
          writtenWeights.countDown()
          super.storeChainWeights(weights)

    lazy val fcm = new ForkChoiceManager(blockchainReader, blockchainWriter)

    private def header(number: Int, parent: Option[BlockHeader], difficulty: Int, salt: Int): BlockHeader =
      BlockHeader(
        parentHash = BlockHash(parent.map(_.hash.value).getOrElse(ByteString(new Array[Byte](32)))),
        ommersHash = BlockHash(BlockHeader.EmptyOmmers),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = TrieRoot(ByteString(Array.fill(32)(0x44.toByte))),
        transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
        receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
        logsBloom = BloomFilter.Empty,
        difficulty = Difficulty(BigInt(difficulty)),
        number = BlockNumber(number),
        gasLimit = GasAmount(30000000),
        gasUsed = GasAmount(0),
        unixTimestamp = Timestamp(1700000000L + number),
        extraData = ByteString(salt.toByte),
        mixHash = BlockHash(ByteString(new Array[Byte](32))),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostOlympia(BigInt("1000000000"))
      )

    val genesis: BlockHeader = header(0, None, 100, 0)
    val a1: BlockHeader = header(1, Some(genesis), 10, 0)
    val s1: BlockHeader = header(1, Some(genesis), 20, 1)
    val s2: BlockHeader = header(2, Some(s1), 30, 1)

    def td(n: Int): ChainWeight = ChainWeight.totalDifficultyOnly(BigInt(n))
    def weightOf(h: BlockHeader): Option[ChainWeight] = blockchainReader.getChainWeightByHash(h.hash)

    val unrepaired = 100
    val repaired = 5000
    Seq(genesis, a1).foreach(h => blockchainWriter.storeBlockHeader(h).commit())
    Seq(s1, s2).foreach(h => blockchainWriter.storeBlockHeaderByHashOnly(h).commit())
    storagesInstance.storages.appStateStorage.putBestBlockInfo(BlockInfo(a1.hash.value, 1)).commit()
    blockchainWriter.storeChainWeight(a1.hash, td(repaired + 10)).commit()
    blockchainWriter.storeChainWeight(s1.hash, td(unrepaired + 20)).commit()
    blockchainWriter.storeChainWeight(s2.hash, td(unrepaired + 20 + 30)).commit()
    blockchainWriter.storeChainWeight(genesis.hash, td(repaired)).commit()

    def choose(head: BlockHeader): Either[ForkChoiceManager.Refusal, Unit] =
      fcm.applyForkChoiceState(ForkChoiceState(head.hash.value, ByteString.empty, ByteString.empty))

  it should "re-derive the weights of side payloads a fork choice makes canonical, from the repaired parent (B-126)" taggedAs UnitTest in new WeightFixture:
    choose(s2) shouldBe Right(())

    blockchainReader.getCanonicalHashByNumber(1) shouldBe Some(s1.hash)
    blockchainReader.getCanonicalHashByNumber(2) shouldBe Some(s2.hash)
    weightOf(s1) shouldBe Some(td(repaired + 20))
    weightOf(s2) shouldBe Some(td(repaired + 20 + 30))
    // the block it left keeps its own (it was not adopted), and a confirmation of the head writes nothing more
    weightOf(a1) shouldBe Some(td(repaired + 10))
    choose(s2) shouldBe Right(())
    weightOf(s2) shouldBe Some(td(repaired + 20 + 30))

  it should "not invent a weight for an adopted branch whose base has none (B-126)" taggedAs UnitTest in new WeightFixture:
    storagesInstance.storages.chainWeightStorage.remove(genesis.hash.value).commit()

    choose(s2) shouldBe Right(())

    weightOf(s1) shouldBe Some(td(unrepaired + 20))
    weightOf(s2) shouldBe Some(td(unrepaired + 20 + 30))

  it should "re-derive the adopted weights outside the chain-weight repair's final step (B-126)" taggedAs UnitTest in new WeightFixture:
    // ChainWeightReconcile's finish holds ChainInsertLock exclusively while it repairs and sets its marker: the
    // re-derivation reads the base's weight and writes the branch's under the lock, shared, so it can neither read a
    // weight the finish is about to repair nor land between its check and its marker.
    val chosen = new java.util.concurrent.atomic.AtomicReference[Option[Either[ForkChoiceManager.Refusal, Unit]]](None)
    val writtenDuringFinish: (Boolean, Thread) =
      com.chipprbots.ethereum.ledger.ChainInsertLock.exclusive {
        storagesInstance.storages.chainWeightStorage.put(genesis.hash.value, td(unrepaired)).commit()
        val fork = new Thread(() => chosen.set(Some(choose(s2))))
        fork.start()
        // A bounded wait for something that must NOT happen: a weight written while the finish holds the lock
        val written = writtenWeights.await(2, java.util.concurrent.TimeUnit.SECONDS)
        // the finish repairs the base, as its batch does
        storagesInstance.storages.chainWeightStorage.put(genesis.hash.value, td(repaired)).commit()
        (written, fork)
      }
    writtenDuringFinish._2.join(30000)

    chosen.get shouldBe Some(Right(()))
    writtenDuringFinish._1 shouldBe false
    weightOf(s1) shouldBe Some(td(repaired + 20))
    weightOf(s2) shouldBe Some(td(repaired + 20 + 30))
