package com.chipprbots.ethereum.db.storage.pathstate

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.mpt.NibblePath
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, T037: a view of the anchor's state stays right while the disk moves under it (data-model 6.3.1).
  *
  * There is no snapshot under a view. A view remembers the epoch it was made at; a writer logs what its batch is about
  * to overwrite before it writes; and a read that finds the epoch changed asks the log what the position held. The
  * cases below drive the writer's three steps (publish, write, settle) by hand, in every order a reader can meet them,
  * on one thread: nothing here depends on timing except the one case that says so.
  */
class StateViewSpec extends AnyFlatSpec with Matchers:
  import PathStateTestKit.*

  private val someOwner = ByteString(Array.fill[Byte](32)(0x3c))
  private val p = PositionKey.at(None, NibblePath(1, 2))
  private val q = PositionKey.at(Some(someOwner), NibblePath(3))
  private val untouched = PositionKey.at(None, NibblePath(9))

  private def bytes(tag: Int): Array[Byte] = Array.fill[Byte](40)(tag.toByte)

  private val first = block(1, ByteString(Array.fill[Byte](32)(1)))
  private val second = block(2, ByteString(Array.fill[Byte](32)(2)))

  /** The rows, with a step that runs once, between a read of a row and whatever the reader does next. */
  final private class Rows(dataSource: DataSource) extends PathNodeStorage(dataSource):
    var afterNextRead: () => Unit = () => ()
    override def read(position: PositionKey): Option[Array[Byte]] =
      val found = super.read(position)
      val step = afterNextRead
      afterNextRead = () => ()
      step()
      found

    def put(position: PositionKey, encoded: Array[Byte]): Unit =
      position.owner.fold(writeAccountNode(position.path.toNibbles, encoded))(
        writeStorageNode(_, position.path.toNibbles, encoded)
      )

    def delete(position: PositionKey): Unit =
      position.owner.fold(deleteAccountNode(position.path.toNibbles))(deleteStorageNode(_, position.path.toNibbles))

  final private class Fixture(dataSource: DataSource, maxMoves: Int = 3):
    val rows = new Rows(dataSource)
    rows.put(p, bytes(1))
    rows.put(q, bytes(2))
    rows.put(untouched, bytes(9))
    val log = new StateMoveLog(maxMoves, Some(first))

    def viewNow(): StateView =
      val (epoch, held) = log.atRest.get
      new StateView(held.get, epoch, log, rows)

    /** The first step of a move: what its batch is about to overwrite goes into the log, and the epoch turns odd. */
    def publish(to: BlockRef, writes: (PositionKey, Option[Array[Byte]])*): Unit =
      log.publish(StateMove(to, writes.map((position, _) => position -> current(position)).toMap))

    def current(position: PositionKey): Option[ByteString] =
      rows.dataSource
        .getOptimized(
          if position.owner.isEmpty then com.chipprbots.ethereum.db.storage.Namespaces.StateTriePathNamespace
          else com.chipprbots.ethereum.db.storage.Namespaces.StorageTriePathNamespace,
          position.rowKey
        )
        .map(ByteString(_))

    /** The second step: the batch. */
    def write(writes: (PositionKey, Option[Array[Byte]])*): Unit =
      writes.foreach((position, next) => next.fold(rows.delete(position))(rows.put(position, _)))

    /** A whole move: published, written, settled. */
    def move(to: BlockRef, writes: (PositionKey, Option[Array[Byte]])*): Unit =
      publish(to, writes*)
      write(writes*)
      log.settled()

  private def read(view: StateView, position: PositionKey): Option[ByteString] = view.read(position).map(ByteString(_))

  dataSources.foreach { case (name, onDataSource) =>
    s"A view of the anchor on $name" should "read the row at a position, and nothing where there is no row" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          val f = new Fixture(dataSource)
          val view = f.viewNow()
          read(view, p) shouldBe Some(ByteString(bytes(1)))
          read(view, q) shouldBe Some(ByteString(bytes(2)))
          view.read(PositionKey.at(None, NibblePath(7, 7))) shouldBe None
          view.read(PositionKey.at(Some(someOwner), NibblePath(1, 2))) shouldBe None
          view.isAnchorViewNow shouldBe true
        }
      }

    it should "keep answering with what a position held when a move overwrites it, at every step of the move" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          val f = new Fixture(dataSource)
          val view = f.viewNow()

          f.publish(second, p -> Some(bytes(11)), q -> None)
          // published, not yet written: the disk still holds the old rows, and the epoch is odd
          read(view, p) shouldBe Some(ByteString(bytes(1)))
          read(view, q) shouldBe Some(ByteString(bytes(2)))
          view.isAnchorViewNow shouldBe false

          f.write(p -> Some(bytes(11)), q -> None)
          // written, not yet settled: the disk holds the new rows, and the move in flight says what was there
          read(view, p) shouldBe Some(ByteString(bytes(1)))
          read(view, q) shouldBe Some(ByteString(bytes(2)))

          f.log.settled()
          read(view, p) shouldBe Some(ByteString(bytes(1)))
          read(view, q) shouldBe Some(ByteString(bytes(2)))
          view.isAnchorViewNow shouldBe false

          // and a view made now sees the new state
          val later = f.viewNow()
          later.block shouldBe second
          read(later, p) shouldBe Some(ByteString(bytes(11)))
          later.read(q) shouldBe None
          later.isAnchorViewNow shouldBe true
        }
      }

    it should "read nothing at a position a later move created" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val created = PositionKey.at(None, NibblePath(5, 5, 5))
        val view = f.viewNow()
        f.move(second, created -> Some(bytes(50)))
        view.read(created) shouldBe None
        read(f.viewNow(), created) shouldBe Some(ByteString(bytes(50)))
      }
    }

    it should "read the disk at a position no move has written, also while a batch is in flight" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val view = f.viewNow()
        f.publish(second, p -> Some(bytes(11)))
        read(view, untouched) shouldBe Some(ByteString(bytes(9)))
        f.write(p -> Some(bytes(11)))
        read(view, untouched) shouldBe Some(ByteString(bytes(9)))
        f.log.settled()
        read(view, untouched) shouldBe Some(ByteString(bytes(9)))
      }
    }

    it should "answer from the earliest move that wrote a position, when several did" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource)
        val view = f.viewNow()
        f.move(second, p -> Some(bytes(11)))
        val between = f.viewNow()
        f.move(block(3, second.stateRoot), q -> Some(bytes(22)))
        f.move(block(4, second.stateRoot), p -> Some(bytes(111)))

        read(view, p) shouldBe Some(ByteString(bytes(1)))
        read(view, q) shouldBe Some(ByteString(bytes(2)))
        read(between, p) shouldBe Some(ByteString(bytes(11)))
        read(between, q) shouldBe Some(ByteString(bytes(2)))
      }
    }

    it should "be right when the batch lands between its read of the row and its read of the epoch" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          // The reader has the old row in hand and then finds the epoch changed: the move's prior is that same row.
          val whole = new Fixture(dataSource)
          val view = whole.viewNow()
          whole.rows.afterNextRead = () => whole.move(second, p -> Some(bytes(11)))
          read(view, p) shouldBe Some(ByteString(bytes(1)))
          whole.log.epoch shouldBe 2

          // The same with the batch still in flight when the reader looks at the epoch.
          val inFlight = new Fixture(EphemDataSource())
          val earlier = inFlight.viewNow()
          inFlight.rows.afterNextRead = () =>
            inFlight.publish(second, q -> None)
            inFlight.write(q -> None)
          read(earlier, q) shouldBe Some(ByteString(bytes(2)))
          inFlight.log.epoch shouldBe 1

          // The batch was written before the read and settles after it: the reader has the NEW row in hand.
          val settling = new Fixture(EphemDataSource())
          val before = settling.viewNow()
          settling.publish(second, p -> Some(bytes(11)))
          settling.write(p -> Some(bytes(11)))
          settling.rows.afterNextRead = () => settling.log.settled()
          read(before, p) shouldBe Some(ByteString(bytes(1)))
        }
      }

    it should "stop being the view of what is on disk after a batch that failed, and still read the same rows" taggedAs
      UnitTest in {
        onDataSource { dataSource =>
          val f = new Fixture(dataSource)
          val view = f.viewNow()
          an[IllegalStateException] should be thrownBy f.log.inFlight(
            StateMove(second, Map(p -> Some(ByteString(bytes(1)))))
          ) {
            throw new IllegalStateException("the batch failed")
          }
          // The disk is as it was, and so is the anchor. But a batch began since the view was made, so a mismatch
          // this view met now would be called stale and retried, never corrupt.
          f.log.anchor shouldBe Some(first)
          view.isAnchorViewNow shouldBe false
          read(view, p) shouldBe Some(ByteString(bytes(1)))
          read(view, untouched) shouldBe Some(ByteString(bytes(9)))
          f.viewNow().isAnchorViewNow shouldBe true
        }
      }

    it should "throw stale once the log of moves no longer reaches back to it, and not before" taggedAs UnitTest in {
      onDataSource { dataSource =>
        val f = new Fixture(dataSource, maxMoves = 3)
        val view = f.viewNow()
        (2 to 4).foreach(n => f.move(block(n, second.stateRoot), p -> Some(bytes(10 + n))))
        // three moves later the log still holds all three
        read(view, p) shouldBe Some(ByteString(bytes(1)))
        read(view, untouched) shouldBe Some(ByteString(bytes(9)))

        f.move(block(5, second.stateRoot), q -> Some(bytes(55)))
        val stale = the[StaleStateViewException] thrownBy view.read(p)
        stale.block shouldBe first
        stale.createdAtEpoch shouldBe 0
        stale.epochNow shouldBe 8
        // also at a position no move ever wrote: the log can no longer say so
        a[StaleStateViewException] should be thrownBy view.read(untouched)
        // a view made now is fine
        read(f.viewNow(), p) shouldBe Some(ByteString(bytes(14)))
      }
    }
  }

  "The log of state moves" should "log a move under the odd epoch it sets, so that a reader who samples that epoch finds it" taggedAs
    UnitTest in {
      val log = new StateMoveLog(maxMoves = 4, Some(first))
      log.epoch shouldBe 0
      log.movesBetween(0, 0) shouldBe Some(Nil)

      val move = StateMove(second, Map(p -> None))
      log.publish(move)
      log.epoch shouldBe 1
      log.movesBetween(0, 1) shouldBe Some(Seq(move))
      log.anchor shouldBe Some(first)

      log.settled()
      log.epoch shouldBe 2
      log.anchor shouldBe Some(second)
      log.movesBetween(0, 2) shouldBe Some(Seq(move))
      log.movesBetween(2, 2) shouldBe Some(Nil)

      val next = StateMove(block(3, second.stateRoot), Map(q -> None))
      log.publish(next)
      log.movesBetween(0, 3) shouldBe Some(Seq(move, next))
      log.movesBetween(2, 3) shouldBe Some(Seq(next))
    }

  it should "keep no more bytes of prior values than it may, letting the oldest moves go: the views older than them are stale" taggedAs
    UnitTest in {
      val node = ByteString(Array.fill[Byte](100)(7))
      // room for two moves' prior values and a little more
      val bytesOfOne = p.rowKey.length.toLong + node.length
      val log = new StateMoveLog(maxMoves = 8, Some(first), maxBytes = 2 * bytesOfOne + 10)
      val moves = (1 to 3).map(n => StateMove(block(n + 1, first.stateRoot), Map(p -> Some(node))))
      moves.foreach(move => log.inFlight(move)(()))
      // the first went to make room for the third; a view made before it can no longer be given back what it read
      log.movesBetween(0, log.epoch) shouldBe None
      log.movesBetween(2, log.epoch) shouldBe Some(moves.drop(1))
      // a move that is by itself more than all may hold is kept while it is the newest: a reader of it must find it
      val large = StateMove(block(9, first.stateRoot), Map(q -> Some(ByteString(Array.fill[Byte](1000)(1)))))
      log.inFlight(large)(())
      log.movesBetween(log.epoch - 2, log.epoch) shouldBe Some(Seq(large))
      log.movesBetween(4, log.epoch) shouldBe None
    }

  it should "empty itself for a barrier, so that every view made before it is stale; and keep the anchor when the barrier's write failed" taggedAs
    UnitTest in {
      val log = new StateMoveLog(maxMoves = 4, Some(first))
      log.inFlight(StateMove(second, Map(p -> None)))(())
      log.movesBetween(0, log.epoch) should not be empty
      val third = block(3, second.stateRoot)
      log.inFlightBarrier(third)(())
      log.anchor shouldBe Some(third)
      log.epoch shouldBe 4
      log.movesBetween(0, 4) shouldBe None
      log.movesBetween(2, 4) shouldBe None
      log.movesBetween(4, 4) shouldBe Some(Nil)
      // after it, moves are logged as ever
      val next = StateMove(block(4, second.stateRoot), Map(q -> None))
      log.inFlight(next)(())
      log.movesBetween(4, log.epoch) shouldBe Some(Seq(next))
      // a barrier whose write fails: the anchor stays, the epoch moves on, and what was older is stale all the same
      an[IllegalStateException] should be thrownBy log.inFlightBarrier(block(5, second.stateRoot))(
        throw new IllegalStateException("the batch failed")
      )
      log.anchor shouldBe Some(block(4, second.stateRoot))
      log.epoch shouldBe 8
      log.movesBetween(4, 8) shouldBe None
    }

  it should "keep the anchor when a batch failed, and still count the epoch" taggedAs UnitTest in {
    val log = new StateMoveLog(maxMoves = 4, Some(first))
    log.publish(StateMove(second, Map(p -> None)))
    log.settled(committed = false)
    log.anchor shouldBe Some(first)
    log.epoch shouldBe 2
  }

  it should "settle a batch that throws as not committed, and leave nothing in flight" taggedAs UnitTest in {
    val log = new StateMoveLog(maxMoves = 4, Some(first))
    val boom = new IllegalStateException("the batch failed")
    (the[IllegalStateException] thrownBy log.inFlight(StateMove(second, Map(p -> None)))(throw boom)) shouldBe boom
    log.epoch shouldBe 2
    log.anchor shouldBe Some(first)
    log.atRest shouldBe Some((2L, Some(first)))

    // and one that returns is committed
    log.inFlight(StateMove(second, Map(p -> None))) {
      log.epoch shouldBe 3
      log.atRest shouldBe None
      "written"
    } shouldBe "written"
    log.epoch shouldBe 4
    log.anchor shouldBe Some(second)
  }

  it should "refuse a second move while one is being written, and a settle with none" taggedAs UnitTest in {
    val log = new StateMoveLog(maxMoves = 4, Some(first))
    an[IllegalArgumentException] should be thrownBy log.settled()
    log.publish(StateMove(second, Map.empty))
    an[IllegalArgumentException] should be thrownBy log.publish(StateMove(second, Map.empty))
  }

  it should "say the disk is not at rest while a batch is being written" taggedAs UnitTest in {
    val log = new StateMoveLog(maxMoves = 4, Some(first))
    log.atRest shouldBe Some((0L, Some(first)))
    log.publish(StateMove(second, Map.empty))
    log.atRest shouldBe None
    log.settled()
    log.atRest shouldBe Some((2L, Some(second)))
  }

  it should "make a reader that wants the disk at rest wait for the batch being written" taggedAs UnitTest in {
    val log = new StateMoveLog(maxMoves = 4, Some(first))
    log.publish(StateMove(second, Map.empty))

    val got = new AtomicReference[Option[(Long, Option[BlockRef])]](None)
    val waiter = new Thread(() => got.set(Some(log.awaitRest())))
    waiter.start()
    // WAITING is the state of a thread inside the monitor's wait, which is the only wait this thread can be in.
    val deadline = System.nanoTime() + 20_000_000_000L
    while waiter.getState != Thread.State.WAITING && System.nanoTime() < deadline do Thread.onSpinWait()
    waiter.getState shouldBe Thread.State.WAITING
    got.get shouldBe None

    log.settled()
    waiter.join(20_000)
    got.get shouldBe Some((2L, Some(second)))
  }

  "The state anchor" should "be the string v1:<block hash>:<number>:<state root>" taggedAs UnitTest in {
    val anchor = BlockRef(
      ByteString(Array.fill[Byte](32)(0xab.toByte)),
      BigInt(21_000_000),
      ByteString(Array.fill[Byte](32)(0x0c))
    )
    val encoded = StateAnchor.encode(anchor)
    encoded shouldBe s"v1:${"ab" * 32}:21000000:${"0c" * 32}"
    StateAnchor.decode(encoded) shouldBe anchor
    StateAnchor.Key shouldBe "state.anchor"
  }

  it should "refuse a value it cannot read, and never take it for no anchor" taggedAs UnitTest in {
    val hash = "ab" * 32
    Seq(
      "",
      s"v2:$hash:5:$hash",
      s"v1:$hash:5",
      s"v1:$hash:5:$hash:extra",
      s"v1:${"ab" * 31}:5:$hash",
      s"v1:$hash:five:$hash",
      s"v1:$hash::$hash",
      s"v1:$hash:-5:$hash",
      s"v1:${"zz" * 32}:5:$hash"
    ).foreach { value =>
      withClue(s"'$value': ")(an[IllegalStateException] should be thrownBy StateAnchor.decode(value))
    }
  }

  "The finalized block's row" should "be the string v1:<block hash>:<number>, and refuse what it cannot read" taggedAs
    UnitTest in {
      val block = FinalizedBlock(BigInt(1234), ByteString(Array.tabulate[Byte](32)(_.toByte)))
      val encoded = FinalizedBlock.encode(block)
      encoded shouldBe s"v1:${(0 until 32).map(i => f"$i%02x").mkString}:1234"
      FinalizedBlock.decode(encoded) shouldBe block
      val hash = "ab" * 32
      Seq(
        "",
        "v1",
        s"v2:$hash:5",
        s"v1:$hash",
        s"v1:$hash:",
        s"v1:$hash:-5",
        s"v1:${"ab" * 31}:5",
        s"v1:${"zz" * 32}:5"
      )
        .foreach { value =>
          withClue(s"'$value': ")(an[IllegalStateException] should be thrownBy FinalizedBlock.decode(value))
        }
    }
