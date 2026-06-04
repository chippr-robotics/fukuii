package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration._

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.WithActorSystemShutDown
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.network.p2p.messages.ETH63.AccountImplicits.AccountEnc
import com.chipprbots.ethereum.testing.Tags._

/** The scan-actor wrapper must run the combined parallel scan once and emit BOTH gap sets to its parent, exactly as the
  * underlying scanner computes them — so the controller can drive the downloads.
  */
class CombinedRecoveryScanActorSpec
    extends TestKit(ActorSystem("CombinedRecoveryScanActorSpec_System"))
    with AnyFlatSpecLike
    with WithActorSystemShutDown
    with Matchers {

  private def fixtures()
      : (StateStorage, EvmCodeStorage, AppStateStorage, ByteString, Set[ByteString], Set[ByteString]) = {
    val ds = EphemDataSource()
    val (stateStorage, _, _) = StateStorage.createTestStateStorage(ds)
    val evm = new EvmCodeStorage(ds)
    val appState = new AppStateStorage(EphemDataSource())
    val build = stateStorage.getBackingStorage(0)

    def presentCode(seed: Int): ByteString = {
      val code = Array.fill[Byte](8)(seed.toByte)
      val h = ByteString(kec256(code))
      evm.put(h, ByteString(code)).commit()
      h
    }
    def missingCode(seed: Int): ByteString = ByteString(kec256(Array[Byte](seed.toByte, 0x5a)))
    def presentStorage(seed: Int): ByteString =
      ByteString(
        MerklePatriciaTrie[Array[Byte], Array[Byte]](build)
          .put(Array[Byte](seed.toByte, 1), Array[Byte](seed.toByte, 2))
          .getRootHash
      )
    def missingStorage(seed: Int): ByteString = ByteString(kec256(Array[Byte](seed.toByte, 0x3c)))
    def acct(seed: Int): ByteString = ByteString(kec256(Array[Byte](seed.toByte)))
    def account(storageRoot: ByteString, codeHash: ByteString): Account =
      Account(nonce = UInt256.Zero, storageRoot = storageRoot, codeHash = codeHash)

    val mCode2 = missingCode(2)
    val mStor3 = missingStorage(3)
    val mCode5 = missingCode(5)
    val accounts = Seq(
      acct(1) -> account(presentStorage(1), presentCode(1)),
      acct(2) -> account(Account.EmptyStorageRootHash, mCode2),
      acct(3) -> account(mStor3, Account.EmptyCodeHash),
      acct(5) -> account(presentStorage(5), mCode5),
      acct(6) -> Account.empty()
    )
    val root = ByteString(
      accounts
        .foldLeft(MerklePatriciaTrie[Array[Byte], Array[Byte]](build)) { case (t, (h, a)) =>
          t.put(h.toArray, a.toBytes)
        }
        .getRootHash
    )
    (stateStorage, evm, appState, root, Set(mCode2, mCode5), Set(mStor3))
  }

  "CombinedRecoveryScanActor" should "scan once and emit both gap sets to its parent" taggedAs (UnitTest, SyncTest) in {
    val (stateStorage, evm, appState, root, expectedCode, expectedStorageRoots) = fixtures()
    val parent = TestProbe("parent")

    parent.childActorOf(
      CombinedRecoveryScanActor.props(
        stateRoot = root,
        stateStorage = stateStorage,
        evmCodeStorage = evm,
        appStateStorage = appState,
        syncController = parent.ref,
        pivotBlockNumber = BigInt(0),
        snapSyncConfig = SNAPSyncConfig(recoveryScanConcurrency = 2, recoveryScanShardDepth = 1)
      )
    )

    val msg = parent.expectMsgType[CombinedRecoveryScanActor.CombinedScanComplete](10.seconds)
    msg.missingBytecodes.toSet shouldBe expectedCode
    msg.missingStorageTries.map(_._2).toSet shouldBe expectedStorageRoots
    msg.missingStorageTries.size shouldBe 1
  }
}
