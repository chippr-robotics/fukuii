package com.chipprbots.ethereum.consensus.engine

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BloomFilter
import com.chipprbots.ethereum.domain.CanonicalHeadLock
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.domain.GasAmount
import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.*

/** B-245: a fork choice tells the block topic's publisher of each block it makes canonical, so that `eth_subscribe
  * newHeads` sees the heads the Engine API makes (BlockImporter, the other publisher, is never reached by it).
  *
  * The publisher is called synchronously on the fork choice's thread, so the recording needs no waiting.
  */
class ForkChoiceManagerNewHeadsSpec extends AnyFlatSpec with Matchers:

  trait Fixture extends EphemBlockchainTestSetup:
    @volatile var told: Vector[Block] = Vector.empty
    @volatile var toldUnderTheLock: Vector[Boolean] = Vector.empty

    lazy val fcm = new ForkChoiceManager(
      blockchainReader,
      blockchainWriter,
      onNewCanonical = block =>
        told :+= block
        toldUnderTheLock :+= CanonicalHeadLock.isHeldByCurrentThread
    )

    def header(number: Int, parent: Option[BlockHeader], salt: Int): BlockHeader =
      BlockHeader(
        parentHash = BlockHash(parent.map(_.hash.value).getOrElse(ByteString(new Array[Byte](32)))),
        ommersHash = BlockHash(BlockHeader.EmptyOmmers),
        beneficiary = ByteString(new Array[Byte](20)),
        stateRoot = TrieRoot(ByteString(Array.fill(32)(0x44.toByte))),
        transactionsRoot = TrieRoot(BlockHeader.EmptyMpt),
        receiptsRoot = TrieRoot(BlockHeader.EmptyMpt),
        logsBloom = BloomFilter.Empty,
        difficulty = Difficulty.Zero,
        number = BlockNumber(number),
        gasLimit = GasAmount(30000000),
        gasUsed = GasAmount(0),
        unixTimestamp = Timestamp(1700000000L + number),
        extraData = ByteString(salt.toByte),
        mixHash = BlockHash(ByteString(new Array[Byte](32))),
        nonce = ByteString(new Array[Byte](8)),
        extraFields = HefPostOlympia(BigInt("1000000000"))
      )

    def blockOf(h: BlockHeader): Block = Block(h, BlockBody.empty)

    // canonical: genesis, a1. On a1: a2, a3, a4 (stored by hash only, as newPayload stores them). On genesis: s1, s2.
    val genesis: BlockHeader = header(0, None, 0)
    val a1: BlockHeader = header(1, Some(genesis), 0)
    val a2: BlockHeader = header(2, Some(a1), 0)
    val a3: BlockHeader = header(3, Some(a2), 0)
    val a4: BlockHeader = header(4, Some(a3), 0)
    val s1: BlockHeader = header(1, Some(genesis), 1)
    val s2: BlockHeader = header(2, Some(s1), 1)

    Seq(genesis, a1).foreach(h => blockchainWriter.storeBlock(blockOf(h)).commit())
    Seq(a2, a3, a4, s1, s2).foreach(h => blockchainWriter.storeBlockByHashOnly(blockOf(h)).commit())
    storagesInstance.storages.appStateStorage.putBestBlockInfo(BlockInfo(a1.hash.value, 1)).commit()

    def choose(head: BlockHeader): Either[ForkChoiceManager.Refusal, Unit] =
      fcm.applyForkChoiceState(ForkChoiceState(head.hash.value, ByteString.empty, ByteString.empty))

    def toldHashes: Seq[ByteString] = told.map(_.header.hash.value)

  "ForkChoiceManager" should "tell the publisher of the new head a forkchoiceUpdated moves to" taggedAs UnitTest in new Fixture:
    choose(a2) shouldBe Right(())
    toldHashes shouldBe Seq(a2.hash.value)

  it should "tell of every block a jump of several makes canonical, lowest first" taggedAs UnitTest in new Fixture:
    choose(a4) shouldBe Right(())
    toldHashes shouldBe Seq(a2, a3, a4).map(_.hash.value)

  it should "tell of the new branch's blocks on a reorg, and not of the ones it leaves" taggedAs UnitTest in new Fixture:
    choose(s2) shouldBe Right(())
    toldHashes shouldBe Seq(s1, s2).map(_.hash.value)
    blockchainReader.getCanonicalHashByNumber(1) shouldBe Some(s1.hash)

  it should "not tell again of a block already canonical (imported and published), nor of a repeated fork choice" taggedAs UnitTest in new Fixture:
    // a1 is canonical, as BlockImporter leaves a block it has imported and published
    choose(a1) shouldBe Right(())
    told shouldBe empty
    choose(a3) shouldBe Right(())
    choose(a3) shouldBe Right(())
    toldHashes shouldBe Seq(a2, a3).map(_.hash.value)
    // extending by one tells only of the new block
    choose(a4) shouldBe Right(())
    toldHashes shouldBe Seq(a2, a3, a4).map(_.hash.value)

  it should "tell after the move is committed and the head's lock is released" taggedAs UnitTest in new Fixture:
    var bestWhenTold: Vector[Option[ByteString]] = Vector.empty
    val observing = new ForkChoiceManager(
      blockchainReader,
      blockchainWriter,
      onNewCanonical = _ => bestWhenTold :+= blockchainReader.getBestBlockHeader.map(_.hash.value)
    )
    observing.applyForkChoiceState(ForkChoiceState(a3.hash.value, ByteString.empty, ByteString.empty)) shouldBe Right(
      ()
    )
    bestWhenTold shouldBe Vector.fill(2)(Some(a3.hash.value))
    // choose() through `fcm` records the lock state of its own call
    choose(a4) shouldBe Right(())
    toldUnderTheLock shouldBe Vector(false)

  it should "not fail a fork choice whose publisher throws" taggedAs UnitTest in new Fixture:
    val failing = new ForkChoiceManager(
      blockchainReader,
      blockchainWriter,
      onNewCanonical = _ => throw new RuntimeException("topic gone")
    )
    failing.applyForkChoiceState(ForkChoiceState(a2.hash.value, ByteString.empty, ByteString.empty)) shouldBe Right(())
    blockchainReader.getBestBlockHeader.map(_.hash) shouldBe Some(a2.hash)
