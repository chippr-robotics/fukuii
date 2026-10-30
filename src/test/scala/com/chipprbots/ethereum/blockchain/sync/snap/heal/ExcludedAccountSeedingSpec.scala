package com.chipprbots.ethereum.blockchain.sync.snap.heal

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.actors.healing.HealRequestFixtures.*
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.ExcludedReason
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** Spec 022 T034/T035: the pure part of the excluded set's resolution. */
class ExcludedAccountSeedingSpec extends AnyFlatSpec with Matchers:

  private val key: Array[Byte] = (0 until 64).map(i => ((i * 7 + 3) % 16).toByte).toArray
  private val accountHash: ByteString = packNibbles(key)
  private val storageRoot: ByteString = ByteString(Array.fill(32)(0x51.toByte))
  private val codeHash: ByteString = ByteString(Array.fill(32)(0x52.toByte))
  private val value: ByteString = ByteString(
    Account.accountSerializer.toBytes(
      Account(
        nonce = UInt256(1L),
        balance = UInt256(1L),
        storageRoot = TrieRoot(storageRoot),
        codeHash = com.chipprbots.ethereum.domain.CodeHash(codeHash)
      )
    )
  )
  private val leafAtDepth3 = LeafNode(ByteString(key.drop(3)), value)

  /** The node under each hash: a branch at depth 0, an extension over nibbles 1..2 (the third branch-free nibble is the
    * branch below), and the leaf.
    */
  private val branch3: BranchNode = branch(key(2).toInt -> leafAtDepth3)
  private val ext = ExtensionNode(ByteString(key.slice(1, 2)), HashNode(branch3.hash))
  private val rootNode: BranchNode = branch(key(0).toInt -> ext)
  private val nodes: Seq[MptNode] = Seq(rootNode, ext, branch3, leafAtDepth3)
  private val root = ByteString(rootNode.hash)

  private def reader(absent: Set[ByteString] = Set.empty): (ByteString, Seq[ByteString]) => Option[MptNode] =
    (hash, _) => nodes.find(n => ByteString(n.hash) == hash).filterNot(n => absent(ByteString(n.hash)))

  private def present(extra: ByteString*): (ByteString, Seq[ByteString]) => Option[MptNode] =
    (hash, path) => reader()(hash, path).orElse(Option.when(extra.contains(hash))(NullNode))

  "lookup" should "find an account through a branch, an extension and a leaf" taggedAs UnitTest in {
    ExcludedAccountSeeding.lookup(root, accountHash, reader()) match
      case ExcludedAccountSeeding.Lookup.Found(a) => a.storageRoot.value shouldBe storageRoot
      case other                                  => fail(s"not found: $other")
  }

  it should "name the first absent node, with the path to fetch it from" taggedAs UnitTest in {
    ExcludedAccountSeeding.lookup(root, accountHash, reader(Set(ByteString(ext.hash)))) shouldBe
      ExcludedAccountSeeding.Lookup.Missing(
        Seq(ByteString(HexPrefix.encode(key.take(1), isLeaf = false))),
        ByteString(ext.hash)
      )
    ExcludedAccountSeeding.lookup(root, accountHash, reader(Set(root))) shouldBe
      ExcludedAccountSeeding.Lookup.Missing(Seq(ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))), root)
  }

  it should "say absent when the walk reaches a node that excludes the key" taggedAs UnitTest in {
    def other(differAt: Int): ByteString =
      val k = key.clone()
      k(differAt) = ((k(differAt) + 1) % 16).toByte
      packNibbles(k)
    withClue("an empty branch slot: ") {
      ExcludedAccountSeeding.lookup(root, other(0), reader()) shouldBe ExcludedAccountSeeding.Lookup.Absent
    }
    withClue("another extension key: ") {
      ExcludedAccountSeeding.lookup(root, other(1), reader()) shouldBe ExcludedAccountSeeding.Lookup.Absent
    }
    withClue("another leaf key: ") {
      ExcludedAccountSeeding.lookup(root, other(10), reader()) shouldBe ExcludedAccountSeeding.Lookup.Absent
    }
  }

  it should "say absent for a leaf whose value is not an account" taggedAs UnitTest in {
    val notAccount = LeafNode(ByteString(key), ByteString(Array[Byte](1, 2, 3)))
    val r: (ByteString, Seq[ByteString]) => Option[MptNode] = (_, _) => Some(notAccount)
    ExcludedAccountSeeding.lookup(ByteString(notAccount.hash), accountHash, r) shouldBe
      ExcludedAccountSeeding.Lookup.Absent
  }

  "accountPathPositions" should "name every position on the key, depth 0 to 64, once" taggedAs UnitTest in {
    val positions = ExcludedAccountSeeding.accountPathPositions(accountHash)
    positions should have size 65
    positions.head._2 shouldBe empty
    positions.last._2 shouldBe key
    positions.foreach(_._1 shouldBe None)
    positions.map(p => ByteString(p._2)).distinct should have size 65
  }

  private def seeding(
      store: SnapSyncProgressStorage,
      read: (ByteString, Seq[ByteString]) => Option[MptNode],
      code: ByteString => Boolean = _ => true,
      pageSize: Int = 256
  ) = new ExcludedAccountSeeding(store, read, code, pageSize)

  private def freshStore(): SnapSyncProgressStorage = new SnapSyncProgressStorage(EphemDataSource())

  "A step" should "remove an account whose storage root and code are present, durably" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageSkipped)))
    val step = seeding(store, present(storageRoot)).step(root)
    step.removed shouldBe 1
    step.seeds shouldBe empty
    step.changed shouldBe true
    store.hasExcluded shouldBe false
  }

  it should "seed an absent storage root at the account's own position and keep the entry" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned)))
    val step = seeding(store, reader()).step(root)
    step.seeds shouldBe Seq(
      (Seq(accountHash, ByteString(HexPrefix.encode(Array.empty[Byte], isLeaf = false))), storageRoot)
    )
    step.removed shouldBe 0
    store.hasExcluded shouldBe true
  }

  it should "ask for absent code and keep the entry, for an account and for a bare code hash" taggedAs UnitTest in {
    val store = freshStore()
    val bare = ByteString(Array.fill(32)(0x53.toByte))
    store.recordExcluded(
      Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned), ExcludedAccount(bare, ExcludedReason.CodeMissing))
    )
    val step = seeding(store, present(storageRoot), code = _ => false).step(root)
    step.missingCode.toSet shouldBe Set(codeHash, bare)
    step.removed shouldBe 0
  }

  it should "remove a bare code hash once the code is stored, with no root in play" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(codeHash, ExcludedReason.CodeMissing)))
    seeding(store, (_, _) => throw new RuntimeException("a code entry reads no node")).step(root).removed shouldBe 1
  }

  it should "seed the node a lookup finds absent, not the account's storage" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned)))
    val step = seeding(store, reader(Set(ByteString(ext.hash)))).step(root)
    step.seeds.map(_._2) shouldBe Seq(ByteString(ext.hash))
    store.hasExcluded shouldBe true
  }

  it should "keep an entry whose read fails, and resolve the rest of the page" taggedAs UnitTest in {
    val store = freshStore()
    val gone = ByteString(Array.fill(32)(0xee.toByte)) // not on the trie, so resolved by the walk from the root
    store.recordExcluded(
      Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned), ExcludedAccount(gone, ExcludedReason.StorageSkipped))
    )
    val failing: (ByteString, Seq[ByteString]) => Option[MptNode] = (hash, path) =>
      if hash == storageRoot then throw new RuntimeException("injected read failure") else reader()(hash, path)
    val step = seeding(store, failing).step(root)
    step.failedReads shouldBe 1
    step.removed shouldBe 1
    store.excludedPage(None, 10).map(_.subject) shouldBe Seq(accountHash)
  }

  it should "walk the set in pages: more until the last, then a new pass from the start" taggedAs UnitTest in {
    val store = freshStore()
    val entries = (1 to 3).map(i => ExcludedAccount(ByteString(Array.fill(32)(i.toByte)), ExcludedReason.CodeMissing))
    store.recordExcluded(entries)
    val s = seeding(store, reader(), code = _ => false, pageSize = 2)
    val first = s.step(root)
    first.more shouldBe true
    first.missingCode should have size 2
    val second = s.step(root)
    second.more shouldBe false
    second.missingCode should have size 1
    (first.missingCode ++ second.missingCode).distinct should have size 3
    s.step(root).missingCode should have size 2 // a new pass
  }

  it should "report a change in an earlier page of the pass in its last step, and forget it at the next pass" taggedAs UnitTest in {
    val store = freshStore()
    val entries = (1 to 3).map(i => ExcludedAccount(ByteString(Array.fill(32)(i.toByte)), ExcludedReason.CodeMissing))
    store.recordExcluded(entries)
    var stored = Set(entries.head.subject)
    val s = seeding(store, reader(), code = h => stored(h), pageSize = 1)
    s.step(root).changed shouldBe true // the first entry is complete and leaves
    s.step(root).changed shouldBe true
    val last = s.step(root)
    last.more shouldBe true // a full page: the pass ends with an empty one
    val end = s.step(root)
    end.more shouldBe false
    end.changed shouldBe true
    s.step(root).changed shouldBe false
    stored = Set.empty
  }

  it should "start over after restart" taggedAs UnitTest in {
    val store = freshStore()
    val entries = (1 to 3).map(i => ExcludedAccount(ByteString(Array.fill(32)(i.toByte)), ExcludedReason.CodeMissing))
    store.recordExcluded(entries)
    val s = seeding(store, reader(), code = _ => false, pageSize = 1)
    val first = s.step(root).missingCode
    s.restart()
    s.step(root).missingCode shouldBe first
  }

  it should "leave the pass where it was when a page cannot be read" taggedAs UnitTest in {
    final class FailingScan extends EphemDataSource(Map.empty):
      var failing = false
      override def scanRangeLimit(ns: IndexedSeq[Byte], from: Array[Byte], to: Array[Byte], limit: Int) =
        if failing then throw new RuntimeException("injected") else super.scanRangeLimit(ns, from, to, limit)
    val source = new FailingScan
    val store = new SnapSyncProgressStorage(source)
    store.recordExcluded(Seq(ExcludedAccount(codeHash, ExcludedReason.CodeMissing)))
    val s = seeding(store, reader(), code = _ => false)
    source.failing = true
    an[RuntimeException] should be thrownBy s.step(root)
    source.failing = false
    s.step(root).missingCode shouldBe Seq(codeHash)
  }
