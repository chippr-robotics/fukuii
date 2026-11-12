package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk

/** What the specs of the overlay share: a datadir on the position store with a hash store beside it that says what each
  * state is, and blocks executed on both.
  */
trait LayerChains:
  self: Matchers =>

  import PathStateStorage.*
  import PathStateTestKit.*

  type Tx = World => World
  type Disk = (Map[ByteString, ByteString], Map[ByteString, ByteString])

  def slots(address: Address, written: (Int, Int)*): Tx = withSlots(address, written)
  def account(address: Address, nonce: Int, balance: Int): Tx = world =>
    world.saveAccount(
      address,
      world.getAccount(address).getOrElse(Account(0, 0)).copy(nonce = nonce, balance = balance)
    )
  def delete(address: Address): Tx = _.deleteAccount(address)
  val eve: Address = Address(0xe7e)

  /** An in-memory source whose writes fail when told to. (A subclass and not a wrapper: the storages ask a source
    * whether it is the in-memory one, to know the shape of its keys.)
    */
  final class Failing extends EphemDataSource(Map.empty):
    @volatile var fails = false
    override def update(dataSourceUpdates: Seq[com.chipprbots.ethereum.db.dataSource.DataUpdate]): Unit =
      if fails then throw new java.io.IOException("the disk is full")
      super.update(dataSourceUpdates)

  /** A datadir whose position store holds `HashedState`'s state as block 1's. */
  final class Chain(
      settings: CommitSettings = CommitSettings(),
      maxViewAgeMoves: Int = 8,
      val source: EphemDataSource = EphemDataSource()
  ):
    val hashed = new HashedState
    val first: BlockRef = block(1, hashed.root)
    val rows = new PathNodeStorage(source)
    val appState = new AppStateStorage(source)
    writeState(rows, hashed.positions())
    appState.putStateAnchor(first).commit()
    val state = new PathStateStorage(rows, appState, maxViewAgeMoves, settings)
    val records = new ChangesetStorage(source)

    def disk: Disk = allRows(rows)
    def everything: Map[ByteString, ByteString] =
      source.storage.map((key, value) => ByteString(key.array()) -> ByteString(value))

    /** The rows a state is, by a walk of its raw nodes on the hash store. */
    def rowsOf(root: ByteString): Disk =
      val positions = RawTrieWalk.statePositions(root.toArray, hashed.nodes.load)
      (
        positions.getOrElse(None, Map.empty),
        positions.collect { case (Some(owner), trie) => trie.map((hp, node) => (owner ++ hp) -> node) }.flatten.toMap
      )

    /** The best-block pointer, as the block's own half of the batch. */
    def pointer(ref: BlockRef): Option[DataSourceBatchUpdate] =
      Some(appState.putBestBlockInfo(BlockInfo(ref.hash, ref.number)))

    /** A block of `transactions` on `parent`: executed on a fresh staged layer, and on the hash store. */
    final class Executed(val parent: BlockRef, transactions: Seq[Tx], behind: Boolean = false):
      // on the anchor or a block forward layers lead to, as the node stages a block; or, for the overlay's own
      // specs, on a block behind the anchor or on a fork
      val staged: PathStagedState = if behind then state.stageOf(parent) else state.stage(parent)
      def run(start: World): World =
        transactions.foldLeft(start)((world, tx) => InMemoryWorldStateProxy.persistState(tx(world)))
      val root: ByteString = run(hashed.world(parent.stateRoot)).stateRootHash
      // what the block's world reads, through whatever layers its parent's state is made of, is the parent's state
      run(worldOver(staged.storage, parent.stateRoot, hashed.codes)).stateRootHash shouldBe root
      // named by its parent and its state: two blocks on one parent, or at one height, are two blocks
      val ref: BlockRef =
        BlockRef(ByteString(kec256((parent.hash ++ root).toArray)), parent.number + 1, root)

      def inPlace(): Commit = state.commitCanonical(Some(staged), ref, pointer(ref)) match
        case CommitOutcome.Applied(done) => done
        case other                       => fail(s"block ${ref.show} was not applied: $other")
      def layered(): ForwardLayer = state.commitOverlay(staged, ref) match
        case OverlayOutcome.Layered(layer) => layer
        case other                         => fail(s"block ${ref.show} was not kept as a layer: $other")

    def on(parent: BlockRef)(transactions: Tx*): Executed = new Executed(parent, transactions)
    def onAny(parent: BlockRef)(transactions: Tx*): Executed = new Executed(parent, transactions, behind = true)

    def promote(ref: BlockRef): Commit = state.promote(ref, pointer(ref)) match
      case CommitOutcome.Applied(done) => done
      case other                       => fail(s"block ${ref.show} was not promoted: $other")

    /** Every stored node of the state of `ref`, read through `storage` at its position: each must be the node the
      * reference has there.
      */
    def readThrough(storage: MptStorage, ref: BlockRef): Unit =
      RawTrieWalk.statePositions(ref.stateRoot.toArray, hashed.nodes.load).foreach { (trie, trieRows) =>
        trieRows.foreach { (hp, node) =>
          val position = PositionKey(trie, hp)
          withClue(s"block ${ref.number}, ${position.show}: ") {
            ByteString(storage.forOwner(trie).getAt(position.path, kec256(node.toArray)).encode) shouldBe node
          }
        }
      }

    /** The rows of `owner`'s storage trie in the state of `ref`. */
    def rowsOf(ref: BlockRef, owner: ByteString): Map[ByteString, ByteString] =
      rowsOf(ref.stateRoot)._2.filter(_._1.startsWith(owner))

  // A trie replaced whole in one block, made again in the next and replaced again in the third: each is settled against
  // rows that are partly the disk's and partly the layers'.
  val fourBlocks: Seq[Seq[Tx]] = Seq(
    Seq(slots(alice, 1 -> 5, 2 -> 6).andThen(account(dave, 8, 8)), delete(bob)),
    Seq(
      slots(bob, (1 to 30).map(i => i -> (i + 7))*),
      slots(carol, otherSlots.take(12).map((k, _) => k -> 0)*)
    ),
    Seq(delete(bob), account(eve, 0, 1).andThen(slots(eve, 1 -> 1, 2 -> 2)), slots(alice, 41 -> 1)),
    Seq(slots(bob, 3 -> 3), delete(crowd(3)))
  )
