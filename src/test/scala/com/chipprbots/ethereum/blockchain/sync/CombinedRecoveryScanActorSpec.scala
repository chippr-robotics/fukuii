package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.CodeHash
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.testing.Tags.*

/** The scan-actor wrapper must run the combined parallel scan once and emit BOTH gap sets to its parent, exactly as the
  * underlying scanner computes them — so the controller can drive the downloads.
  */
class CombinedRecoveryScanActorSpec extends ScalaTestWithActorTestKit() with AnyFlatSpecLike with Matchers:

  implicit private val classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem

  private def fixtures()
      : (StateStorage, EvmCodeStorage, AppStateStorage, ByteString, Set[ByteString], Set[ByteString]) =
    val ds = EphemDataSource()
    val (stateStorage, _, _) = StateStorage.createTestStateStorage(ds)
    val evm = new EvmCodeStorage(ds)
    val appState = new AppStateStorage(EphemDataSource())
    val build = stateStorage.getBackingStorage(0)

    def presentCode(seed: Int): ByteString =
      val code = Array.fill[Byte](8)(seed.toByte)
      val h = ByteString(kec256(code))
      evm.put(h, ByteString(code)).commit()
      h
    def missingCode(seed: Int): ByteString = ByteString(kec256(Array[Byte](seed.toByte, 0x5a)))
    def presentStorage(seed: Int): ByteString =
      ByteString(
        MerklePatriciaTrie[Array[Byte], Array[Byte]](build)
          .put(Array[Byte](seed.toByte, 1), Array[Byte](seed.toByte, 2))
          .getRootHash
      )
    def missingStorage(seed: Int): ByteString = ByteString(kec256(Array[Byte](seed.toByte, 0x3c)))
    def acct(seed: Int): ByteString = ByteString(kec256(Array[Byte](seed.toByte)))
    def account(storageRoot: TrieRoot, codeHash: CodeHash): Account =
      Account(nonce = UInt256.Zero, storageRoot = storageRoot, codeHash = codeHash)

    val mCode2 = missingCode(2)
    val mStor3 = missingStorage(3)
    val mCode5 = missingCode(5)
    val accounts = Seq(
      acct(1) -> account(TrieRoot(presentStorage(1)), CodeHash(presentCode(1))),
      acct(2) -> account(Account.EmptyStorageRootHash, CodeHash(mCode2)),
      acct(3) -> account(TrieRoot(mStor3), Account.EmptyCodeHash),
      acct(5) -> account(TrieRoot(presentStorage(5)), CodeHash(mCode5)),
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

  "CombinedRecoveryScanActor" should "scan once and emit both gap sets to its parent" taggedAs (UnitTest, SyncTest) in {
    val (stateStorage, evm, appState, root, expectedCode, expectedStorageRoots) = fixtures()
    val parent = TestProbe("parent")

    testKit.spawn(
      CombinedRecoveryScanActor(
        stateRoot = root,
        stateStorage = stateStorage,
        evmCodeStorage = evm,
        appStateStorage = appState,
        syncController = parent.ref.toTyped[Any],
        pivotBlockNumber = BigInt(0),
        snapSyncConfig = SNAPSyncConfig(recoveryScanConcurrency = 2, recoveryScanShardDepth = 1)
      ),
      "combined-recovery-scan-spec-t1"
    )

    val msg = parent.expectMsgType[CombinedRecoveryScanActor.CombinedScanComplete](10.seconds)
    msg.missingBytecodes.toSet shouldBe expectedCode
    msg.missingStorageTries.map(_._2).toSet shouldBe expectedStorageRoots
    msg.missingStorageTries.size shouldBe 1
    msg.holes shouldBe 0L
    msg.failed shouldBe false
  }

  // spec 022 T050a: what the controller gets must tell a pass that ran from a scan that could not run. Before this any
  // failure read as empty gap sets, and the controller wrote both flags over nothing.
  private class Holes:
    private val ds = EphemDataSource()
    private val (stateStorage, nodes, _) = StateStorage.createTestStateStorage(ds)
    private val evm = new EvmCodeStorage(ds)
    private val build = stateStorage.getBackingStorage(0)

    private def key(nibble: Int, tag: Int): Array[Byte] =
      Array.tabulate[Byte](32)(i => if i == 0 then (nibble << 4).toByte else if i == 1 then tag.toByte else 0)
    private def missing(seed: Int): ByteString = ByteString(kec256(Array[Byte](seed.toByte, 0x5a)))
    private def codeOnly(code: ByteString): Account =
      Account(nonce = UInt256.Zero, storageRoot = Account.EmptyStorageRootHash, codeHash = CodeHash(code))

    val beside: ByteString = missing(1)
    val underHole: ByteString = missing(2)
    val alone: ByteString = missing(3)
    val storageGap: ByteString = missing(4)
    val root: ByteString = ByteString(
      Seq(
        key(1, 0x10) -> codeOnly(beside),
        key(1, 0x20) -> codeOnly(underHole),
        key(2, 0x10) -> codeOnly(alone),
        key(3, 0x10) -> Account(
          nonce = UInt256.Zero,
          storageRoot = TrieRoot(storageGap),
          codeHash = Account.EmptyCodeHash
        )
      ).foldLeft(MerklePatriciaTrie[Array[Byte], Array[Byte]](build)) { case (t, (k, a)) => t.put(k, a.toBytes) }
        .getRootHash
    )

    private def branchAtOrBelow(node: MptNode): BranchNode = node match
      case b: BranchNode    => b
      case e: ExtensionNode => branchAtOrBelow(e.next)
      case h: HashNode      => branchAtOrBelow(build.get(h.hash))
      case other            => fail(s"no branch below $other")
    private def hashOf(node: MptNode): ByteString = ByteString(node.asInstanceOf[HashNode].hash)
    private lazy val rootBranch = branchAtOrBelow(HashNode(root.toArray))

    def depthOneChild(n: Int): ByteString = hashOf(rootBranch.children(n))
    def deeperNode: ByteString = hashOf(branchAtOrBelow(rootBranch.children(1)).children(2))
    def drop(hash: ByteString): Unit =
      nodes.update(Seq(hash), Nil)
      ()
    def corrupt(hash: ByteString): Unit =
      val threeItems = rlp.encode(
        rlp.RLPList(rlp.RLPValue(Array[Byte](1)), rlp.RLPValue(Array[Byte](2)), rlp.RLPValue(Array[Byte](3)))
      )
      nodes.update(Nil, Seq(hash -> threeItems))
      ()

    def scan(name: String): CombinedRecoveryScanActor.CombinedScanComplete =
      val parent = TestProbe(s"parent-$name")
      testKit.spawn(
        CombinedRecoveryScanActor(
          stateRoot = root,
          stateStorage = stateStorage,
          evmCodeStorage = evm,
          appStateStorage = new AppStateStorage(EphemDataSource()),
          syncController = parent.ref.toTyped[Any],
          pivotBlockNumber = BigInt(0),
          snapSyncConfig = SNAPSyncConfig(recoveryScanConcurrency = 2, recoveryScanShardDepth = 1)
        ),
        s"combined-recovery-scan-spec-$name"
      )
      parent.expectMsgType[CombinedRecoveryScanActor.CombinedScanComplete](10.seconds)

  it should "complete over an absent depth-1 child: the gaps of the rest of the trie, and the hole counted" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val h = new Holes
    h.drop(h.depthOneChild(2))
    val msg = h.scan("depth-one")
    msg.failed shouldBe false
    msg.holes shouldBe 1L
    msg.missingBytecodes.toSet shouldBe Set(h.beside, h.underHole)
    msg.missingStorageTries.map(_._2).toSet shouldBe Set(h.storageGap)
  }

  it should "complete over an absent deeper node: the gaps of the rest of the trie, and the hole counted" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val h = new Holes
    h.drop(h.deeperNode)
    val msg = h.scan("deeper")
    msg.failed shouldBe false
    msg.holes shouldBe 1L
    msg.missingBytecodes.toSet shouldBe Set(h.beside, h.alone)
    msg.missingStorageTries.map(_._2).toSet shouldBe Set(h.storageGap)
  }

  it should "report an absent root as failed, not as a trie with no gaps" taggedAs (UnitTest, SyncTest) in {
    val h = new Holes
    h.drop(h.root)
    val msg = h.scan("absent-root")
    msg.failed shouldBe true
    msg.missingBytecodes shouldBe empty
    msg.missingStorageTries shouldBe empty
  }

  it should "report a node that will not decode as failed, not as a hole" taggedAs (UnitTest, SyncTest) in {
    val h = new Holes
    h.corrupt(h.deeperNode)
    h.scan("undecodable").failed shouldBe true
  }

  it should "report an empty trie as a pass with nothing in it, not as failed" taggedAs (UnitTest, SyncTest) in {
    val ds = EphemDataSource()
    val (stateStorage, _, _) = StateStorage.createTestStateStorage(ds)
    val parent = TestProbe("parent-empty")
    testKit.spawn(
      CombinedRecoveryScanActor(
        stateRoot = ByteString(MerklePatriciaTrie.EmptyRootHash),
        stateStorage = stateStorage,
        evmCodeStorage = new EvmCodeStorage(ds),
        appStateStorage = new AppStateStorage(EphemDataSource()),
        syncController = parent.ref.toTyped[Any],
        pivotBlockNumber = BigInt(0),
        snapSyncConfig = SNAPSyncConfig()
      ),
      "combined-recovery-scan-spec-empty"
    )
    val msg = parent.expectMsgType[CombinedRecoveryScanActor.CombinedScanComplete](10.seconds)
    msg.failed shouldBe false
    msg.holes shouldBe 0L
  }
