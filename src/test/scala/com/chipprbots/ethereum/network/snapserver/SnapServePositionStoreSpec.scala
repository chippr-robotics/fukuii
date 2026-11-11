package com.chipprbots.ethereum.network.snapserver

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.mpt.PositionTestKit.ReferenceStore
import com.chipprbots.ethereum.mpt.PositionTestKit.Rows
import com.chipprbots.ethereum.mpt.byteStringSerializer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C2b (T052): SNAP serving from the position store is, message for message, what it is from the hash
  * store.
  *
  * A peer that syncs from this node verifies every range against the root it asked about, with the proofs it is sent.
  * Where a node is stored has no place in a response: one state is held two ways here, as nodes of a hash store and as
  * rows of the position store, and each request is served from both and the two responses compared whole.
  *
  * Every read of the server is reached by a descent from a root, so every read can say where it is: the account trie
  * under no owner, a storage trie under its account's hash. In the state used here `alice` and `bob` hold the same
  * storage trie, node for node, which a store keyed by position keeps twice.
  */
class SnapServePositionStoreSpec extends AnyFlatSpec with Matchers:

  import PathStateTestKit.*

  private val zero = ByteString(new Array[Byte](32))
  private val max = ByteString(Array.fill[Byte](32)(0xff.toByte))
  private val plenty = BigInt(2 * 1024 * 1024)

  /** One state held two ways: as nodes of a hash store, and as rows of the position store, where it is `anchor`'s. */
  abstract private class TwoStores(val root: ByteString, val onHashes: MptStorage, load: Array[Byte] => Array[Byte]):
    val anchor: BlockRef = block(1, root)

    /** Every stored position of the state, trie by trie. */
    val positions: Map[Option[ByteString], Rows] = RawTrieWalk.statePositions(root.toArray, load)
    private val source = EphemDataSource()
    seedPositions(source, positions, anchor)

    /** The state as rows of CF `t` and CF `u`: the storage a view of the anchor hands out. */
    val onPositions: MptStorage =
      new PathStateStorage(new PathNodeStorage(source), new AppStateStorage(source)).readOnly(anchor)

    /** What `serve` answers from the position store, which is what it answers from the hash store. */
    def both[A](serve: MptStorage => A): A =
      val fromPositions = serve(onPositions)
      fromPositions shouldBe serve(onHashes)
      fromPositions

    /** The paths of the stored nodes of one trie, as a healing peer asks for them. */
    def paths(trie: Option[ByteString]): Seq[ByteString] = positions(trie).keys.toSeq.sortBy(hex)

  /** The state the production world builds: keys are hashes, so its tries are bushy and their extensions short. */
  final private class Fixture private (val hashed: HashedState)
      extends TwoStores(hashed.root, hashed.nodes, hashed.nodes.load):
    def this() = this(new HashedState)
    val accountHashes: Seq[ByteString] = (Seq(alice, bob, carol, dave) ++ crowd).map(owner).sortBy(hex)

  /** A state whose keys are chosen, so that its tries have what hashed keys almost never give: extensions many nibbles
    * long, in the account trie and in a storage trie, and keys that part from one in its middle. A path that is counted
    * wrong through an extension is a node looked for at the wrong position.
    */
  final private class Stretched private (nodes: ReferenceStore, built: Stretched.Built)
      extends TwoStores(built.root, nodes, nodes.load):
    def this(nodes: ReferenceStore) = this(nodes, Stretched.build(nodes))
    def this() = this(new ReferenceStore)
    export built.{accountKeys, holder, twin, slotKeys}

  private object Stretched:
    final case class Built(
        root: ByteString,
        accountKeys: Seq[ByteString],
        holder: ByteString,
        twin: ByteString,
        slotKeys: Seq[ByteString]
    )

    /** A key of 32 bytes: `prefix`, zeros, and `last`. */
    def key(prefix: String, last: Int): ByteString =
      val head = prefix.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray
      ByteString(head ++ new Array[Byte](31 - head.length) :+ last.toByte)

    def build(nodes: ReferenceStore): Built =
      // 7777…01 and 7777…02 part at their last nibble; 7778… parts from them at the fourth
      val slotKeys = Seq(key("7777", 1), key("7777", 2), key("7778", 0), key("80", 0), key("8f", 0xff))
      val slots = slotKeys.zipWithIndex.foldLeft(MerklePatriciaTrie[ByteString, ByteString](nodes)) {
        case (trie, (slot, i)) => trie.put(slot, ByteString(Array.fill[Byte](33)((i + 1).toByte)))
      }
      val storageRoot = TrieRoot(ByteString(slots.getRootHash))
      // the holder and its twin hold the same storage, and part at the sixth nibble of their keys
      val holder = key("c0ffee", 0)
      val twin = key("c0ffef", 0)
      val accounts: Seq[(ByteString, Account)] = Seq(
        key("1111", 0) -> Account(nonce = 1, balance = 10),
        key("1111", 1) -> Account(nonce = 2, balance = 20),
        key("25", 0) -> Account(nonce = 3, balance = 30),
        key("26", 0) -> Account(nonce = 4, balance = 40),
        key("3abcde", 0) -> Account(nonce = 5, balance = 50),
        key("3abcdf", 0) -> Account(nonce = 6, balance = 60),
        holder -> Account(nonce = 7, balance = 70, storageRoot = storageRoot),
        twin -> Account(nonce = 8, balance = 80, storageRoot = storageRoot)
      )
      val state = accounts.foldLeft(MerklePatriciaTrie[ByteString, Account](nodes)) { case (trie, (k, account)) =>
        trie.put(k, account)
      }
      nodes.persist()
      Built(ByteString(state.getRootHash), accounts.map(_._1), holder, twin, slotKeys)

  private def hex(bytes: ByteString): String = bytes.map("%02x".format(_)).mkString
  private def unknown: ByteString = ByteString(kec256("nobody".getBytes))
  private def accountOf(address: Address): ByteString = owner(address)

  "GetAccountRange, from the position store" should "serve the whole range as the hash store does" taggedAs UnitTest in {
    val f = new Fixture
    val served: AccountRange =
      f.both(SnapServer.serveAccountRange(1, f.hashed.root, zero, max, plenty, _))
    served.accounts.map(_._1) shouldBe f.accountHashes
    served.proof should not be empty
  }

  it should "serve a truncated range, with its right proof, as the hash store does" taggedAs UnitTest in {
    val f = new Fixture
    val served = f.both(SnapServer.serveAccountRange(2, f.hashed.root, zero, max, BigInt(400), _))
    served.accounts.size should (be > 1).and(be < f.accountHashes.size)
    // the left proof alone is the path to one key; with the right proof there are nodes of a second path
    val leftOnly = f.both(SnapServer.serveAccountRange(2, f.hashed.root, zero, zero, plenty, _))
    served.proof.size should be > leftOnly.proof.size
  }

  it should "serve a range that starts inside the keys, a reversed range and a single key as the hash store does" taggedAs
    UnitTest in {
      val f = new Fixture
      val middle = f.accountHashes(f.accountHashes.size / 2)

      val fromMiddle = f.both(SnapServer.serveAccountRange(3, f.hashed.root, middle, max, plenty, _))
      fromMiddle.accounts.map(_._1) shouldBe f.accountHashes.drop(f.accountHashes.size / 2)

      val reversed = f.both(SnapServer.serveAccountRange(4, f.hashed.root, middle, zero, plenty, _))
      reversed.accounts.map(_._1) shouldBe Seq(middle)

      val single = f.both(SnapServer.serveAccountRange(5, f.hashed.root, middle, middle, plenty, _))
      single.accounts.map(_._1) shouldBe Seq(middle)

      val pastAll = f.both(SnapServer.serveAccountRange(6, f.hashed.root, max, max, plenty, _))
      pastAll.accounts shouldBe empty
      pastAll.proof should not be empty
    }

  "GetStorageRanges, from the position store" should
    "serve each account's slots from under that account, as the hash store does" taggedAs UnitTest in {
      val f = new Fixture
      val asked = Seq(alice, bob, carol, dave).map(accountOf) :+ unknown
      val served: StorageRanges = f.both { storage =>
        SnapServer.serveStorageRanges(
          7,
          f.hashed.root,
          asked,
          zero,
          max,
          plenty,
          storage,
          SnapServer.storageRootOf(f.hashed.root, storage)
        )
      }
      // alice and bob hold the same storage: the same slots, each read under its own account
      served.slots.map(_.size) shouldBe Seq(manySlots.size, manySlots.size, otherSlots.size, 0, 0)
      served.slots(0) shouldBe served.slots(1)
    }

  it should "serve a range that starts inside the slots, and a truncated one with its proofs, as the hash store does" taggedAs
    UnitTest in {
      val f = new Fixture
      def serve(start: ByteString, budget: BigInt): StorageRanges = f.both { storage =>
        SnapServer.serveStorageRanges(
          8,
          f.hashed.root,
          Seq(accountOf(alice), accountOf(carol)),
          start,
          max,
          budget,
          storage,
          SnapServer.storageRootOf(f.hashed.root, storage)
        )
      }
      val whole = serve(zero, plenty)
      val someSlot = whole.slots.head(manySlots.size / 2)._1

      val fromInside = serve(someSlot, plenty)
      fromInside.slots.head.map(_._1) shouldBe whole.slots.head.map(_._1).drop(manySlots.size / 2)
      fromInside.proof should not be empty

      val truncated = serve(zero, BigInt(200))
      truncated.slots.head.size should (be > 0).and(be < manySlots.size)
      truncated.proof should not be empty
      // a truncated first account ends the response
      truncated.slots should have size 1
    }

  "GetTrieNodes, from the position store" should
    "serve account-trie paths and storage paths in one request as the hash store does" taggedAs UnitTest in {
      val f = new Fixture
      // the path of every stored node is a path a healing peer may ask for
      val accountPaths: Seq[Seq[ByteString]] = f.paths(None).take(12).map(Seq(_))
      def storagePaths(address: Address): Seq[ByteString] =
        accountOf(address) +: f.paths(Some(accountOf(address))).take(6)
      val asked = accountPaths ++ Seq(storagePaths(alice), storagePaths(bob), storagePaths(carol))

      val served: TrieNodes = f.both(SnapServer.serveTrieNodes(9, f.hashed.root, asked, plenty, _))
      served.nodes should have size (12 + 6 + 6 + 6)
      all(served.nodes.map(_.size)) should be > 0
      // every node served is a node of the state: its hash is a stored node's
      val stored = f.positions.values.flatMap(_.values).map(rlp => ByteString(kec256(rlp.toArray))).toSet
      served.nodes.map(rlp => ByteString(kec256(rlp.toArray))).foreach(hash => stored should contain(hash))
    }

  it should "serve a path nothing is at, and an account that has no storage, as the hash store does" taggedAs
    UnitTest in {
      val f = new Fixture
      val nowhere = ByteString(Array[Byte](0x1f, 0xff.toByte, 0xff.toByte)) // an odd path of five nibbles
      val asked = Seq(Seq(nowhere), Seq(accountOf(dave), ByteString(Array[Byte](0x00))), Seq(unknown, nowhere))
      val served = f.both(SnapServer.serveTrieNodes(10, f.hashed.root, asked, plenty, _))
      served.nodes.map(_.size).toSet shouldBe Set(0)
    }

  "A root the store does not hold" should "be answered with nothing, from either store" taggedAs UnitTest in {
    val f = new Fixture
    val otherRoot = ByteString(kec256("another state".getBytes))
    f.both(SnapServer.serveAccountRange(11, otherRoot, zero, max, plenty, _)) shouldBe AccountRange(
      11,
      Seq.empty,
      Seq.empty
    )
    f.both { storage =>
      SnapServer.serveStorageRanges(
        12,
        otherRoot,
        Seq(accountOf(alice)),
        zero,
        max,
        plenty,
        storage,
        SnapServer.storageRootOf(otherRoot, storage)
      )
    }.slots
      .flatten shouldBe empty
    f.both(SnapServer.serveTrieNodes(13, otherRoot, Seq(Seq(ByteString(Array[Byte](0x00)))), plenty, _))
      .nodes shouldBe empty
  }

  // ─── Extensions ───────────────────────────────────────────────────────────────────────────────────────────────

  private val budgets = Seq(plenty, BigInt(1), BigInt(150), BigInt(300))

  /** Keys that are in no trie here and part from its keys inside an extension, before one and after one. */
  private val strangers = Seq(
    Stretched.key("1110", 0xff),
    Stretched.key("1111", 0x10),
    Stretched.key("3abc", 0),
    Stretched.key("3abcd0", 0),
    Stretched.key("c0ff", 0),
    Stretched.key("7776", 0),
    Stretched.key("7777", 0),
    Stretched.key("77", 0)
  )

  "A state whose tries have long extensions" should "be the state the fixture says it is" taggedAs UnitTest in {
    val f = new Stretched
    def depths(trie: Option[ByteString]): Set[Int] =
      f.positions(trie).keys.map(row => HexPrefix.decode(row.toArray)._1.length).toSet
    // the branch 1111…0 and 1111…1 part at is 63 nibbles down, and the one 3abcde… and 3abcdf… part at is 5 down
    depths(None) should contain.allOf(63, 5)
    // the same storage trie twice, under each account, with a branch 63 nibbles down
    f.positions(Some(f.holder)) shouldBe f.positions(Some(f.twin))
    depths(Some(f.holder)) should contain.allOf(63, 3)
  }

  it should "serve every account range, from every origin and at every budget, as the hash store does" taggedAs
    UnitTest in {
      val f = new Stretched
      val sorted = f.accountKeys.sortBy(hex)
      for
        origin <- zero +: (f.accountKeys ++ strangers)
        limit <- Seq(max, origin, Stretched.key("3abcde", 0))
        budget <- budgets
      do
        withClue(s"origin ${hex(origin).take(8)}, limit ${hex(limit).take(8)}, budget $budget: ") {
          val served = f.both(SnapServer.serveAccountRange(20, f.root, origin, limit, budget, _))
          served.proof should not be empty
          // what is served starts at the first key at or after the origin
          served.accounts.headOption.map(_._1) shouldBe sorted.find(k => hex(k) >= hex(origin))
        }
      f.both(SnapServer.serveAccountRange(21, f.root, zero, max, plenty, _)).accounts.map(_._1) shouldBe sorted
    }

  it should "serve every storage range of the two accounts that hold the same trie as the hash store does" taggedAs
    UnitTest in {
      val f = new Stretched
      val sorted = f.slotKeys.sortBy(hex)
      for
        asked <- Seq(Seq(f.holder, f.twin), Seq(f.twin), Seq(Stretched.key("c0ff", 0), f.holder))
        origin <- zero +: (f.slotKeys ++ strangers)
        budget <- budgets
      do
        withClue(s"${asked.size} accounts, origin ${hex(origin).take(8)}, budget $budget: ") {
          f.both { storage =>
            SnapServer.serveStorageRanges(
              22,
              f.root,
              asked,
              origin,
              max,
              budget,
              storage,
              SnapServer.storageRootOf(f.root, storage)
            )
          }
        }
      val whole = f.both { storage =>
        SnapServer.serveStorageRanges(
          23,
          f.root,
          Seq(f.holder, f.twin),
          zero,
          max,
          plenty,
          storage,
          SnapServer.storageRootOf(f.root, storage)
        )
      }
      whole.slots.map(_.map(_._1)) shouldBe Seq(sorted, sorted)
    }

  it should "serve the node at every stored path, and nothing at a path that ends inside an extension" taggedAs
    UnitTest in {
      val f = new Stretched
      val everyAccountPath = f.paths(None).map(Seq(_))
      val everyStoragePath = Seq(f.holder, f.twin).map(account => account +: f.paths(Some(account)))
      val served = f.both(SnapServer.serveTrieNodes(24, f.root, everyAccountPath ++ everyStoragePath, plenty, _))
      // every stored node, each the node the store holds at that path
      served.nodes shouldBe f.paths(None).map(f.positions(None)) ++
        Seq(f.holder, f.twin).flatMap(account => f.paths(Some(account)).map(f.positions(Some(account))))

      // three nibbles into 3abcd, one nibble short of the branch 63 down, and in the storage trie one nibble into the
      // extension that starts at 7: each is the middle of an extension
      val inside = Seq(
        Seq(ByteString(HexPrefix.encode(Array[Byte](3, 0xa, 0xb), isLeaf = false))),
        Seq(ByteString(HexPrefix.encode(Array[Byte](1, 1, 1, 1) ++ new Array[Byte](58), isLeaf = false))),
        Seq(f.holder, ByteString(HexPrefix.encode(Array[Byte](7, 7), isLeaf = false)))
      )
      f.both(SnapServer.serveTrieNodes(25, f.root, inside, plenty, _)).nodes.map(_.size) shouldBe Seq(0, 0, 0)
    }

  it should "answer with nothing when its view goes stale, wherever in an extension's descent that is" taggedAs
    UnitTest in {
      given f: Stretched = new Stretched
      wherever(AccountRange(26, Seq.empty, Seq.empty))(SnapServer.serveAccountRange(26, f.root, zero, max, plenty, _))
      wherever(StorageRanges(27, Seq.empty, Seq.empty)) { storage =>
        SnapServer.serveStorageRanges(
          27,
          f.root,
          Seq(f.holder, f.twin),
          zero,
          max,
          plenty,
          storage,
          SnapServer.storageRootOf(f.root, storage)
        )
      }
    }

  // A serve holds one view across hundreds of reads, and a block is committed under it every few seconds. The server
  // took any read that failed as "no node here". For a node that is missing that is right. For a view the disk moved
  // away from it is a hole: the accounts under that node left out of the range, and proofs that cannot close over it,
  // sent to a peer that will find the response invalid and drop this node. A node that was read before the move is
  // still a node of the root asked about (each is checked against the hash its parent holds), but a part of a range is
  // not a range. So the answer is the empty response, "this state is not here", wherever in the serve the move falls.

  private def wherever[A](nothing: A)(serve: MptStorage => A)(using f: TwoStores): Unit =
    val counted = new StaleAfter(f.onPositions, healthy = Int.MaxValue)
    val whole = serve(counted)
    val reads = counted.readsAsked
    whole should not be nothing
    reads should be > 10
    (0 until reads).foreach { healthy =>
      withClue(s"the view went stale after $healthy of $reads reads: ") {
        serve(new StaleAfter(f.onPositions, healthy)) shouldBe nothing
      }
    }
    // a move after the last read of the serve changes nothing
    serve(new StaleAfter(f.onPositions, reads)) shouldBe whole

  "A view the disk moves away from during a serve" should
    "answer GetAccountRange with nothing, wherever the move falls, and never with a part of the range" taggedAs UnitTest in {
      given f: Fixture = new Fixture
      wherever(AccountRange(14, Seq.empty, Seq.empty))(
        SnapServer.serveAccountRange(14, f.hashed.root, zero, max, plenty, _)
      )
      // a truncated range reads its right proof last
      wherever(AccountRange(15, Seq.empty, Seq.empty))(
        SnapServer.serveAccountRange(15, f.hashed.root, zero, max, BigInt(400), _)
      )
    }

  it should "answer GetStorageRanges with nothing, the lookup of each account's storage root included" taggedAs
    UnitTest in {
      given f: Fixture = new Fixture
      wherever(StorageRanges(16, Seq.empty, Seq.empty)) { storage =>
        SnapServer.serveStorageRanges(
          16,
          f.hashed.root,
          Seq(alice, bob, carol).map(accountOf),
          zero,
          max,
          plenty,
          storage,
          SnapServer.storageRootOf(f.hashed.root, storage)
        )
      }
      wherever(StorageRanges(17, Seq.empty, Seq.empty)) { storage =>
        SnapServer.serveStorageRanges(
          17,
          f.hashed.root,
          Seq(accountOf(alice)),
          zero,
          max,
          BigInt(200),
          storage,
          SnapServer.storageRootOf(f.hashed.root, storage)
        )
      }
    }

  it should "answer GetTrieNodes with nothing, and not with empty nodes in the place of the ones it could not read" taggedAs
    UnitTest in {
      given f: Fixture = new Fixture
      val accountPaths = f.paths(None).take(8).map(Seq(_))
      val storagePaths = accountOf(carol) +: f.paths(Some(accountOf(carol))).take(6)
      wherever(TrieNodes(18, Seq.empty))(
        SnapServer.serveTrieNodes(18, f.hashed.root, accountPaths :+ storagePaths, plenty, _)
      )
    }
