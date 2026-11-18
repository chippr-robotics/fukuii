package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.HexPrefix

/** A datadir of the unified layout holding the shared test state as the state of one anchor, for the specs of the
  * repairs and the audit by position (spec 018 C5b): the rows, the anchor, and a [[PathStateStorage]] over them.
  */
final class PositionRepairFixture(withAnchor: Boolean = true):
  import PathStateTestKit.*

  val source: EphemDataSource = EphemDataSource()
  val hashed = new HashedState
  val anchor: BlockRef = block(100, hashed.root)
  if withAnchor then seed(source, hashed, anchor)
  else writeState(new PathNodeStorage(source), hashed.positions())
  val rows = new PathNodeStorage(source)
  val appState = new AppStateStorage(source)
  val state = new PathStateStorage(rows, appState)

  /** Every stored row of the state at `stateRoot`, as a position and its node. */
  def rowsOf(stateRoot: ByteString = hashed.root): Seq[(PositionKey, Array[Byte])] =
    hashed.positions(stateRoot).toSeq.flatMap { case (owner, trieRows) =>
      trieRows.toSeq.map { case (hp, encoded) => PositionKey(owner, hp) -> encoded.toArray }
    }

  def hashOf(node: Array[Byte]): ByteString = ByteString(kec256(node))

  /** A stored row that is not a root, in the account trie or in the storage trie of `owner`. */
  def innerRow(owner: Option[ByteString]): (PositionKey, Array[Byte]) =
    rowsOf()
      .filter { case (position, _) =>
        position.owner == owner && position.hp != PositionKey.AccountRoot.hp &&
        HexPrefix.decode(position.hp.toArray)._1.nonEmpty
      }
      .sortBy(_._1.hp.length)
      .last

  def rowAt(position: PositionKey): Option[Array[Byte]] = rows.read(position)

  def put(position: PositionKey, bytes: Array[Byte]): Unit =
    source.update(rows.rowUpdates(Seq(position -> Some(ByteString(bytes)))))

  def remove(position: PositionKey): Unit =
    source.update(rows.rowUpdates(Seq(position -> None)))

  /** Bytes that are no trie node. */
  val garbage: Array[Byte] = Array[Byte](0xff.toByte, 1, 2, 3)

  /** Commits one more block on the anchor that changes the account `dave`, and gives the block. */
  def commitNext(): BlockRef =
    val staged = state.stage(anchor)
    val changed = com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
      .persistState(
        worldOver(staged.storage, anchor.stateRoot, hashed.codes).saveAccount(dave, Account(8, UInt256(9)))
      )
      .stateRootHash
    // the same change on the hash-keyed reference, which also holds the new state's nodes for `rowsOf`
    require(
      hashed.change(hashed.root)(_.saveAccount(dave, Account(8, UInt256(9)))) == changed,
      "the position store and the reference disagree on the new root"
    )
    val next = BlockRef(ByteString(Array.fill[Byte](32)(6)), anchor.number + 1, changed)
    state.commitCanonical(Some(staged), next, None) match
      case _: PathStateStorage.CommitOutcome.Applied => next
      case other => throw new IllegalStateException(s"the commit was not applied: $other")
