package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy

/** A canonical chain on the unified state layout as a node holds one: the state of its head in the position store, a
  * changeset record for each block, each block's header and its place in the index by number, and the best block. And
  * the chain reader over them, which is what serves a state to a peer by its root.
  *
  * A hash store is given every block too ([[hashed]]), and says what each block's state is.
  */
final class CanonicalChainOnPositions(
    settings: PathStateStorage.CommitSettings = PathStateStorage.CommitSettings(),
    maxViewAgeMoves: Int = 8,
    val source: EphemDataSource = EphemDataSource()
):
  import PathStateStorage.*
  import PathStateTestKit.*

  val hashed = new HashedState
  val appState = new AppStateStorage(source)
  val headersByHash = new BlockHeadersStorage(source)
  val hashesByNumber = new BlockNumberMappingStorage(source)

  /** A header at `number` with `stateRoot`; `salt` makes another block of the same height and state. */
  def headerOf(number: BigInt, stateRoot: ByteString, salt: Int = 0): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(number),
      stateRoot = TrieRoot(stateRoot),
      extraData = ByteString(s"block beside the chain $salt".getBytes)
    )

  def refOf(header: BlockHeader): BlockRef = BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

  private val first: BlockHeader = headerOf(1, hashed.root)
  seed(source, hashed, refOf(first))
  headersByHash
    .put(first.hash.value, first)
    .and(hashesByNumber.put(first.number.value, first.hash.value))
    .and(appState.putBestBlockInfo(BlockInfo(first.hash.value, first.number.value)))
    .commit()

  val state = new PathStateStorage(new PathNodeStorage(source), appState, maxViewAgeMoves, settings)
  val reader = new BlockchainReader(headersByHash, null, hashesByNumber, null, null, appState, null, null, Some(state))

  /** The chain's headers, oldest first: the first is at height 1. */
  var headers: Vector[BlockHeader] = Vector(first)
  def head: BlockHeader = headers.last

  private def run(start: World, transactions: Seq[World => World]): World =
    transactions.foldLeft(start)((world, tx) => InMemoryWorldStateProxy.persistState(tx(world)))

  /** The next block of the chain: executed on the head and committed with its header, its place in the index and the
    * best block, in one batch. With no transactions it leaves the state as it was, and has its parent's state root.
    */
  def next(transactions: (World => World)*): BlockHeader =
    val parent = refOf(head)
    val staged = state.stage(parent)
    val root = run(hashed.world(parent.stateRoot), transactions).stateRootHash
    require(
      run(worldOver(staged.storage, parent.stateRoot, hashed.codes), transactions).stateRootHash == root,
      "the position store and the hash store do not agree on the block's state"
    )
    val header = headerOf(parent.number + 1, root)
    val records = headersByHash
      .put(header.hash.value, header)
      .and(hashesByNumber.put(header.number.value, header.hash.value))
      .and(appState.putBestBlockInfo(BlockInfo(header.hash.value, header.number.value)))
    state.commitCanonical(Some(staged), refOf(header), Some(records)) match
      case CommitOutcome.Applied(_) => ()
      case other => throw new IllegalStateException(s"block ${header.number} was not applied: $other")
    headers :+= header
    header

  /** A block beside the chain, on `parent`: executed and kept as a layer, with its header stored by its hash and
    * '''not''' in the index by number.
    */
  def beside(parent: BlockHeader, salt: Int)(transactions: (World => World)*): BlockHeader =
    val on = refOf(parent)
    val staged = state.stageOf(on)
    val root = run(hashed.world(on.stateRoot), transactions).stateRootHash
    run(worldOver(staged.storage, on.stateRoot, hashed.codes), transactions)
    val header = headerOf(on.number + 1, root, salt)
    headersByHash.put(header.hash.value, header).commit()
    state.commitOverlay(staged, refOf(header)) match
      case OverlayOutcome.Kept(_) => ()
      case other                  => throw new IllegalStateException(s"block ${header.number} was not kept: $other")
    header
