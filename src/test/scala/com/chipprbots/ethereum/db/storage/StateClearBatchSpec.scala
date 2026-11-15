package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.heal.HealInvariant
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.EscalationKind
import com.chipprbots.ethereum.db.storage.pathstate.FinalizedBlock
import com.chipprbots.ethereum.db.storage.pathstate.StateEscalation
import com.chipprbots.ethereum.db.storage.pathstate.StateLayout
import com.chipprbots.ethereum.domain.appstate.BlockInfo
import com.chipprbots.ethereum.testing.Tags.*

/** The record of a clear of the state, and the one batch that begins one (spec 018 C5a part 3; T172): what it removes,
  * what it keeps, and that a datadir is afterwards one SNAP starts on as on a new one.
  */
class StateClearBatchSpec extends AnyFlatSpec with Matchers:

  private def hash(byte: Int): ByteString = ByteString(Array.fill[Byte](32)(byte.toByte))
  private val genesis = hash(1)
  private val head = BlockRef(hash(7), 5000, hash(8))

  "The record of an escalation" should "be read back as it was written, and be an error when it cannot be read" taggedAs
    UnitTest in {
      for kind <- EscalationKind.values do
        val escalation = StateEscalation(1763236800123L, kind, hash(9), BigInt("123456789012"))
        StateEscalation.decode(StateEscalation.encode(escalation)) shouldBe escalation
      StateEscalation.encode(StateEscalation(42L, EscalationKind.Unreachable, hash(0xab), 7)) shouldBe
        s"v1:42:unreachable:${"ab" * 32}:7"
      StateEscalation.encode(StateEscalation(42L, EscalationKind.LocalFault, hash(0xab), 7)) should startWith(
        "v1:42:local:"
      )
      // never read as none: whoever counts the cool-down takes an unreadable one for one that has just happened
      for unreadable <- Seq(
          "",
          "v2:42:unreachable:" + "ab" * 32 + ":7",
          "v1:42:another:" + "ab" * 32 + ":7",
          "v1:x:unreachable:" + "ab" * 32 + ":7",
          "v1:42:unreachable:" + "ab" * 31 + ":7",
          "v1:42:unreachable:" + "zz" * 32 + ":7",
          "v1:42:unreachable:" + "ab" * 32 + ":-7",
          "v1:42:unreachable:" + "ab" * 32
        )
      do withClue(s"'$unreadable': ")(an[IllegalStateException] should be thrownBy StateEscalation.decode(unreadable))
    }

  /** A datadir that synced to `head` with SNAP on the unified layout and has followed the chain since. */
  private def synced(): AppStateStorage =
    val appState = new AppStateStorage(EphemDataSource())
    appState
      .markDatadirCreatedUnderInvariant()
      .and(appState.putStateLayout())
      .and(appState.putStateAnchor(head))
      .and(appState.putStateOrigin(BlockRef(hash(5), 4000, hash(6))))
      .and(appState.putStateFinalized(FinalizedBlock(4900, hash(4))))
      .and(appState.putChangesetPrunedTo(4400))
      .and(appState.putBestBlockInfo(BlockInfo(head.hash, head.number)))
      .and(appState.snapSyncDone())
      .and(appState.fastSyncDone())
      .and(appState.putSnapSyncPivotBlock(4000))
      .and(appState.putSnapSyncMinPivotBlock(3900))
      .and(appState.putSnapSyncStateRoot(hash(6)))
      .and(appState.putSnapSyncProgress("{}"))
      .and(appState.putSnapAccountResumeCheckpoint("{}"))
      .and(appState.putSnapSyncAccountsComplete(true))
      .and(appState.putSnapSyncStorageFilePath("/tmp/storage"))
      .commit()
    appState

  "The batch that begins a clear of the state" should "leave a datadir SNAP starts on as on a new one, still marked as the unified layout's" taggedAs
    UnitTest in {
      val appState = synced()
      HealInvariant.isEmpty(appState, None) shouldBe false
      val escalation = StateEscalation(1763236800000L, EscalationKind.Unreachable, hash(0x77), 5200)
      appState.stateClearBatch(genesis, atMillis = 1763236800000L, Some(escalation)).commit()

      // no state, and nothing that says a sync is done over it
      appState.getStateAnchor() shouldBe None
      appState.getStateOrigin() shouldBe None
      appState.getStateFinalized() shouldBe None
      appState.getChangesetPrunedTo() shouldBe None
      appState.isSnapSyncDone() shouldBe false
      appState.isFastSyncDone() shouldBe false
      // what the last run kept is gone: the next one does not skip a phase over rows that are gone, and owes no repair
      appState.isSnapSyncAccountsComplete() shouldBe false
      appState.getSnapSyncPivotBlock() shouldBe None
      appState.getSnapSyncMinPivotBlock() shouldBe None
      appState.getSnapSyncStateRoot() shouldBe None
      appState.getSnapSyncProgress() shouldBe None
      appState.getSnapAccountResumeCheckpoint() shouldBe None
      appState.getSnapSyncStorageFilePath().forall(_.isEmpty) shouldBe true
      appState.getBestBlockInfo() shouldBe BlockInfo(genesis, 0)
      HealInvariant.isEmpty(appState, None) shouldBe true

      // kept: the directory is the unified layout's still, and was created under the invariant
      appState.getStateLayout() shouldBe Some(StateLayout.PathLive1)
      appState.isDatadirCreatedUnderInvariant() shouldBe true
      // and the two marks of the clear
      appState.getStateEscalationLast() shouldBe Some(escalation)
      appState.isStateResetPending() shouldBe true

      // the rows gone, the mark is removed, and the escalation stays for the cool-down
      appState.removeStateResetPending().commit()
      appState.isStateResetPending() shouldBe false
      appState.getStateEscalationLast() shouldBe Some(escalation)
    }

  it should "record no escalation when there is none: the genesis state cleared as SNAP starts" taggedAs UnitTest in {
    val appState = synced()
    appState.stateClearBatch(genesis, atMillis = 5L, escalation = None).commit()
    appState.getStateEscalationLast() shouldBe None
    appState.isStateResetPending() shouldBe true
    appState.getStateAnchor() shouldBe None
    // one from before is not taken away by a clear that is not one
    val earlier = StateEscalation(1L, EscalationKind.LocalFault, hash(3), 10)
    val again = synced()
    again.stateClearBatch(genesis, 1L, Some(earlier)).commit()
    again.stateClearBatch(genesis, 2L, None).commit()
    again.getStateEscalationLast() shouldBe Some(earlier)
  }
