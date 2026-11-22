package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BadEntryDelete
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.db.storage.RepairOutcome
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.DirectRowMptStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit.*
import com.chipprbots.ethereum.db.storage.pathstate.PositionKey
import com.chipprbots.ethereum.db.storage.pathstate.PositionRepairs
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.CorruptNodeException
import com.chipprbots.ethereum.mpt.NibblePath
import com.chipprbots.ethereum.mpt.ShardEnumerator
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C5b (T214, T219): the recovery scans give the same verdicts on the unified layout, read by
  * position, as on the hash layout, on a seeded store with injected gaps.
  *
  * '''The oracle.''' The plan compares the verdicts with those of the pinned 0.12.1 jar. That jar cannot be run where
  * this was written, so the verdicts are pinned instead against the hash-layout scan on today's staging, which is the
  * oracle the jar embodies (the scans are the code 0.12.1 shipped, changed only to read by position, and
  * `RecoveryScannersPositionSpec` holds them to the verdicts frozen from before that). The comparison with the pinned
  * v0.12.1 is `scripts/oracle/b375/run.sh` (B-375; `baselines/b375-recovery-scan-oracle.md`): 66 of 66 verdicts equal.
  * Every expectation here is written out by hand from the fixture, not recorded from a run.
  *
  * What is held:
  *   - each scan (`CombinedRecoveryScan` whole and by shard, the legacy storage and bytecode scans, the sharded and
  *     parallel `CombinedRecoveryScanner`) finds on rows exactly what it finds on hashes, for each injected gap;
  *   - the hash scheme and the guard off are untouched (`RecoveryScanStores` hands back the hash store);
  *   - the scan only reads rows, one position at a time, and a state that moves under it ends it and is never a gap.
  */
