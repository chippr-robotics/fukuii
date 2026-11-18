package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.db.storage.pathstate.PositionRepairFixture

/** The audit's walk over a position store (spec 018 C5b): a reader of the rows by position, as the audit's own reads
  * them, and a loop that runs the walk to its end and writes nothing.
  */
final class PositionAuditHarness(val f: PositionRepairFixture):

  /** Rows by position, straight from the store; the head is the anchor's block. */
  val reader: AuditReader = new AuditReader:
    override def nodes(hashes: Seq[ByteString]): Seq[RawNodeRead] =
      throw new UnsupportedOperationException("a position store is read by position")
    override def nodesAt(requests: Seq[(AuditPosition, ByteString)]): Seq[RawNodeRead] =
      f.state.rawRowsAt(requests.map((at, hash) => (at.key, hash)))
    override def codes(hashes: Seq[ByteString]): Seq[Option[ByteString]] = hashes.map(_ => None)
    override def head(): Option[AuditHead] = headNow()

  def headNow(): Option[AuditHead] = f.state.anchor.map(a => AuditHead(a.number, a.stateRoot, 0L))

  final case class Totals(
      nodes: Long,
      moved: Long,
      holes: Vector[AuditHole],
      unfollowable: Vector[String]
  )

  /** The whole walk from the root of the account trie at `head`, batch by batch, holes collected and none repaired. */
  def walkAll(head: AuditHead = headNow().get, batch: Int = 64): Totals =
    var stack = Vector(AuditFrame(AuditPosition.AccountRoot, None))
    var totals = Totals(0, 0, Vector.empty, Vector.empty)
    val filter = new AuditCodeFilter(100)
    while stack.nonEmpty do
      val (frames, rest) = stack.splitAt(batch)
      val step = AuditWalk.walkBatch(frames, head, reader, filter)
      stack = step.pushes ++ rest
      totals = totals.copy(
        nodes = totals.nodes + step.nodes,
        moved = totals.moved + step.moved,
        holes = totals.holes ++ step.holes,
        unfollowable = totals.unfollowable ++ step.unfollowable
      )
    totals
