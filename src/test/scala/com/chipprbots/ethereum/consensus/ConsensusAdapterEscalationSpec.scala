package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EscalationTrigger
import com.chipprbots.ethereum.blockchain.sync.EscalationWatch
import com.chipprbots.ethereum.blockchain.sync.PowEscalationFeed
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportStateFault
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportedToTop
import com.chipprbots.ethereum.blockchain.sync.regular.ChainReorganised
import com.chipprbots.ethereum.consensus.Consensus.ConsensusResult
import com.chipprbots.ethereum.consensus.Consensus.ConsensusStateFault
import com.chipprbots.ethereum.consensus.Consensus.ExtendedCurrentBestBranch
import com.chipprbots.ethereum.consensus.Consensus.SelectedNewBestBranch
import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
import com.chipprbots.ethereum.db.storage.pathstate.ReorgRefusal
import com.chipprbots.ethereum.db.storage.pathstate.ReorgTooDeep
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.ledger.BlockData
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** That every result of an import of proof-of-work blocks reaches the escalation's feed, with the branch it is of and
  * the header validation of the import (spec 018 C5a part 6b).
  */
class ConsensusAdapterEscalationSpec extends AnyFlatSpec with Matchers:

  private given IORuntime = IORuntime.global
  private given BlockchainConfig = Config.blockchains.blockchainConfig

  private val forkHash = BlockHash(ByteString(Array.fill[Byte](32)(7)))

  private def branch(from: Int, blocks: Int): NonEmptyList[Block] =
    NonEmptyList.fromListUnsafe(
      (0 until blocks)
        .foldLeft(List.empty[Block]) { (made, i) =>
          val header = Fixtures.Blocks.ValidBlock.header.copy(
            parentHash = made.headOption.fold(forkHash)(_.header.hash),
            number = BlockNumber(from + i),
            difficulty = Difficulty(100)
          )
          Block(header, BlockBody.empty) :: made
        }
        .reverse
    )

  private class Rig(result: NonEmptyList[Block] => ConsensusResult):
    val escalated: mutable.ArrayBuffer[EscalationKind] = mutable.ArrayBuffer.empty
    val validated: mutable.ArrayBuffer[List[BigInt]] = mutable.ArrayBuffer.empty
    var head: ChainWeight = ChainWeight.totalDifficultyOnly(1050)
    var broken = false
    val weights: mutable.Map[BlockHash, ChainWeight] = mutable.Map(forkHash -> ChainWeight.totalDifficultyOnly(1000))
    val feed = new PowEscalationFeed(
      new EscalationWatch(new EscalationTrigger(64), () => 0L, escalated += _, (_, _) => ()),
      hash => if broken then throw new IllegalStateException("the weights cannot be read") else weights.get(hash),
      () => if broken then throw new IllegalStateException("the head cannot be read") else Some(head)
    )
    private val consensus = new Consensus:
      override def evaluateBranch(
          block: NonEmptyList[Block]
      )(implicit blockExecutionScheduler: IORuntime, blockchainConfig: BlockchainConfig): IO[ConsensusResult] =
        IO.pure(result(block))
    private val validation = new BlockValidation(null, null, null):
      override def firstInvalidHeader(
          branch: List[Block]
      )(implicit blockchainConfig: BlockchainConfig): Option[(BlockHeader, String)] =
        validated += branch.map(_.number.value)
        None
    val adapter = new ConsensusAdapter(consensus, null, null, validation, IORuntime.global, None, Some(feed))
    def imported(blocks: NonEmptyList[Block]): Any = adapter.evaluateBranch(blocks).unsafeRunSync()

  private def data(block: Block): BlockData = BlockData(block, Nil, ChainWeight.totalDifficultyOnly(0))

  "The import of a branch" should "tell the feed of one this node's state could not take, less what of it became the head" taggedAs UnitTest in {
    val tooDeep = new ReorgTooDeep(ReorgRefusal.Deep, "200 blocks below the head")
    val rig = new Rig(blocks => ConsensusStateFault(tooDeep, List(data(blocks.head)), Nil))
    val blocks = branch(5, 3)
    // as an import leaves it: the block that became the head has its weight stored, and is the head
    rig.weights += blocks.head.header.hash -> ChainWeight.totalDifficultyOnly(1100)
    rig.head = ChainWeight.totalDifficultyOnly(1100)
    rig.imported(blocks) shouldBe a[BlockImportStateFault]
    // the first block became the head; the two above it are what was refused, and what was validated
    rig.validated.toList shouldBe List(List(BigInt(6), BigInt(7)))
    rig.escalated.toList shouldBe List(EscalationKind.Unreachable)
  }

  it should "tell the feed of a head that moved, by extension and by reorganisation" taggedAs UnitTest in {
    val corrupt = new ReorgTooDeep(ReorgRefusal.Corrupt, "a record is not what it should be")
    Seq[(String, NonEmptyList[Block] => ConsensusResult, Class[?])](
      ("extension", blocks => ExtendedCurrentBestBranch(blocks.toList.map(data)), classOf[BlockImportedToTop]),
      ("reorganisation", blocks => SelectedNewBestBranch(Nil, blocks.toList, Nil), classOf[ChainReorganised])
    ).foreach { case (how, moved, answer) =>
      withClue(s"$how: ") {
        var refuse = true
        val rig = new Rig(blocks => if refuse then ConsensusStateFault(corrupt, Nil, Nil) else moved(blocks))
        rig.imported(branch(5, 3))
        rig.feed.refusedWeight.isDefined shouldBe true
        refuse = false
        rig.head = ChainWeight.totalDifficultyOnly(1300)
        rig.imported(branch(5, 3)).getClass shouldBe answer
        rig.feed.refusedWeight shouldBe None
      }
    }
  }

  // forge: the feed is told inside the result of an import, and a head that has moved is not answered as an error
  it should "stand, whatever goes wrong in the feed" taggedAs UnitTest in {
    val corrupt = new ReorgTooDeep(ReorgRefusal.Corrupt, "a record is not what it should be")
    val refused = new Rig(_ => ConsensusStateFault(corrupt, Nil, Nil))
    refused.weights.clear()
    refused.broken = true
    refused.imported(branch(5, 3)) shouldBe a[BlockImportStateFault]
    val moved = new Rig(blocks => ExtendedCurrentBestBranch(blocks.toList.map(data)))
    moved.broken = true
    moved.imported(branch(5, 3)) shouldBe a[BlockImportedToTop]
  }
