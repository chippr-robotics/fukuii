package com.chipprbots.ethereum.consensus

import org.apache.pekko.util.ByteString

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.collection.mutable
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.EscalationTrigger
import com.chipprbots.ethereum.blockchain.sync.EscalationWatch
import com.chipprbots.ethereum.blockchain.sync.PowEscalationFeed
import com.chipprbots.ethereum.blockchain.sync.regular.BlockEnqueued
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

  /** `blocks` blocks from `from`, the first standing on `parent`; `mark` tells siblings at the same height apart. */
  private def branch(
      from: Int,
      blocks: Int,
      parent: BlockHash = forkHash,
      mark: Byte = 0
  ): NonEmptyList[Block] =
    NonEmptyList.fromListUnsafe(
      (0 until blocks)
        .foldLeft(List.empty[Block]) { (made, i) =>
          val header = Fixtures.Blocks.ValidBlock.header.copy(
            parentHash = made.headOption.fold(parent)(_.header.hash),
            number = BlockNumber(from + i),
            difficulty = Difficulty(100),
            extraData = if mark == 0 then Fixtures.Blocks.ValidBlock.header.extraData else ByteString(mark)
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
    // the adapter's clock, in nanoseconds: moved by hand
    var now: Long = 0L
    val adapter =
      new ConsensusAdapter(
        consensus,
        reader,
        queue,
        validation,
        IORuntime.global,
        None,
        Some(feed),
        stopped += _,
        () => now
      )
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

  // forge C1: a disk that refused a write may refuse the reads too. The stop is asked for before the store is read for
  // what the head stands on; a read that throws there leaves the answer with nothing imported, and the stop stands
  it should "stop the node even when the store cannot be read after the write it refused" taggedAs UnitTest in {
    val unreadable = new java.io.UncheckedIOException(new java.io.IOException("injected: the read failed as well"))
    val reader = new com.chipprbots.ethereum.domain.BlockchainReader(null, null, null, null, null, null, null, null):
      override def getBestBlockHeader: Option[BlockHeader] = throw unreadable
      override def getBlockHeaderByHash(hash: BlockHash): Option[BlockHeader] = throw unreadable
      override def getChainWeightByHash(hash: BlockHash): Option[ChainWeight] = throw unreadable
    val host = new EphemBlockchainTestSetup {}
    val queue = BlockQueue(host.blockchainReader, com.chipprbots.ethereum.utils.Config.SyncConfig(Config.config))
    val refused = Thrown.wrapped(Thrown.rocks(org.rocksdb.Status.Code.IOError, org.rocksdb.Status.SubCode.NoSpace))
    val rig = new Rig(_ => throw refused, reader, queue)
    val blocks = branch(5, 3)
    rig.imported(blocks) match
      case fault: BlockImportStateFault =>
        fault.imported shouldBe Nil
        fault.fault.getMessage should include("the node stops")
        fault.fault.getMessage should include("IOError/NoSpace")
      case other => fail(s"not a fault of this node's state: $other")
    rig.stopped.toList.map(_ eq refused) shouldBe List(true)
    rig.feed.refusedWeight shouldBe None
    blocks.toList.foreach(block => queue.isQueued(block.hash) shouldBe true)
  }

  // forge C2: the stop runs aside and the database's close waits for the import, so batches keep coming. Once the stop
  // is asked for none is evaluated (none can write): each is answered not judged, not counted, and the stop is asked once
  it should "evaluate no batch once the stop is asked for" taggedAs UnitTest in {
    val refused = new java.io.IOException("injected: the disk refused the commit")
    var evaluations = 0
    val on = new ThrowingRig(refused)
    val rig = new Rig(
      { _ =>
        evaluations += 1
        throw refused
      },
      on.host.blockchainReader,
      on.queue
    )
    rig.imported(on.blocks) shouldBe a[BlockImportStateFault]
    evaluations shouldBe 1
    val later = branch(8, 2)
    rig.imported(later) match
      case fault: BlockImportStateFault =>
        fault.fault shouldBe a[com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable]
        fault.fault.getMessage should include("no block is evaluated")
        fault.imported shouldBe Nil
      case other => fail(s"not a fault of this node's state: $other")
    evaluations shouldBe 1
    rig.stopped.toList.map(_ eq refused) shouldBe List(true)
    rig.validated shouldBe empty
    rig.feed.refusedWeight shouldBe None
    later.toList.foreach(block => on.queue.isQueued(block.hash) shouldBe true)
  }

  // forge W1: a store busy batch after batch takes no write. Every busy answer is counted, whichever block the batch
  // stands on, until a block becomes the head; once five have come over ten minutes from the first, the node stops as
  // for a store that failed
  private class BusyRig:
    val busy: Throwable = Thrown.busy.head
    private var answer: NonEmptyList[Block] => ConsensusResult = _ => throw busy
    private val on = new ThrowingRig(busy)
    val rig = new Rig(blocks => answer(blocks), on.host.blockchainReader, on.queue)
    // `blocks` stored, the last of them the head, as a sync before the batches left them
    def theHead(blocks: List[Block]): Unit =
      blocks.foreach(block =>
        on.host.blockchainWriter.save(block, Nil, ChainWeight.totalDifficultyOnly(block.number.value), true)
      )
    def busyAnswer(blocks: NonEmptyList[Block]): Unit = rig.imported(blocks) match
      case BlockImportStateFault(fault: com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable, _) =>
        fault.askAgain shouldBe true
      case other => fail(s"not a store that was busy: $other")
    def busyAnswers(blocks: NonEmptyList[Block], times: Int): Unit = (1 to times).foreach(_ => busyAnswer(blocks))
    // the batch is answered as `result` says, once
    def answering(blocks: NonEmptyList[Block])(result: NonEmptyList[Block] => ConsensusResult): Any =
      answer = result
      try rig.imported(blocks)
      finally answer = _ => throw busy
    // the clock moves on
    def later(by: FiniteDuration = ConsensusAdapter.BusyStopAfter): Unit = rig.now += by.toNanos
    // the next busy answer, ten minutes on, is the `count`th since the head moved, and stops the node
    def stopsOn(blocks: NonEmptyList[Block], count: Int = ConsensusAdapter.BusyBeforeStop): Unit =
      rig.stopped shouldBe empty
      later()
      rig.imported(blocks) match
        case BlockImportStateFault(fault, _) =>
          fault.getMessage should include(s"was busy $count times over")
          fault.getMessage should include("with no block made the head between")
          fault.getMessage should include("the node stops")
        case other => fail(s"not a fault of this node's state: $other")
      rig.stopped.toList.map(_ eq busy) shouldBe List(true)

  private val otherParent = BlockHash(ByteString(Array.fill[Byte](32)(3)))
  private val UnderStop = ConsensusAdapter.BusyBeforeStop - 1

  it should "stop the node when the store is busy too many times with no block made the head" taggedAs UnitTest in {
    val on = new BusyRig
    val blocks = branch(5, 3)
    // four over ten minutes, then a batch evaluated that moved the head: the count and its clock start again
    on.busyAnswers(blocks, UnderStop)
    on.later()
    on.answering(blocks) { moved =>
      on.theHead(moved.toList)
      ExtendedCurrentBestBranch(moved.toList.map(data))
    } shouldBe a[BlockImportedToTop]
    on.busyAnswers(blocks, UnderStop)
    on.rig.stopped shouldBe empty
    // the fifth stops the node
    on.stopsOn(blocks)
    // nothing of it is told to the escalation
    on.rig.feed.refusedWeight shouldBe None
  }

  // forge W1: a lighter side branch kept as it is reads the store and writes nothing. It says nothing of a store that
  // takes writes, and does not start the count again
  it should "count on through lighter branches kept without a write" taggedAs UnitTest in {
    val on = new BusyRig
    val blocks = branch(5, 3)
    val lighter = branch(5, 2, parent = otherParent)
    (1 to UnderStop).foreach { _ =>
      on.busyAnswer(blocks)
      on.answering(lighter)(_ => Consensus.KeptCurrentBestBranch) shouldBe BlockEnqueued
    }
    on.stopsOn(blocks)
  }

  // forge W1: batches that alternate between branches standing on different blocks are counted together: a store busy
  // on each takes no write, and a count kept per block would never reach the stop
  it should "count the busy answers of branches on different blocks together" taggedAs UnitTest in {
    val on = new BusyRig
    val one = branch(5, 3)
    val other = branch(9, 2, parent = otherParent)
    one.head.header.parentHash should not be other.head.header.parentHash
    (1 to UnderStop).foreach(i => on.busyAnswer(if i % 2 == 1 then one else other))
    on.stopsOn(other)
  }

  // forge W1: siblings at the same height, as any two branches, count together
  it should "count the busy answers of sibling blocks at the same height together" taggedAs UnitTest in {
    val on = new BusyRig
    val one = branch(5, 3)
    val sibling = branch(5, 3, mark = 1)
    one.head.hash should not be sibling.head.hash
    one.head.header.parentHash shouldBe sibling.head.header.parentHash
    (1 to UnderStop).foreach(i => on.busyAnswer(if i % 2 == 1 then one else sibling))
    on.stopsOn(one)
  }

  // forge W1: a prefix of a batch that became the head is a write the store took; the count starts again
  it should "start the count again when a prefix of a batch becomes the head" taggedAs UnitTest in {
    val on = new BusyRig
    val blocks = branch(5, 3)
    val corrupt = new ReorgTooDeep(ReorgRefusal.Corrupt, "a record is not what it should be")
    on.busyAnswers(blocks, UnderStop)
    on.later()
    on.answering(blocks) { batch =>
      on.theHead(List(batch.head))
      ConsensusStateFault(corrupt, List(data(batch.head)), batch.tail)
    } match
      case BlockImportStateFault(_, imported) => imported.map(_.block) shouldBe List(blocks.head)
      case other                              => fail(s"not a fault of this node's state: $other")
    on.busyAnswers(blocks, UnderStop)
    on.stopsOn(blocks)
  }

  // forge W1: a write stall, a compaction behind or a lock timed out clears in seconds. Busy answers that come fast, as
  // batches alternating between branches do (the importer spaces its tries out only on the same block), do not stop
  // the node however many they are, until ten minutes have passed since the first
  it should "not stop the node for a store busy many times within seconds" taggedAs UnitTest in {
    val on = new BusyRig
    val one = branch(5, 3)
    val other = branch(9, 2, parent = otherParent)
    (1 to 4 * ConsensusAdapter.BusyBeforeStop).foreach { i =>
      on.busyAnswer(if i % 2 == 1 then one else other)
      on.later(1.second)
    }
    // and up to the last moment before the ten minutes
    on.later(ConsensusAdapter.BusyStopAfter - (4 * ConsensusAdapter.BusyBeforeStop).seconds - 1.nano)
    on.busyAnswer(one)
    on.rig.stopped shouldBe empty
  }

  // forge W1: busy answers that go on past ten minutes from the first, with no block made the head, stop the node at
  // the next one
  it should "stop the node at the next busy answer once the store has been busy for ten minutes" taggedAs UnitTest in {
    val on = new BusyRig
    val one = branch(5, 3)
    val other = branch(9, 2, parent = otherParent)
    (1 to ConsensusAdapter.BusyBeforeStop).foreach(i => on.busyAnswer(if i % 2 == 1 then one else other))
    on.rig.stopped shouldBe empty
    on.stopsOn(other, count = ConsensusAdapter.BusyBeforeStop + 1)
  }

  // forge W1: both floors hold. A store busy once, then again after a long quiet with no block made the head, has said
  // little: it stops only once five busy answers have come
  it should "not stop the node for fewer than five busy answers, however long apart" taggedAs UnitTest in {
    val on = new BusyRig
    val blocks = branch(5, 3)
    on.busyAnswer(blocks)
    on.later()
    on.later()
    (2 to UnderStop).foreach { _ =>
      on.busyAnswer(blocks)
      on.rig.stopped shouldBe empty
    }
    on.stopsOn(blocks)
  }

  // forge W1 (round 5): the blocks of a busy batch the head's chain already held (a batch from below the head, as after
  // SNAP's pivot) are answered as imported and told as the head, which did not move. That is no write the store took,
  // and the count goes on
  it should "count on through busy batches whose blocks the head's chain already held" taggedAs UnitTest in {
    val on = new BusyRig
    val blocks = branch(5, 4)
    on.theHead(blocks.toList.take(3))
    (1 to UnderStop).foreach { _ =>
      on.rig.imported(blocks) match
        case BlockImportStateFault(fault: com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable, imported) =>
          fault.askAgain shouldBe true
          imported.map(_.block) shouldBe blocks.toList.take(3)
        case other => fail(s"not a store that was busy: $other")
    }
    on.stopsOn(blocks)
  }

  // forge W1 (round 6): the blocks of a busy batch that all sit below the head on its chain are answered as imported,
  // and the last of them is told as the head; the stored head did not move, and the count goes on
  it should "count on through busy batches whose blocks all sit below the head on its chain" taggedAs UnitTest in {
    val on = new BusyRig
    val chain = branch(5, 4)
    on.theHead(chain.toList)
    val below = NonEmptyList.fromListUnsafe(chain.toList.take(2))
    (1 to UnderStop).foreach { _ =>
      on.rig.imported(below) match
        case BlockImportStateFault(fault: com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable, imported) =>
          fault.askAgain shouldBe true
          imported.map(_.block) shouldBe below.toList
        case other => fail(s"not a store that was busy: $other")
    }
    on.stopsOn(below)
  }

  // forge W1: a store read as corrupt with no block made the head took no write; the count goes on
  it should "count on through another failure of the store that moved no head" taggedAs UnitTest in {
    val on = new BusyRig
    val blocks = branch(5, 3)
    on.busyAnswers(blocks, 2)
    on.answering(blocks)(_ => throw Thrown.corrupt.head) match
      case BlockImportStateFault(_, imported) => imported shouldBe Nil
      case other                              => fail(s"not a fault of this node's state: $other")
    on.busyAnswers(blocks, UnderStop - 2)
    on.stopsOn(blocks)
    // the corrupt store is counted, as ever; the busy answers are not
    on.rig.feed.refusedWeight.isDefined shouldBe true
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
