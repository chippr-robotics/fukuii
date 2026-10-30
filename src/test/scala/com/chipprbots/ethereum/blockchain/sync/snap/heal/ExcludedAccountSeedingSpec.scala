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
      Seq(
        ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned),
        ExcludedAccount(bare, ExcludedReason.CodeMissing)
      )
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

  // Spec 022 review item 4 (hunter W2): A to B to A, or a reorg. The decision stands for the account's storage whatever
  // this root holds, so "no such account" must not forget it.
  private val elsewhere: BranchNode = branch(((key(0) + 1) % 16) -> leafAtDepth3)
  private val elsewhereRoot: ByteString = ByteString(elsewhere.hash)
  private def withElsewhere(
      read: (ByteString, Seq[ByteString]) => Option[MptNode]
  ): (ByteString, Seq[ByteString]) => Option[MptNode] =
    (hash, path) => if hash == elsewhereRoot then Some(elsewhere) else read(hash, path)

  "An account the root does not hold" should "stay in the set, dormant, and stop counting against the root" taggedAs UnitTest in {
    val store = freshStore()
    val entry = ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned, claimedStorageRoot = storageRoot)
    store.recordExcluded(Seq(entry))
    val step = seeding(store, withElsewhere(reader())).step(elsewhereRoot)
    step.removed shouldBe 0
    step.demoted shouldBe 1
    step.seeds shouldBe empty
    step.changed shouldBe true
    store.hasExcluded shouldBe false
    store.excludedPage(None, 10) shouldBe Seq(entry.copy(dormant = true))
  }

  it should "count again, with its seed, at a root that holds it incomplete" taggedAs UnitTest in {
    val store = freshStore()
    val entry = ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned, claimedStorageRoot = storageRoot)
    store.recordExcluded(Seq(entry.copy(dormant = true)))
    val step = seeding(store, reader()).step(root)
    step.promoted shouldBe 1
    step.seeds should have size 1
    step.changed shouldBe true
    store.excludedPage(None, 10) shouldBe Seq(entry)
  }

  it should "count again when the walk to it hits a missing node: the root may hold it" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned, dormant = true)))
    val step = seeding(store, reader(Set(ByteString(ext.hash)))).step(root)
    step.promoted shouldBe 1
    step.seeds.map(_._2) shouldBe Seq(ByteString(ext.hash))
    store.hasExcluded shouldBe true
  }

  it should "leave the set at a root that holds it complete" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned, dormant = true)))
    seeding(store, present(storageRoot)).step(root).removed shouldBe 1
    store.hasAnyExcluded shouldBe false
  }

  it should "stay dormant, without a write or a change, at another root that does not hold it" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned, dormant = true)))
    val step = seeding(store, withElsewhere(reader())).step(elsewhereRoot)
    step.changed shouldBe false
    store.excludedPage(None, 10).map(_.dormant) shouldBe Seq(true)
  }

  it should "survive A to B to A: back at A it is seeded again" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned)))
    val s = seeding(store, withElsewhere(reader()))
    s.step(root).seeds should have size 1 // A: incomplete
    s.restart()
    s.step(elsewhereRoot).demoted shouldBe 1 // B: no such account
    s.restart()
    s.step(root).seeds should have size 1 // A again
    store.hasExcluded shouldBe true
  }

  "blocking" should "hold for a dormant entry only until a pass has looked at it from this root" taggedAs UnitTest in {
    val store = freshStore()
    val s = seeding(store, withElsewhere(reader()))
    s.blocking shouldBe false
    store.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned, dormant = true)))
    s.blocking shouldBe true
    s.step(elsewhereRoot)
    s.blocking shouldBe false
    s.restart() // a new root: it may hold the account
    s.blocking shouldBe true
  }

  it should "hold for any active entry, whatever the pass" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(codeHash, ExcludedReason.CodeMissing)))
    val s = seeding(store, reader(), code = _ => false)
    s.blocking shouldBe true
    s.step(root)
    s.blocking shouldBe true
  }

  // Spec 022 review (the user's decision, 2026-10-30): what "only code" means for the set.
  "onlyCodeOutstanding" should "be unknown, so false, until a pass has ended at this root" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(
      Seq(
        ExcludedAccount(codeHash, ExcludedReason.CodeMissing),
        ExcludedAccount(storageRoot, ExcludedReason.CodeMissing)
      )
    )
    val s = seeding(store, reader(), code = _ => false, pageSize = 1)
    s.onlyCodeOutstanding shouldBe false
    s.step(root).more shouldBe true
    withClue("the pass has a page left: ")(s.onlyCodeOutstanding shouldBe false)
    s.step(root).more shouldBe true
    s.step(root).more shouldBe false
    s.onlyCodeOutstanding shouldBe true
    s.restart()
    withClue("a new root is unknown again: ")(s.onlyCodeOutstanding shouldBe false)
  }

  it should "be true for an account that needs nothing but its code" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned)))
    val s = seeding(store, present(storageRoot), code = _ => false)
    s.step(root).seeds shouldBe empty
    s.onlyCodeOutstanding shouldBe true
  }

  it should "not count a seed whose node is already here, only not on disk: its chain is held" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned)))
    val s = new ExcludedAccountSeeding(
      store,
      reader(Set(root)), // the root is held, so a read of the store does not find it
      _ => false,
      seedNeedsNoFetch = _._2 == root
    )
    val step = s.step(root)
    step.seeds shouldBe empty
    withClue("the entry stays: nothing has shown it complete: ")(store.hasExcluded shouldBe true)
    s.onlyCodeOutstanding shouldBe true
  }

  it should "be false after a pass that seeded a node or could not read one" taggedAs UnitTest in {
    val seeded = freshStore()
    seeded.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned)))
    val a = seeding(seeded, reader(), code = _ => false)
    a.step(root).seeds should not be empty
    a.onlyCodeOutstanding shouldBe false

    val failing = freshStore()
    failing.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned)))
    val b = seeding(failing, (_, _) => throw new RuntimeException("read failed"), code = _ => false)
    b.step(root).failedReads shouldBe 1
    b.onlyCodeOutstanding shouldBe false
  }

  it should "be false with nothing outstanding: there is nothing for it to be only" taggedAs UnitTest in {
    val store = freshStore()
    val s = seeding(store, reader())
    s.step(root)
    s.onlyCodeOutstanding shouldBe false
  }

  // Round 2 review A: a dormant entry going up and down at each re-peg is not the set changing.
  "accountSignature" should "not change when an entry goes dormant or comes back, or when the root moves" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned)))
    val s = seeding(store, withElsewhere(reader()))
    s.step(root)
    val first = s.accountSignature
    first should not be BlockerClock.SetSignature.Empty
    s.restart()
    s.accountSignature shouldBe first
    s.step(elsewhereRoot).demoted shouldBe 1
    s.accountSignature shouldBe first
    s.restart()
    s.step(root).promoted shouldBe 1
    s.accountSignature shouldBe first
  }

  it should "change when an account leaves the set, and not for a code entry" taggedAs UnitTest in {
    val store = freshStore()
    val s = seeding(store, present(storageRoot))
    store.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned)))
    val withAccount =
      val blocked = seeding(store, reader())
      blocked.step(root)
      blocked.accountSignature
    s.step(root).removed shouldBe 1
    s.accountSignature shouldBe BlockerClock.SetSignature.Empty
    withAccount should not be BlockerClock.SetSignature.Empty
    store.recordExcluded(Seq(ExcludedAccount(codeHash, ExcludedReason.CodeMissing)))
    val c = seeding(store, reader(), code = _ => false)
    c.step(root)
    c.accountSignature shouldBe BlockerClock.SetSignature.Empty
  }

  "A code entry of the empty code hash" should "leave at once: there is no such code to fetch (beacon)" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(Account.EmptyCodeHash.value, ExcludedReason.CodeMissing)))
    val step = seeding(store, reader(), code = _ => false).step(root)
    step.removed shouldBe 1
    step.missingCode shouldBe empty
  }

  "A step" should "seed the node a lookup finds absent, not the account's storage" taggedAs UnitTest in {
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
      Seq(
        ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned),
        ExcludedAccount(gone, ExcludedReason.StorageSkipped)
      )
    )
    val failing: (ByteString, Seq[ByteString]) => Option[MptNode] = (hash, path) =>
      if hash == storageRoot then throw new RuntimeException("injected read failure") else reader()(hash, path)
    val step = seeding(store, failing).step(root)
    step.failedReads shouldBe 1
    step.demoted shouldBe 1
    store.excludedPage(None, 10).map(e => (e.subject, e.dormant)) shouldBe Seq((accountHash, false), (gone, true))
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

  "The heal start's position deletes" should "go in batches of whole keys, each position once, skipping code-only entries (T034)" taggedAs UnitTest in {
    val store = freshStore()
    val accounts = (0 to ExcludedAccountSeeding.DeleteBatchAccounts).map { i =>
      ExcludedAccount(ByteString(Array.fill[Byte](31)(0x11) :+ i.toByte), ExcludedReason.StorageAbandoned)
    }
    store.recordExcluded(accounts :+ ExcludedAccount(codeHash, ExcludedReason.CodeMissing))
    val batches = scala.collection.mutable.ArrayBuffer.empty[Seq[(Option[ByteString], Array[Byte])]]
    seeding(store, reader()).deleteAccountPositions(batches += _) shouldBe accounts.size
    batches should have size 2
    batches.foreach { batch =>
      batch.map(p => ByteString(p._2)).distinct should have size batch.size.toLong
    }
    accounts.foreach { account =>
      val key = ExcludedAccountSeeding.accountNibbles(account.subject)
      val holding = batches.filter(_.exists(_._2.sameElements(key)))
      withClue("the deepest position of each key is in one batch: ")(holding should have size 1)
      val positions = holding.head.map(p => ByteString(p._2)).toSet
      (0 to key.length).foreach(depth => positions should contain(ByteString(key.take(depth))))
    }
    val codeKey = ExcludedAccountSeeding.accountNibbles(codeHash)
    batches.flatten.exists(_._2.sameElements(codeKey)) shouldBe false
  }

  // Between two batches a write can fail, and more than one batch needs more than 128 accounts (beacon N-2, vault).
  it should "leave what it deleted deleted and throw when a later batch fails, with more than 128 accounts" taggedAs UnitTest in {
    val store = freshStore()
    val accounts = (0 until 130).map(i => ByteString(Array.fill[Byte](31)(0x11) :+ i.toByte))
    store.recordExcluded(accounts.map(ExcludedAccount(_, ExcludedReason.StorageAbandoned)))
    var batches = 0
    val failure = intercept[RuntimeException] {
      seeding(store, reader()).deleteAccountPositions { _ =>
        batches += 1
        if batches == 2 then throw new RuntimeException("second batch failed")
      }
    }
    failure.getMessage shouldBe "second batch failed"
    batches shouldBe 2
    withClue("the set is what a later run deletes from: ")(store.excludedPage(None, 1000) should have size 130)

    var deleted = Seq.empty[Int]
    seeding(store, reader()).deleteAccountPositions(batch => deleted = deleted :+ batch.size) shouldBe 130
    withClue("each account's 65 positions in one batch: ") {
      deleted.size shouldBe 2
    }
  }

  it should "not delete the positions of a dormant entry: the root does not hold that account" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(accountHash, ExcludedReason.StorageAbandoned, dormant = true)))
    var deleted = 0
    seeding(store, reader()).deleteAccountPositions(_ => deleted += 1) shouldBe 0
    deleted shouldBe 0
  }

  it should "do nothing with no account in the set" taggedAs UnitTest in {
    val store = freshStore()
    store.recordExcluded(Seq(ExcludedAccount(codeHash, ExcludedReason.CodeMissing)))
    val batches = scala.collection.mutable.ArrayBuffer.empty[Seq[(Option[ByteString], Array[Byte])]]
    seeding(store, reader()).deleteAccountPositions(batches += _) shouldBe 0
    batches shouldBe empty
  }
