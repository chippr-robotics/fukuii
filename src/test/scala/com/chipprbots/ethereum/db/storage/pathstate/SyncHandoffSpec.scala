package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockBodiesStorage
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.ChainWeightStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.*

/** What a sync that ends on the unified state layout writes, and in how many writes (spec 018 C5a part 5b): the pivot's
  * header and its place in the index, its weight, the best block, the recoveries' flags and the heal's marker, with the
  * state's anchor, in one synced batch.
  */
class SyncHandoffSpec extends AnyFlatSpec with Matchers:
  import PathStateTestKit.*

  final private class Counting extends EphemDataSource(Map.empty):
    @volatile var plain = 0
    @volatile var synced = 0
    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      plain += 1
      super.update(dataSourceUpdates)
    override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
      synced += 1
      super.update(dataSourceUpdates)

  /** A datadir of the unified layout at the end of a sync, and the pivot's header over the state the sync wrote. */
  private class Synced:
    val source = new Counting
    val hashed = new HashedState
    val appState = new AppStateStorage(source)
    val headers = new BlockHeadersStorage(source)
    val numbers = new BlockNumberMappingStorage(source)
    val weights = new ChainWeightStorage(source)
    val bodies = new BlockBodiesStorage(source)
    val rows = new PathNodeStorage(source)
    writeState(rows, hashed.positions())
    appState.putStateLayout().commit()
    val state = new PathStateStorage(rows, appState)
    val handoff =
      new SyncHandoff(state, rows, appState, headers, numbers, weights, bodies, new EvmCodeStorage(source))
    val pivot: BlockHeader =
      Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(5000), stateRoot = TrieRoot(hashed.root))
    val weight: ChainWeight = ChainWeight.totalDifficultyOnly(BigInt(123456789))
    source.plain = 0
    source.synced = 0

    def commit(storage: Boolean = true, code: Boolean = true, clean: Boolean = true): Boolean =
      handoff.commit(pivot, weight, storageRecoveryDone = storage, bytecodeRecoveryDone = code, cleanDecision = clean)

  "The hand-off of a sync" should "write the pivot's block records with the state's anchor, in one synced write" taggedAs UnitTest in {
    val s = new Synced

    s.commit() shouldBe false

    withClue("one write, synced: ")((s.source.synced, s.source.plain) shouldBe (1, 0))
    val hash = s.pivot.hash.value
    s.headers.get(hash) shouldBe Some(s.pivot)
    withClue("the pivot's place in the index of blocks by number moves with the state: ") {
      s.numbers.get(BigInt(5000)) shouldBe Some(hash)
    }
    s.weights.get(hash) shouldBe Some(s.weight)
    s.appState.getBestBlockInfo() shouldBe BlockInfo(hash, 5000)
    s.state.anchor shouldBe Some(BlockRef(hash, 5000, s.hashed.root))
    s.appState.getStateOrigin() shouldBe Some(BlockRef(hash, 5000, s.hashed.root))
    s.appState.isSnapSyncDone() shouldBe true
    s.appState.isStorageRecoveryDone() shouldBe true
    s.appState.isBytecodeRecoveryDone() shouldBe true
  }

  it should "write a recovery that is owed as owed, over what an earlier sync left" taggedAs UnitTest in {
    val s = new Synced
    s.appState.storageRecoveryDone().and(s.appState.bytecodeRecoveryDone()).commit()

    s.commit(storage = false, code = true)

    s.appState.isStorageRecoveryDone() shouldBe false
    s.appState.isBytecodeRecoveryDone() shouldBe true

    val other = new Synced
    other.appState.storageRecoveryDone().and(other.appState.bytecodeRecoveryDone()).commit()
    other.commit(storage = true, code = false)
    other.appState.isStorageRecoveryDone() shouldBe true
    other.appState.isBytecodeRecoveryDone() shouldBe false
  }

  it should "take the scan that was owed off the books" taggedAs UnitTest in {
    val s = new Synced
    s.appState.markSnapCodeScanOwed("a repair run").commit()
    s.commit()
    s.appState.getSnapCodeScanOwed() shouldBe None
  }

  it should "keep the heal's marker on a clean decision, and remove it without one, in the same write" taggedAs UnitTest in {
    val clean = new Synced
    clean.appState.stampSnapHealInvariant().commit()
    clean.source.plain = 0
    clean.commit(clean = true) shouldBe false
    clean.appState.isSnapHealInvariantPresent() shouldBe true

    val lazyHandOff = new Synced
    lazyHandOff.appState.stampSnapHealInvariant().commit()
    lazyHandOff.source.plain = 0
    lazyHandOff.commit(clean = false) shouldBe true
    lazyHandOff.appState.isSnapHealInvariantPresent() shouldBe false
    (lazyHandOff.source.synced, lazyHandOff.source.plain) shouldBe (1, 0)

    withClue("no marker to remove: ") {
      new Synced().commit(clean = false) shouldBe false
    }
  }

  it should "write nothing when the store refuses the hand-off" taggedAs UnitTest in {
    val s = new Synced
    s.commit()
    val again = s.pivot.copy(number = BlockNumber(5001))
    an[IllegalStateException] should be thrownBy
      s.handoff.commit(again, s.weight, storageRecoveryDone = true, bytecodeRecoveryDone = true, cleanDecision = true)
    s.headers.get(again.hash.value) shouldBe None
    s.numbers.get(BigInt(5001)) shouldBe None
    s.appState.getBestBlockInfo().number shouldBe 5000
  }

  it should "say what is missing of the window below the pivot, by the stores it reads" taggedAs UnitTest in {
    val s = new Synced
    val parent = s.pivot.copy(number = BlockNumber(4999))
    val child = s.pivot.copy(parentHash = parent.hash)
    s.headers.put(child.hash.value, child).commit()

    val noParent = s.handoff.windowMissing(child, proofOfWork = false)
    noParent.header.map(_._1) shouldBe Some(BigInt(4999))

    s.headers.put(parent.hash.value, parent).commit()
    val bodiesWanted = s.handoff.windowMissing(child, proofOfWork = true)
    bodiesWanted.bodies.map(_.number.value) should contain(BigInt(5000))
    s.bodies.put(child.hash.value, BlockBody.empty).commit()
    s.handoff.windowMissing(child, proofOfWork = true).bodies.map(_.number.value) should not contain BigInt(5000)
  }

  it should "sample the rows it was built over" taggedAs UnitTest in {
    val s = new Synced
    val result = s.handoff.sample(s.hashed.root, accountsLeftOut = false)
    result.sampledAccountLeaves should be > 0
    result.sampledAccountMisses shouldBe 0
    a[com.chipprbots.ethereum.db.storage.PathHandoffSample.Stopped] should be thrownBy
      s.handoff.sample(ByteString(Array.fill[Byte](32)(3)), accountsLeftOut = false)
  }

  it should "look for the code of the accounts it sampled in the node's own code store" taggedAs UnitTest in {
    val source = new Counting
    val hashed = new HashedState
    val code = ByteString("the code of an account")
    val root = hashed.change(hashed.root)(_.saveCode(dave, code))
    val rows = new PathNodeStorage(source)
    writeState(rows, hashed.positions(root))
    val appState = new AppStateStorage(source)
    val codes = new EvmCodeStorage(source)
    val handoff = new SyncHandoff(
      new PathStateStorage(rows, appState),
      rows,
      appState,
      new BlockHeadersStorage(source),
      new BlockNumberMappingStorage(source),
      new ChainWeightStorage(source),
      new BlockBodiesStorage(source),
      codes
    )

    val before = handoff.sample(root, accountsLeftOut = false)
    before.sampledCode shouldBe 1
    withClue("the sync's store of code holds none yet: ")(before.sampledCodeAbsent shouldBe 1)

    codes.put(ByteString(com.chipprbots.ethereum.crypto.kec256(code.toArray)), code).commit()
    handoff.sample(root, accountsLeftOut = false).sampledCodeAbsent shouldBe 0
  }
