package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256

/** What the specs of a head move's plan share: blocks that are only what a plan is made from. */
trait PlannedMoves:
  self: Matchers =>

  import HeadMovePlan.*

  def hashOf(name: String): ByteString = ByteString(kec256(name.getBytes("UTF-8")))

  /** Blocks that are only what a plan is made from: headers, the headers of records, and layers that hold nothing. */
  final class Model:
    type Record = Either[BlockDiffCodec.Rejected, BlockDiffCodec.Header]
    val records: mutable.Map[ByteString, Record] = mutable.Map.empty
    val layers: mutable.Map[BlockRef, ForwardLayer] = mutable.Map.empty
    val links: mutable.Map[ByteString, Link] = mutable.Map.empty
    val asked: mutable.Buffer[(String, ByteString)] = mutable.Buffer.empty

    val genesis: BlockRef = BlockRef(hashOf("genesis"), 0, hashOf("genesis-state"))
    links(genesis.hash) = Link(genesis, ByteString(new Array[Byte](32)))

    /** A block on `parent` that this node holds the header of, and nothing else. */
    def header(parent: BlockRef, name: String): BlockRef =
      val ref = BlockRef(hashOf(s"$name on ${parent.show}"), parent.number + 1, hashOf(s"state of $name"))
      links(ref.hash) = Link(ref, parent.hash)
      ref

    def recordOf(parent: BlockRef, ref: BlockRef, incomplete: Boolean = false): BlockDiffCodec.Header =
      BlockDiffCodec.Header(ref.hash, ref.number, parent.hash, parent.stateRoot, ref.stateRoot, incomplete)

    /** A block that was applied in place: it has a record. */
    def recorded(parent: BlockRef, name: String): BlockRef =
      val ref = header(parent, name)
      records(ref.hash) = Right(recordOf(parent, ref))
      ref

    def tombstoned(parent: BlockRef, name: String): BlockRef =
      val ref = header(parent, name)
      records(ref.hash) = Right(recordOf(parent, ref, incomplete = true))
      ref

    def layerOf(parent: BlockRef, ref: BlockRef): ForwardLayer =
      ForwardLayer.of(ref, parent, StagedChanges.Frozen(Map.empty, Nil))

    /** A block that was executed and is kept as a layer. */
    def layered(parent: BlockRef, name: String): BlockRef =
      val ref = header(parent, name)
      layers(ref) = layerOf(parent, ref)
      ref

    /** `count` blocks, each on the one before, the first on `from`: oldest first. */
    def chain(from: BlockRef, count: Int, name: String)(make: (BlockRef, String) => BlockRef): Vector[BlockRef] =
      (1 to count).foldLeft(Vector.empty[BlockRef]) { (done, n) =>
        done :+ make(done.lastOption.getOrElse(from), s"$name$n")
      }

    val sources: Sources = new Sources:
      def recordHeader(blockHash: ByteString): Option[Record] =
        asked += "record" -> blockHash
        records.get(blockHash)
      def layer(block: BlockRef): Option[ForwardLayer] =
        asked += "layer" -> block.hash
        layers.get(block)
      def link(blockHash: ByteString): Option[Link] =
        asked += "link" -> blockHash
        links.get(blockHash)

    val open: Limits = Limits(Int.MaxValue, 10000, prunedTo = Some(1), origin = Some(genesis), finalized = None)

    def plan(anchor: BlockRef, target: BlockRef, limits: Limits = open): Outcome =
      HeadMovePlan.of(anchor, 6L, target, sources, limits)

    def planned(anchor: BlockRef, target: BlockRef, limits: Limits = open): HeadMovePlan =
      plan(anchor, target, limits) match
        case Outcome.Planned(plan) =>
          plan.anchor shouldBe anchor
          plan.target shouldBe target
          plan.epoch shouldBe 6L
          // the reverse set leads from the anchor down to the fork, the forward set from the fork up to the target
          plan.reverse.map(_.block) shouldBe (anchor +: plan.reverse.map(_.parent)).dropRight(1)
          plan.reverse.lastOption.foreach(_.parent shouldBe plan.fork)
          plan.forward.map(_.parent) shouldBe (plan.fork +: plan.forward.map(_.block)).dropRight(1)
          plan.forward.lastOption.foreach(_.block shouldBe target)
          if plan.reverse.isEmpty then plan.fork shouldBe anchor
          if plan.forward.isEmpty then plan.fork shouldBe target
          plan.depth shouldBe plan.reverse.size
          plan
        case other => fail(s"not planned: $other")

    def refused(anchor: BlockRef, target: BlockRef, limits: Limits = open): ReorgRefusal =
      plan(anchor, target, limits) match
        case Outcome.Refused(reason, detail) =>
          detail should not be empty
          reason
        case other => fail(s"not refused: $other")

    def notHeld(anchor: BlockRef, target: BlockRef, limits: Limits = open): Option[BlockRef] =
      plan(anchor, target, limits) match
        case Outcome.NotHeld(detail, heldTo) =>
          detail should not be empty
          heldTo
        case other => fail(s"not answered as not held: $other")

  def bySource(plan: HeadMovePlan): Vector[String] = plan.forward.map(_.source match
    case Source.Record   => "record"
    case Source.Layer(_) => "layer"
  )
