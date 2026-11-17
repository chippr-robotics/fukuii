package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.json4s.*

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.mpt.PositionTestKit.PositionMirror

/** A [[PositionMirror]] on one fixture's replay (spec 018 Track C, T026): the mirror sits in front of the storage of
  * the genesis load and of every block, a rejected block's changes are dropped, and after each unit that is kept the
  * mirror's rows are held to a walk of the state the unit left.
  *
  * `accessListBuilder` says whether the fixture's fork builds a block access list (Amsterdam). Only that builder reads
  * a storage trie after its account was removed, so on any other fork such a read is a finding.
  */
final class EestPositionMirror(accessListBuilder: Boolean) extends EestBlockchainReplay.StorageObserver:
  val mirror = new PositionMirror
  private val divergences = mutable.ArrayBuffer.empty[String]
  private var unit = "genesis"
  private var blocks = 0

  /** Units whose changes were kept (the genesis load and each accepted block), and blocks that were rejected. */
  var unitsKept = 0
  var unitsDropped = 0

  /** What the mirror found wrong, empty when the fixture's positions are right: reads and updates a position store
    * would have refused, then rows that differ from the state.
    */
  def findings: Seq[String] =
    val readsAfterRemoval =
      Option.when(!accessListBuilder && mirror.readsOfResetTries > 0)(
        s"${mirror.readsOfResetTries} reads of a storage trie after its account was removed, on a fork that builds " +
          "no access list"
      )
    mirror.violations ++ divergences ++ readsAfterRemoval

  def readsChecked: Long = mirror.readsOfStartRows + mirror.readsOfOwnWrites

  override def wrap(storage: MptStorage): MptStorage = mirror.over(storage)

  override def genesisStarting(): Unit = mirror.starting(insertsOnly = true)

  override def blockStarting(): Unit =
    unit = s"block[$blocks]"
    blocks += 1
    mirror.starting()

  override def ended(stateRoot: Option[ByteString], load: Array[Byte] => Array[Byte]): Unit =
    mirror.ended(accepted = stateRoot.isDefined)
    stateRoot match
      case None => unitsDropped += 1
      case Some(root) =>
        unitsKept += 1
        if divergences.size < 20 then divergences ++= mirror.divergencesFrom(root, load).map(d => s"after $unit: $d")

object EestPositionMirror:

  /** A mirror for one `blockchain_test` fixture, by its `network`. A transition into Amsterdam counts as Amsterdam. */
  def forFixture(t: JValue): EestPositionMirror =
    new EestPositionMirror(accessListBuilder = (t \ "network").values.toString.contains("Amsterdam"))
