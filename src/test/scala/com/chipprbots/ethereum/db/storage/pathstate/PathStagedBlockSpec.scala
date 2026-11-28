package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.ledger.RecordingDataSource
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C3b (T093): the staged state of one block on the unified layout, as block execution holds it. */
class PathStagedBlockSpec extends AnyFlatSpec with Matchers:
  import PathStateTestKit.*

  final private class Fixture:
    val source: RecordingDataSource = new RecordingDataSource
    val hashed = new HashedState
    val parent: BlockRef = block(1, hashed.root)
    seed(source, hashed, parent)
    val appState = new AppStateStorage(source)
    val state = new PathStateStorage(new PathNodeStorage(source), appState)
    def disk: (Map[ByteString, ByteString], Map[ByteString, ByteString]) = allRows(new PathNodeStorage(source))

    /** The next block: dave's nonce set, on both stores; and the staged block for it. */
    private val changed: World => World = _.saveAccount(dave, Account(nonce = 9, balance = 9))
    val root: ByteString = hashed.change(hashed.root)(changed)
    val next: BlockRef = block(2, root)
    def staged(parentOf: () => Option[BlockRef] = () => Some(parent), of: BlockRef = next, apart: Boolean = false) =
      new PathStagedBlock(state, of, parentOf, apart)
    val code: ByteString = ByteString(0x60, 0x01, 0x60, 0x00, 0x55)
    val codeHash: ByteString = kec256(code)
    val codes = new EvmCodeStorage(source)
    def stageCode(staged: PathStagedBlock): Unit = staged.stageCode(codes.update(Nil, Seq(codeHash -> code)))
    def codeBatches: Seq[Set[IndexedSeq[Byte]]] = source.batches.filter(_.contains(Namespaces.CodeNamespace)).toSeq
    def execute(staged: PathStagedBlock): Unit =
      InMemoryWorldStateProxy.persistState(changed(worldOver(staged.storage, hashed.root, hashed.codes)))
    def pointer(ref: BlockRef): Some[DataSourceBatchUpdate] = Some(
      appState.putBestBlockInfo(BlockInfo(ref.hash, ref.number))
    )

  "A block staged on the unified layout" should "be committed in place, with its own records, in one batch" taggedAs
    UnitTest in {
      val f = new Fixture
      val staged = f.staged()
      staged.commitsInPlace shouldBe true
      staged.pending shouldBe None
      staged.isBlockApplied(f.next.hash) shouldBe false
      f.execute(staged)
      staged.commitWith(f.next.number, f.next.hash, f.pointer(f.next))
      f.state.anchor shouldBe Some(f.next)
      f.appState.getBestBlockInfo() shouldBe BlockInfo(f.next.hash, f.next.number)
      staged.isBlockApplied(f.next.hash) shouldBe true
      f.state.readOnly(f.next).forOwner(None).get(f.root.toArray) should not be null
    }

  // Execution stages a block before it asks whether the block was executed already.
  it should "ask for no layer until its storage is asked for, so that a block that is applied can be staged" taggedAs
    UnitTest in {
      val f = new Fixture
      val first = f.staged()
      f.execute(first)
      first.commitWith(f.next.number, f.next.hash, f.pointer(f.next))
      val rows = f.disk

      // the same block again: its parent is no longer the block the disk holds
      val again = f.staged()
      again.isBlockApplied(f.next.hash) shouldBe true
      // nothing staged, and its own records are written: the pointer, put elsewhere first to see it
      f.appState.putBestBlockInfo(BlockInfo(f.parent.hash, f.parent.number)).commit()
      again.commitWith(f.next.number, f.next.hash, f.pointer(f.next))
      f.appState.getBestBlockInfo() shouldBe BlockInfo(f.next.hash, f.next.number)
      f.disk shouldBe rows
      // and only asking for its storage would have been refused
      a[StateUnavailable] should be thrownBy f.staged().storage
    }

  it should "leave nothing when it is discarded, and start again from a fresh layer" taggedAs UnitTest in {
    val f = new Fixture
    val rows = f.disk
    val staged = f.staged()
    f.execute(staged)
    staged.discard()
    f.disk shouldBe rows
    f.state.anchor shouldBe Some(f.parent)
    // the retry is one line of worlds from the parent again: the layer of the first attempt is gone
    f.execute(staged)
    staged.commitWith(f.next.number, f.next.hash, f.pointer(f.next))
    f.state.anchor shouldBe Some(f.next)
  }

  it should "not be committed without the block's own records, or as another block" taggedAs UnitTest in {
    val f = new Fixture
    val rows = f.disk
    val staged = f.staged()
    f.execute(staged)
    (the[StateUnavailable] thrownBy staged.commitWith(f.next.number, f.next.hash, None)).reason should
      include("without its own records")
    an[IllegalArgumentException] should be thrownBy staged.commitWith(7, f.next.hash, f.pointer(f.next))
    an[IllegalArgumentException] should be thrownBy
      staged.commitWith(f.next.number, f.parent.hash, f.pointer(f.next))
    f.disk shouldBe rows
    f.state.anchor shouldBe Some(f.parent)
  }

  it should "be refused its storage when its parent is not known, or is not the block the disk holds" taggedAs
    UnitTest in {
      val f = new Fixture
      (the[StateUnavailable] thrownBy f.staged(parentOf = () => None).storage).reason should include("is not known")
      val elsewhere = block(1, EmptyRoot).copy(hash = ByteString(Array.fill[Byte](32)(7)))
      a[StateUnavailable] should be thrownBy f.staged(parentOf = () => Some(elsewhere)).storage
    }

  it should "hand the prefetch a reader of its parent's rows, or none when that state cannot be viewed" taggedAs
    UnitTest in {
      val f = new Fixture
      f.staged().prefetchReader(1 << 20, _ => ()) should not be empty
      f.staged(parentOf = () => None).prefetchReader(1 << 20, _ => ()) shouldBe None
      val elsewhere = block(1, EmptyRoot).copy(hash = ByteString(Array.fill[Byte](32)(7)))
      f.staged(parentOf = () => Some(elsewhere)).prefetchReader(1 << 20, _ => ()) shouldBe None
    }

  // B-454: the code a block deployed lands with the state that refers to it.
  it should "write the block's code in the batch that commits it in place, and none of it when that batch is lost" taggedAs
    UnitTest in {
      val f = new Fixture
      val staged = f.staged()
      f.execute(staged)
      f.stageCode(staged)
      staged.commitWith(f.next.number, f.next.hash, f.pointer(f.next))
      f.codeBatches should have size 1
      (f.codeBatches.head should contain).allOf(Namespaces.AppStateNamespace, Namespaces.StateTriePathNamespace)
      f.codes.get(f.codeHash) shouldBe Some(f.code)

      val lost = new Fixture
      val refused = lost.staged()
      lost.execute(refused)
      lost.stageCode(refused)
      lost.source.refuse = _.contains(Namespaces.AppStateNamespace)
      a[RecordingDataSource.Refused] should be thrownBy
        refused.commitWith(lost.next.number, lost.next.hash, lost.pointer(lost.next))
      lost.codes.get(lost.codeHash) shouldBe None
    }

  // A forward layer is held in memory: there is no batch for the code to join, so it is written first, alone.
  it should "write the block's code on its own, first, when it is committed apart as a forward layer" taggedAs
    UnitTest in {
      val f = new Fixture
      val staged = f.staged(apart = true)
      f.execute(staged)
      f.stageCode(staged)
      staged.commitApart(f.next.number, f.next.hash)
      f.codeBatches shouldBe Seq(Set(Namespaces.CodeNamespace))
      f.codes.get(f.codeHash) shouldBe Some(f.code)
      f.state.anchor shouldBe Some(f.parent)
    }

  it should "write no code for a block that is discarded" taggedAs UnitTest in {
    val f = new Fixture
    val staged = f.staged()
    f.execute(staged)
    f.stageCode(staged)
    staged.discard()
    f.codeBatches shouldBe empty
    f.codes.get(f.codeHash) shouldBe None
  }