class RecoveryScansVerdictSpec extends AnyFlatSpec with Matchers:

  import RecoveryScannersPositionSpec.{firstOfEachRoot, quiet}

  private type Position = (Option[ByteString], ByteString)

  /** One state with code in it (alice and carol have the same, dave has another), held as a hash store and as rows. */
  final private class Seeded:
    val hashed = new HashedState
    val codeA: ByteString = ByteString("alice's code, which carol has too")
    val codeB: ByteString = ByteString("dave's code")
    val root: ByteString = hashed.change(hashed.root)(
      _.saveCode(alice, codeA).saveCode(carol, codeA).saveCode(dave, codeB)
    )
    val anchor: BlockRef = block(7, root)
    val rows: Map[Option[ByteString], com.chipprbots.ethereum.mpt.PositionTestKit.Rows] = hashed.positions(root)

    def hashOfCode(code: ByteString): ByteString = ByteString(kec256(code.toArray))

    /** A code store without `lacking`. */
    def codes(lacking: ByteString*): EvmCodeStorage =
      val store = new EvmCodeStorage(EphemDataSource())
      Seq(codeA, codeB).map(c => hashOfCode(c) -> c).filterNot((h, _) => lacking.contains(h)).foreach { (h, c) =>
        store.put(h, c).commit()
      }
      store

    def hashAt(position: Position): ByteString = ByteString(kec256(rows(position._1)(position._2).toArray))

    /** The positions of one trie, the root first. */
    def stored(trie: Option[ByteString]): Seq[Position] =
      rows(trie).keys.toSeq
        .sortBy(row =>
          (com.chipprbots.ethereum.mpt.HexPrefix.decode(row.toArray)._1.length, row.map("%02x".format(_)).mkString)
        )
        .map(trie -> _)

    /** Every position holding the node `position` holds, in any trie: what a hash store loses with it. */
    def everywhere(position: Position): Seq[Position] =
      val node = rows(position._1)(position._2)
      rows.toSeq.flatMap((trie, rs) => rs.collect { case (row, `node`) => trie -> row })

    /** The rows as a position store: the state of `anchor`, without the rows at `holes`. */
    def positionStore(holes: Position*): PathStateStorage =
      val source = EphemDataSource()
      seedPositions(
        source,
        rows.map((trie, rs) => trie -> rs.filterNot((row, _) => holes.contains(trie -> row))),
        anchor
      )
      new PathStateStorage(new PathNodeStorage(source), new AppStateStorage(source))

    /** The hash store without the nodes at `holes`: lost wherever they are held. */
    def hashStore(holes: Position*): MptStorage = new Lacking(hashed.nodes, holes.map(hashAt).toSet)

  /** What a scan found, or what it threw. */
  final private case class Found(code: Seq[ByteString], storage: Seq[(ByteString, ByteString)], holes: Long)
  private def hex(b: ByteString): String = org.bouncycastle.util.encoders.Hex.toHexString(b.toArray)

  /** Every scan of the state at `root` over `store`, by name. */
  private def scans(root: ByteString, store: MptStorage, codes: EvmCodeStorage): Seq[(String, Either[String, Found])] =
    def combined(run: CombinedRecoveryScan => Unit) = RecoveryScannersPositionSpec.thrown {
      val scan = new CombinedRecoveryScan(store, codes)
      run(scan)
      Found(scan.missingBytecodes, scan.missingStorageTries, scan.holes)
    }
    def shards(depth: Int) = ShardEnumerator.enumShards(root, store, depth)
    def driver(concurrency: Int) = RecoveryScannersPositionSpec.thrown {
      val result = new CombinedRecoveryScanner(
        root,
        () => store,
        codes,
        new AppStateStorage(EphemDataSource()),
        concurrency = concurrency,
        shardDepth = 1
      ).run()
      Found(result.missingBytecodes, result.missingStorageTries, result.holes)
    }
    Seq(
      "scanFrom" -> combined(_.scanFrom(root)),
      "scanShards" -> combined(scan => shards(1).foreach(shard => scan.scanShard(shard.root, shard.pathPrefix))),
      "scanner-sequential" -> driver(1),
      "scanner-parallel" -> driver(2),
      "legacy-storage" -> RecoveryScannersPositionSpec.thrown(
        Found(Nil, StorageRecoveryActor.scanForMissingStorage(root, store, quiet), 0)
      ),
      "legacy-bytecode" -> RecoveryScannersPositionSpec.thrown(
        Found(BytecodeRecoveryActor.scanForMissingBytecodes(root, store, codes, quiet), Nil, 0)
      )
    )

  private def aliceOwner = owner(alice)
  private def bobOwner = owner(bob)
  private def carolOwner = owner(carol)

  // The gaps. Each is a list of positions lost, and what a scan must say of it, written by hand:
  //   - whole: nothing;
  //   - code: the one code alice and carol share is missing, once (dave's is present);
  //   - alice's storage root, and only hers (V3, a position store only): alice's trie, not bob's;
  //   - the storage root that alice and bob share, lost from both: one trie on a hash store, two on rows;
  //   - carol's storage root: her own trie;
  //   - a node of the account trie below the root: a hole, nothing under it was looked at.
  "On a seeded store with injected gaps, each scan" should "find on rows what it finds on hashes" taggedAs UnitTest in {
    val s = new Seeded
    val aliceRoot = s.stored(Some(aliceOwner)).head
    val carolRoot = s.stored(Some(carolOwner)).head
    val accountChild =
      s.stored(None).find(p => com.chipprbots.ethereum.mpt.HexPrefix.decode(p._2.toArray)._1.length == 1).get
    val sharedHash = s.hashAt(aliceRoot)
    s.hashAt(s.stored(Some(bobOwner)).head) shouldBe sharedHash // alice and bob hold one trie

    final case class Gap(name: String, lost: Seq[Position], codesLacking: Seq[ByteString])
    val gaps = Seq(
      Gap("whole", Nil, Nil),
      Gap("code-shared-by-two-accounts", Nil, Seq(s.hashOfCode(s.codeA))),
      Gap("both-codes", Nil, Seq(s.hashOfCode(s.codeA), s.hashOfCode(s.codeB))),
      Gap("shared-storage-root", s.everywhere(aliceRoot), Nil),
      Gap("carol-storage-root", Seq(carolRoot), Nil),
      Gap("storage-root-and-code", Seq(carolRoot), Seq(s.hashOfCode(s.codeB))),
      Gap("account-child", Seq(accountChild), Nil)
    )

    for gap <- gaps do
      withClue(s"${gap.name}: ") {
        val codes = s.codes(gap.codesLacking*)
        val onHashes = scans(s.root, s.hashStore(gap.lost*), codes)
        val onRows = scans(s.root, DirectRowMptStorage(s.positionStore(gap.lost*), s.root), codes)
        onRows.map(_._1) shouldBe onHashes.map(_._1)
        onRows.zip(onHashes).foreach { case ((name, byRows), (_, byHashes)) =>
          withClue(s"$name: ") {
            // One gap for each account on rows; on hashes the trie is one, found under whichever account names it
            // first. A parallel scan's shards race to that, so the hash store's owner is checked to be one of the
            // row scan's owners of the same root, not a particular one (B-065, seen on CI).
            (byRows, byHashes) match
              case (Right(rows), Right(hashes)) =>
                hashes.code.sortBy(hex) shouldBe rows.code.sortBy(hex)
                hashes.holes shouldBe rows.holes
                hashes.storage.map(_._2).sortBy(hex) shouldBe firstOfEachRoot(rows.storage).map(_._2).sortBy(hex)
                val ownersOnRows = rows.storage.groupMap(_._2)(_._1)
                hashes.storage.foreach((owner, root) => ownersOnRows(root) should contain(owner))
              case _ => byRows shouldBe byHashes
          }
        }

        // and the oracle, by hand
        val combined =
          onHashes.toMap.apply(if gap.name == "account-child" then "scanShards" else "scanFrom").toOption.get
        gap.name match
          case "whole" => combined shouldBe Found(Nil, Nil, 0)
          case "code-shared-by-two-accounts" =>
            combined shouldBe Found(Seq(s.hashOfCode(s.codeA)), Nil, 0)
          case "both-codes" =>
            combined.code.toSet shouldBe Set(s.hashOfCode(s.codeA), s.hashOfCode(s.codeB))
            combined.code should have size 2
          case "shared-storage-root" =>
            combined.storage.map(_._2) shouldBe Seq(sharedHash)
            Set(aliceOwner, bobOwner) should contain(combined.storage.head._1)
            onRows.toMap.apply("scanFrom").toOption.get.storage.map(_._1).toSet shouldBe Set(aliceOwner, bobOwner)
          case "carol-storage-root" => combined.storage shouldBe Seq(carolOwner -> s.hashAt(carolRoot))
          case "storage-root-and-code" =>
            combined shouldBe Found(Seq(s.hashOfCode(s.codeB)), Seq(carolOwner -> s.hashAt(carolRoot)), 0)
          case "account-child" =>
            // the shards walk around the node that is gone and count it; the whole walk has nothing to count it with
            combined.holes shouldBe 1L
            onHashes.toMap.apply("scanFrom").left.toOption.get should startWith("threw ")
            onRows.toMap.apply("scanShards").map(_.holes) shouldBe Right(1L)
      }
  }

  // Spec 022 V3, as the scan on rows meets it: the trie is two there, and a loss of one is the scan's to find.
  "A storage trie only one of two accounts with its root has lost" should
    "be found by the scan on rows under the account that lost it" taggedAs UnitTest in {
      val s = new Seeded
      val aliceRoot = s.stored(Some(aliceOwner)).head
      for lost <- Seq(aliceOwner, bobOwner) do
        val position = s.stored(Some(lost)).head
        scans(s.root, DirectRowMptStorage(s.positionStore(position), s.root), s.codes()).foreach { (name, found) =>
          withClue(s"$name, ${lost.take(2)} lost: ") {
            found.map(_.storage) match
              case Right(storage) if name == "legacy-bytecode" => storage shouldBe Nil
              case other                                       => other shouldBe Right(Seq(lost -> s.hashAt(aliceRoot)))
          }
        }
    }

  "A storage root whose row is another node, or bytes that are no node" should
    "be a missing storage trie to the scan, as an absent one is" taggedAs UnitTest in {
      val s = new Seeded
      val carolRoot = s.stored(Some(carolOwner)).head
      val other = s.rows(Some(aliceOwner))(s.stored(Some(aliceOwner)).head._2)
      val source = EphemDataSource()
      seedPositions(source, s.rows, s.anchor)
      val rowsStore = new PathNodeStorage(source)
      val state = new PathStateStorage(rowsStore, new AppStateStorage(source))
      val storeRows = DirectRowMptStorage(state, s.root)
      for bytes <- Seq(other.toArray, Array[Byte](0xff.toByte, 1, 2, 3)) do
        source.update(rowsStore.rowUpdates(Seq(PositionKey(carolRoot._1, carolRoot._2) -> Some(ByteString(bytes)))))
        new CombinedRecoveryScan(storeRows, s.codes()).tap(_.scanFrom(s.root)).missingStorageTries shouldBe
          Seq(carolOwner -> s.hashAt(carolRoot))
    }

  "A node of the account trie that is another node than its parent names" should
    "end the scan as corrupt and never as a hole" taggedAs UnitTest in {
      val s = new Seeded
      val child = s.stored(None)(1)
      val sibling = s.stored(None)(2)
      val source = EphemDataSource()
      seedPositions(source, s.rows, s.anchor)
      val rowsStore = new PathNodeStorage(source)
      source.update(
        rowsStore.rowUpdates(Seq(PositionKey(None, child._2) -> Some(ByteString(s.rows(None)(sibling._2)))))
      )
      val store = DirectRowMptStorage(new PathStateStorage(rowsStore, new AppStateStorage(source)), s.root)
      a[CorruptNodeException] should be thrownBy new CombinedRecoveryScan(store, s.codes()).scanFrom(s.root)
      scans(s.root, store, s.codes()).foreach { (name, found) =>
        withClue(s"$name: ") {
          // the legacy scans log a walk that failed and return what they had, on either layout, as they always have
          if name.startsWith("legacy") then found shouldBe Right(Found(Nil, Nil, 0))
          else found.left.toOption.exists(_.contains("CorruptNodeException")) shouldBe true
        }
      }
    }

  /** `inner` with its anchor moved, and every row gone, once it has answered `after` reads. */
  final private class MovesAfter(inner: PositionRepairs, after: Int, moved: BlockRef) extends PositionRepairs:
    private var reads = 0
    val asked: ArrayBuffer[Seq[PositionKey]] = mutable.ArrayBuffer.empty[Seq[PositionKey]]
    def readsMade: Int = reads
    private def gone: Boolean = reads >= after
    override def anchor: Option[BlockRef] = if gone then Some(moved) else inner.anchor
    override def rawRowsAt(requests: Seq[(PositionKey, ByteString)]): Seq[RawNodeRead] =
      asked += requests.map(_._1)
      val answered = if gone then requests.map(_ => RawNodeRead.Absent) else inner.rawRowsAt(requests)
      reads += 1
      answered
    override def repairNodeAt(p: PositionKey, h: ByteString, b: Array[Byte]): RepairOutcome =
      inner.repairNodeAt(p, h, b)
    override def deleteBadNodeAt(p: PositionKey, h: ByteString): BadEntryDelete = inner.deleteBadNodeAt(p, h)

  "A scan that reads the rows of a state that moves" should
    "end as a failure wherever the move falls, and never report a gap or a hole" taggedAs UnitTest in {
      val s = new Seeded
      val moved = block(8, ByteString(kec256("next".getBytes("UTF-8"))))
      val whole = new MovesAfter(s.positionStore(), Int.MaxValue, moved)
      val expected = scans(s.root, DirectRowMptStorage(whole, s.root), s.codes())
      expected.foreach((name, found) => withClue(s"$name, unmoved: ")(found.map(_.holes) shouldBe Right(0L)))
      val total = whole.readsMade
      total should be > 30

      for after <- 0 until total by 3 do
        val moving = new MovesAfter(s.positionStore(), after, moved)
        scans(s.root, DirectRowMptStorage(moving, s.root), s.codes()).zip(expected).foreach {
          case ((name, found), (_, complete)) =>
            withClue(s"$name, moved after $after reads: ") {
              // the whole answer (the walk's last read came before the move), or a failure: nothing in between
              found match
                case Left(text) => text shouldBe "threw StateUnavailable"
                case Right(_)   => found shouldBe complete
            }
        }
    }

  "A scan on the unified layout" should "read rows by position only, each node at the position its parent puts it" taggedAs
    UnitTest in {
      val s = new Seeded
      val counted = new MovesAfter(s.positionStore(), Int.MaxValue, s.anchor)
      // by-hash reads are refused by the store, so a scan that made one would throw and not give this answer
      val found = scans(s.root, DirectRowMptStorage(counted, s.root), s.codes())
      found.foreach((name, f) => withClue(s"$name: ")(f.isRight shouldBe true))
      found.toMap.apply("scanFrom") shouldBe Right(Found(Nil, Nil, 0))
      val keys = counted.asked.flatten.toSet
      val stored = s.rows.flatMap((trie, rs) => rs.keys.map(row => PositionKey(trie, row))).toSet
      keys.subsetOf(stored) shouldBe true
      // every stored row was asked for: the walk reached each node, the storage roots too
      keys shouldBe stored.filter(k => k.owner.isEmpty || s.stored(k.owner).head._2 == k.hp)
      an[UnsupportedOperationException] should be thrownBy DirectRowMptStorage(counted, s.root).rawNodes(Nil)
      an[UnsupportedOperationException] should be thrownBy DirectRowMptStorage(counted, s.root).get(Array.fill(32)(1))
    }

  "holdsNodeAt" should "say what the one row says, and let a move through" taggedAs UnitTest in {
    val s = new Seeded
    val aliceRoot = s.stored(Some(aliceOwner)).head
    val hash = s.hashAt(aliceRoot)
    val onRows = DirectRowMptStorage(s.positionStore(), s.root).forOwner(Some(aliceOwner))
    onRows.holdsNodeAt(NibblePath.Empty, hash) shouldBe true
    onRows.holdsNodeAt(NibblePath.Empty, ByteString(kec256(Array[Byte](1)))) shouldBe false // another node's hash
    DirectRowMptStorage(s.positionStore(aliceRoot), s.root)
      .forOwner(Some(aliceOwner))
      .holdsNodeAt(NibblePath.Empty, hash) shouldBe false
    val moved = new MovesAfter(s.positionStore(), 0, block(8, ByteString(kec256("next".getBytes("UTF-8")))))
    a[StateUnavailable] should be thrownBy DirectRowMptStorage(moved, s.root)
      .forOwner(Some(aliceOwner))
      .holdsNodeAt(NibblePath.Empty, hash)
    // a hash store answers as the presence read of B-034 does
    s.hashStore().forOwner(Some(aliceOwner)).holdsNodeAt(NibblePath.Empty, hash) shouldBe true
    s.hashStore(aliceRoot).forOwner(Some(aliceOwner)).holdsNodeAt(NibblePath.Empty, hash) shouldBe false
  }

  "RecoveryScanStores" should "hand back the hash store with no position store, and the anchor's rows with one" taggedAs
    UnitTest in {
      val s = new Seeded
      val ds = EphemDataSource()
      val (stateStorage, _, _) = StateStorage.createTestStateStorage(ds)
      // guard off, Hash scheme: the hash store, as it was
      RecoveryScanStores.forRoot(stateStorage, None, s.root, 0).getClass shouldBe
        stateStorage.getBackingStorage(0).getClass
      RecoveryScanStores.readable(stateStorage, None, s.root, 0) shouldBe false // the state is not in this store
      // on the layout: only the anchor's root is readable, and the anchor is the state to scan
      val positions = s.positionStore()
      RecoveryScanStores.readable(stateStorage, Some(positions), s.root, 0) shouldBe true
      RecoveryScanStores.readable(stateStorage, Some(positions), ByteString(kec256("other".getBytes)), 0) shouldBe false
      RecoveryScanStores.anchorOf(Some(positions)) shouldBe Some(s.anchor)
      RecoveryScanStores.anchorOf(None) shouldBe None
      RecoveryScanStores
        .readable(stateStorage, Some(s.positionStore(s.stored(None).head)), s.root, 0) shouldBe false
    }

  extension [A](a: A)
    private def tap(f: A => Unit): A =
      f(a); a
