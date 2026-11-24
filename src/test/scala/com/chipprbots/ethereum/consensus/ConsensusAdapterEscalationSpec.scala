package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.EscalationTrigger
import com.chipprbots.ethereum.blockchain.sync.EscalationWatch
import com.chipprbots.ethereum.blockchain.sync.PowEscalationFeed
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailedDueToMissingNode
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
import com.chipprbots.ethereum.ledger.BlockQueue
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

  private class Rig(
      result: NonEmptyList[Block] => ConsensusResult,
      reader: com.chipprbots.ethereum.domain.BlockchainReader = null,
      queue: com.chipprbots.ethereum.ledger.BlockQueue = null
  ):
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
        // as the import runs it: what is thrown is raised in the IO
        IO.delay(result(block))
    private val validation = new BlockValidation(null, null, null):
      override def firstInvalidHeaderNotJudged(
          branch: List[Block]
      )(implicit blockchainConfig: BlockchainConfig): Option[(BlockHeader, String)] =
        validated += branch.map(_.number.value)
        None
      override def firstInvalidHeaderOfNotJudged(
          headers: List[BlockHeader]
      )(implicit blockchainConfig: BlockchainConfig): Option[(BlockHeader, String)] =
        validated += headers.map(_.number.value)
        None
    // what the node's stop was asked for: a write of the store refused (B-440)
    val stopped: mutable.ArrayBuffer[Throwable] = mutable.ArrayBuffer.empty
    val adapter =
      new ConsensusAdapter(consensus, reader, queue, validation, IORuntime.global, None, Some(feed), stopped += _)
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
      (
        "reorganisation",
        blocks => SelectedNewBestBranch(Nil, blocks.toList, Nil, blocks.size),
        classOf[ChainReorganised]
      )
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

  // B-461: a move back that failed left the head on side blocks the branch stands on. No block of the batch is the head,
  // and none is told as imported; the head is told to the feed as the head it is
  it should "tell the feed of a head left on the side blocks a branch stands on, with none of the branch imported" taggedAs UnitTest in {
    val corrupt = new ReorgTooDeep(ReorgRefusal.Corrupt, "a record is not what it should be")
    val sideBlock = Block(
      Fixtures.Blocks.ValidBlock.header
        .copy(parentHash = forkHash, number = BlockNumber(4), difficulty = Difficulty(1)),
      BlockBody.empty
    )
    var refuse = true
    val rig = new Rig(_ =>
      if refuse then ConsensusStateFault(corrupt, Nil, Nil)
      else
        new Consensus.HeadLeftOnSideBlocks(
          new com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable("the move back failed"),
          Nil,
          List(BlockData(sideBlock, Nil, ChainWeight.totalDifficultyOnly(1400)))
        )
    )
    rig.imported(branch(5, 3))
    rig.feed.refusedWeight.isDefined shouldBe true
    refuse = false
    // the head left there is no lighter than the branch refused: a head that moved ends the run
    rig.head = ChainWeight.totalDifficultyOnly(1400)
    rig.imported(branch(5, 3)) match
      case BlockImportStateFault(_, imported) => imported shouldBe Nil
      case other                              => fail(s"not a fault of this node: $other")
    rig.feed.refusedWeight shouldBe None
  }

  // B-337: a prefix that became the head before a missing node (a reorganisation's, or an extension's) is the head:
  // whoever follows it is told, as for any import that moved it, and the missing node is fetched as ever
  it should "tell the feed of the head a prefix became before a missing node, on the hash layout" taggedAs UnitTest in {
    val corrupt = new ReorgTooDeep(ReorgRefusal.Corrupt, "a record is not what it should be")
    val missing =
      new com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException(ByteString(Array.fill[Byte](32)(9)))
    val hashLayout = new com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup {}.blockchainReader
    var refuse = true
    val rig = new Rig(
      blocks =>
        if refuse then ConsensusStateFault(corrupt, Nil, Nil)
        else Consensus.ConsensusErrorDueToMissingNode(Nil, missing, blocks.toList.take(2).map(data)),
      hashLayout
    )
    rig.imported(branch(5, 3))
    rig.feed.refusedWeight.isDefined shouldBe true
    refuse = false
    rig.head = ChainWeight.totalDifficultyOnly(1300)
    rig.imported(branch(5, 3)) match
      case BlockImportFailedDueToMissingNode(reason, imported) =>
        (reason should be).theSameInstanceAs(missing)
        imported.map(_.block) shouldBe branch(5, 3).toList.take(2)
      case other => fail(s"not the missing node, with the prefix: $other")
    rig.feed.refusedWeight shouldBe None
  }

  // spec 018 C5a part 10 (forge): on the unified layout a node missing from the held state is no hole to fetch, which
  // would write the hash-keyed store; it is a fault of this node, told to the feed as one, and counted
  it should "tell a node missing from the held state as a fault of this node, on the unified layout" taggedAs UnitTest in {
    val onPositions = new com.chipprbots.ethereum.db.storage.pathstate.CanonicalChainOnPositions()
    val missing =
      new com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException(ByteString(Array.fill[Byte](32)(9)))
    val rig = new Rig(
      blocks => Consensus.ConsensusErrorDueToMissingNode(Nil, missing, List(data(blocks.head))),
      onPositions.reader
    )
    val blocks = branch(5, 3)
    rig.weights += blocks.head.header.hash -> ChainWeight.totalDifficultyOnly(1100)
    rig.head = ChainWeight.totalDifficultyOnly(1100)

    rig.imported(blocks) match
      case BlockImportStateFault(fault: com.chipprbots.ethereum.db.storage.pathstate.HeldStateIncomplete, imported) =>
        (fault.missing should be).theSameInstanceAs(missing)
        imported.map(_.block) shouldBe List(blocks.head)
      case other => fail(s"not a fault of this node's state: $other")
    withClue("the feed is told of the two blocks above the head, as a branch that was not judged: ") {
      rig.validated.toList shouldBe List(List(BigInt(6), BigInt(7)))
    }
  }

  // B-440: what is thrown out of an import's evaluation, read from its cause chain. `ConsensusImpl` throws a store's
  // failure once the head is back (#2001 pins it); thrown on, the importer dropped the batch with an error line.
  private object Thrown:
    import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.RocksDbDataSourceClosedException
    import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.RocksDbDataSourceException
    import org.rocksdb.RocksDBException
    import org.rocksdb.Status

    def rocks(code: Status.Code, sub: Status.SubCode = Status.SubCode.None): RocksDBException =
      new RocksDBException(s"injected: $code", new Status(code, sub, s"injected: $code"))
    def wrapped(cause: Throwable): Throwable = RocksDbDataSourceException("DataSource not updated", cause)

    val writeRefused: Seq[Throwable] = Seq(
      new java.io.IOException("injected: the disk refused the commit"),
      wrapped(new RocksDBException("No space left")),
      wrapped(rocks(Status.Code.IOError, Status.SubCode.NoSpace)),
      new java.util.concurrent.CompletionException(rocks(Status.Code.IOError)),
      new java.io.UncheckedIOException(new java.io.IOException("injected: the fsync failed"))
    )
    val busy: Seq[Throwable] =
      Seq(Status.Code.Busy, Status.Code.TryAgain, Status.Code.TimedOut, Status.Code.Aborted, Status.Code.Incomplete)
        .map(code => new java.util.concurrent.ExecutionException(wrapped(rocks(code))))
    val corrupt: Seq[Throwable] = Seq(wrapped(rocks(Status.Code.Corruption)), rocks(Status.Code.Corruption))
    val notOfTheStore: Seq[Throwable] = Seq(
      new IllegalStateException("a defect"),
      // RocksDbDataSourceException wraps any NonFatal: a defect under it is a defect
      wrapped(new NullPointerException("a defect under the store's wrapper")),
      wrapped(rocks(Status.Code.InvalidArgument)),
      // the node is stopping: nothing to try again, nothing to escalate
      RocksDbDataSourceClosedException("closed"),
      wrapped(RocksDbDataSourceClosedException("closed")),
      wrapped(rocks(Status.Code.ShutdownInProgress))
    )

  private class ThrowingRig(failure: Throwable):
    val host: EphemBlockchainTestSetup = new com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup {}
    val queue: BlockQueue = com.chipprbots.ethereum.ledger.BlockQueue(
      host.blockchainReader,
      com.chipprbots.ethereum.utils.Config.SyncConfig(Config.config)
    )
    val rig = new Rig(_ => throw failure, host.blockchainReader, queue)
    val blocks: NonEmptyList[Block] = branch(5, 3)
    def answered(): BlockImportStateFault = rig.imported(blocks) match
      case fault: BlockImportStateFault =>
        fault.imported shouldBe Nil
        fault
      case other => fail(s"not a fault of this node's state: $other")
    def queuedAgain(): Unit = blocks.toList.foreach(block => queue.isQueued(block.hash) shouldBe true)

  // the user's decision of 2026-11-24, as go-ethereum's log.Crit: a write refused stops the node, loudly; RocksDB takes no
  // write after it, so nothing is tried again, and it says nothing of this node's state, so nothing is escalated
  it should "stop the node when a write of its store is refused during the import, and count nothing" taggedAs UnitTest in {
    Thrown.writeRefused.foreach { failure =>
      withClue(s"$failure: ") {
        val on = new ThrowingRig(failure)
        on.answered().fault.getMessage should include("the node stops")
        on.rig.stopped.toList.map(_ eq failure) shouldBe List(true)
        on.rig.validated shouldBe empty
        on.rig.feed.refusedWeight shouldBe None
        on.queuedAgain()
      }
    }
  }

  // a store busy for now: the same write may go through at the next try. Queued again, no peer blamed, not counted
  it should "queue the batch again, uncounted, when the store was busy" taggedAs UnitTest in {
    Thrown.busy.foreach { failure =>
      withClue(s"$failure: ") {
        val on = new ThrowingRig(failure)
        on.answered().fault match
          case busy: com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable => busy.askAgain shouldBe true
          case other => fail(s"not a store that was busy: $other")
        on.rig.stopped shouldBe empty
        on.rig.validated shouldBe empty
        on.rig.feed.refusedWeight shouldBe None
        on.queuedAgain()
      }
    }
  }

  // a store RocksDB reads as corrupt says something of this node's state: counted, as any fault of it
  it should "count a store found corrupt during the import as a fault of this node's state" taggedAs UnitTest in {
    Thrown.corrupt.foreach { failure =>
      withClue(s"$failure: ") {
        val on = new ThrowingRig(failure)
        on.answered().fault.getMessage should include("corrupt")
        on.rig.stopped shouldBe empty
        on.rig.validated.toList shouldBe List(List(BigInt(5), BigInt(6), BigInt(7)))
        on.rig.feed.refusedWeight shouldBe Some(ChainWeight.totalDifficultyOnly(1300))
        on.queuedAgain()
      }
    }
  }

  // a fault of the state thrown rather than answered is answered as it is, never wrapped: its type is what the feed
  // reads (B-462)
  it should "answer a fault of this node's state thrown out of the import as it was thrown" taggedAs UnitTest in {
    val thrown = new ReorgTooDeep(ReorgRefusal.Deep, "injected: 200 blocks below the head")
    val on = new ThrowingRig(thrown)
    (on.answered().fault should be).theSameInstanceAs(thrown)
    on.rig.stopped shouldBe empty
    on.rig.escalated.toList shouldBe List(EscalationKind.Unreachable)
    on.queuedAgain()
  }

  // anything else is no failure of the store: a defect, or the node stopping. It is thrown on and fails loudly as
  // before, and nothing is stopped, queued or counted for it
  it should "leave anything else thrown out of the import thrown" taggedAs UnitTest in {
    Thrown.notOfTheStore.foreach { failure =>
      withClue(s"$failure: ") {
        val on = new ThrowingRig(failure)
        (the[Throwable] thrownBy on.rig.imported(on.blocks) should be).theSameInstanceAs(failure)
        on.rig.stopped shouldBe empty
        on.rig.validated shouldBe empty
        on.rig.feed.refusedWeight shouldBe None
      }
    }
  }

  // vault: contract code is fetched by its hash into the code store, which both layouts share
  it should "leave missing contract code to be fetched, on the unified layout" taggedAs UnitTest in {
    val onPositions = new com.chipprbots.ethereum.db.storage.pathstate.CanonicalChainOnPositions()
    val missing = new com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingCodeException(
      ByteString(Array.fill[Byte](32)(9)),
      ByteString(Array.fill[Byte](20)(1))
    )
    val rig = new Rig(_ => Consensus.ConsensusErrorDueToMissingNode(Nil, missing, Nil), onPositions.reader)
    rig.imported(branch(5, 3)) match
      case com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailedDueToMissingNode(reason, _) =>
        (reason should be).theSameInstanceAs(missing)
      case other => fail(s"not left to be fetched: $other")
    rig.escalated shouldBe empty
  }

  // spec 018 C5b: a hole that names its position (a view of the rows alone says it) is fetched by it and written there
  // by repairNodeAt, as on the hash layout; a hole of a layered view names none and stays a fault of this node
  it should "leave a node missing at a known position to be fetched by it, on the unified layout" taggedAs UnitTest in {
    val onPositions = new com.chipprbots.ethereum.db.storage.pathstate.CanonicalChainOnPositions()
    val hash = ByteString(Array.fill[Byte](32)(9))
    val located = List[com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException](
      new com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException(hash).withLocation(ByteString(0x00)),
      new com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingAccountNodeException(
        hash,
        ByteString(Array.fill[Byte](20)(1)),
        Some(ByteString(0x00))
      ),
      new com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingStorageNodeException(
        hash,
        ByteString(Array.fill[Byte](20)(1)),
        Some(ByteString(0x00))
      )
    )
    located.foreach { missing =>
      val rig = new Rig(_ => Consensus.ConsensusErrorDueToMissingNode(Nil, missing, Nil), onPositions.reader)
      rig.imported(branch(5, 3)) match
        case com.chipprbots.ethereum.blockchain.sync.regular.BlockImportFailedDueToMissingNode(reason, _) =>
          (reason should be).theSameInstanceAs(missing)
        case other => fail(s"not left to be fetched: $other")
      rig.escalated shouldBe empty
    }
  }

  // forge, B-291: a hole of a view with layers over the rows names no position, on purpose (`Unplaced`, which the trie's
  // traversal leaves unlocated), so it stays a fault of this node however it is rewrapped
  it should "tell a node missing from a view with layers as a fault of this node, whichever way it is rewrapped" taggedAs UnitTest in {
    import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.*
    val onPositions = new com.chipprbots.ethereum.db.storage.pathstate.CanonicalChainOnPositions()
    val hash = ByteString(Array.fill[Byte](32)(9))
    val owner = ByteString(Array.fill[Byte](20)(1))
    val unplaced = List[MissingNodeException](
      new MissingNodeException(hash) with Unplaced,
      new MissingAccountNodeException(hash, owner) with Unplaced,
      new MissingStorageNodeException(hash, owner) with Unplaced
    )
    unplaced.foreach { missing =>
      val rig = new Rig(_ => Consensus.ConsensusErrorDueToMissingNode(Nil, missing, Nil), onPositions.reader)
      rig.imported(branch(5, 3)) match
        case BlockImportStateFault(fault: com.chipprbots.ethereum.db.storage.pathstate.HeldStateIncomplete, _) =>
          (fault.missing should be).theSameInstanceAs(missing)
        case other => fail(s"not a fault of this node's state: $other")
    }
  }

  // forge, B-291: a hole that was fetched by its position and not filled is told to the feed once the importer's exhausts
  // reach their threshold, as a branch that was not judged and with the validation of an import
  it should "tell the feed of a hole no repair fills, as a branch that was not judged" taggedAs UnitTest in {
    val rig = new Rig(_ => throw new IllegalStateException("nothing is imported"))
    val missing = new com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException(
      ByteString(Array.fill[Byte](32)(9))
    ).withLocation(ByteString(0x00))
    rig.adapter.holeNotRepaired(branch(5, 3).toList, missing)
    rig.validated.toList shouldBe List(List(BigInt(5), BigInt(6), BigInt(7)))
    // and whatever goes wrong in the feed is logged, not thrown
    val broken = new Rig(_ => throw new IllegalStateException("nothing is imported"))
    broken.broken = true
    broken.adapter.holeNotRepaired(branch(5, 3).toList, missing)
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

  // B-300 (g): a branch regular sync refused before fetching it is told by its headers, validated as an import's are
  it should "tell the feed of a branch out of reach, by its headers, with the header validation of an import" taggedAs UnitTest in {
    val beyond = new ReorgTooDeep(ReorgRefusal.BeyondTheFetch, "600 blocks above the fork")
    val rig = new Rig(_ => throw new IllegalStateException("nothing is imported"))
    rig.adapter.branchOutOfReach(branch(5, 3).toList.map(_.header), beyond)
    rig.validated.toList shouldBe List(List(BigInt(5), BigInt(6), BigInt(7)))
    rig.escalated.toList shouldBe List(EscalationKind.Unreachable)
    // and whatever goes wrong in the feed is logged, not thrown
    val broken = new Rig(_ => throw new IllegalStateException("nothing is imported"))
    broken.broken = true
    broken.adapter.branchOutOfReach(branch(5, 3).toList.map(_.header), beyond)
    broken.escalated shouldBe empty
  }
