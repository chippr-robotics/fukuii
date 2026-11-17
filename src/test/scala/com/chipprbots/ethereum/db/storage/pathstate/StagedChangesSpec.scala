package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import scala.util.Random

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C3b (T076, T077, T079, T080 at the layer): what a block's tries write, as one change for each
  * position of the state.
  *
  * A block is run twice from one parent state: on the staged storage of a position store, and on a hash store. The hash
  * store's nodes say what the state is afterwards, by a walk of the raw RLP that knows nothing of the code under test.
  * The staged layer's diff is then held to four things:
  *
  *   - '''forward''': the parent's rows, with every `next` applied, are exactly the rows of the new state;
  *   - '''reverse''': those rows, with every `prior` applied, are exactly the parent's rows again;
  *   - '''the prior is the parent's''': each prior is the parent's row at that position, or none where it had none;
  *   - the diff is one a writer may write ([[BlockDiff.validate]]), and the block's state root is the hash store's.
  */
class StagedChangesSpec extends AnyFlatSpec with Matchers:

  import PathStateTestKit.*

  private type Tx = World => World
  private type Rows = Map[PositionKey, ByteString]

  private def flat(positions: Map[Option[ByteString], Map[ByteString, ByteString]]): Rows =
    positions.flatMap((trie, rows) => rows.map((row, node) => PositionKey(trie, row) -> node))

  /** A block run from the state `HashedState` builds, on both stores. */
  final private class Run(transactions: Seq[Tx]):
    val hashed = new HashedState
    private val anchor = block(1, hashed.root)
    private val source = EphemDataSource()
    val parentRows: Rows = flat(hashed.positions())
    seed(source, hashed, anchor)
    private val view = new PathStateStorage(new PathNodeStorage(source), new AppStateStorage(source)).view(anchor)

    val changes = new StagedChanges
    val staged: MptStorage = PathMptStorage.staged(view, changes)

    /** The worlds after each transaction, each persisted before the next begins, as execution does. */
    private def run(start: World): Seq[World] =
      transactions.scanLeft(start)((world, tx) => InMemoryWorldStateProxy.persistState(tx(world))).tail

    val onPositions: Seq[World] = run(worldOver(staged, hashed.root, hashed.codes))
    val onHashes: Seq[World] = run(hashed.world(hashed.root))
    val root: ByteString = onHashes.last.stateRootHash

    val after: Rows = flat(RawTrieWalk.statePositions(root.toArray, hashed.nodes.load))
    val diff: Map[PositionKey, Change] =
      changes.diff(owner => parentRows.iterator.filter((position, _) => position.owner.contains(owner)))

    def check(): Unit =
      onPositions.map(_.stateRootHash) shouldBe onHashes.map(_.stateRootHash)
      val forward = diff.foldLeft(parentRows) { case (rows, (position, change)) =>
        change.next.fold(rows - position)(node => rows + (position -> node))
      }
      withClue("forward: ")(forward shouldBe after)
      val reverse = diff.foldLeft(after) { case (rows, (position, change)) =>
        change.prior.fold(rows - position)(node => rows + (position -> node))
      }
      withClue("reverse: ")(reverse shouldBe parentRows)
      diff.foreach((position, change) =>
        withClue(s"prior at ${position.show}: ")(change.prior shouldBe parentRows.get(position))
      )
      BlockDiff(
        blockHash = ByteString(kec256("block".getBytes)),
        number = 2,
        parentHash = anchor.hash,
        preStateRoot = hashed.root,
        postStateRoot = root,
        changes = diff
      ).validate shouldBe empty

  private def slots(address: Address, written: (Int, Int)*): Tx = withSlots(address, written)

  /** The account's nonce and balance, its storage and code kept: execution never writes an account's storage root but
    * by persisting its storage trie, or by deleting or creating the account.
    */
  private def account(address: Address, nonce: Int, balance: Int): Tx = world =>
    world.saveAccount(
      address,
      world.getAccount(address).getOrElse(Account(0, 0)).copy(nonce = nonce, balance = balance)
    )
  private def delete(address: Address): Tx = _.deleteAccount(address)
  private val eve = Address(0xe7e)

  "A block's changes" should "be one entry for each position, however often its transactions rewrite it" taggedAs UnitTest in {
    val run = new Run(
      Seq(
        slots(alice, 1 -> 5, 2 -> 6).andThen(account(dave, 8, 8)),
        slots(alice, 1 -> 7, 41 -> 1).andThen(account(crowd.head, 1, 1)),
        slots(alice, 1 -> 9).andThen(slots(carol, 3 -> 3)).andThen(account(dave, 9, 9))
      )
    )
    run.check()
    // the hot positions were written by every transaction, and are there once
    run.diff.keys.count(_ == PositionKey.AccountRoot) shouldBe 1
    run.diff(PositionKey.AccountRoot).prior shouldBe run.parentRows.get(PositionKey.AccountRoot)
  }

  // Rule 1: a node the block's first transaction created is named as an old node when its second replaces it. That is
  // not what the parent's state held there: nothing was.
  it should "give a position the block created and then rewrote no prior, so that a reverse removes it" taggedAs
    UnitTest in {
      val run = new Run(
        Seq(account(eve, 0, 1).andThen(slots(eve, 1 -> 1, 2 -> 2)), slots(eve, 1 -> 3, 3 -> 4), slots(eve, 2 -> 0))
      )
      run.check()
      val evesRows = run.diff.filter((position, _) => position.owner.contains(owner(eve)))
      evesRows should not be empty
      all(evesRows.values.map(_.prior)) shouldBe None
    }

  it should "list a position that held a node and holds none: a slot zeroed, a trie emptied, an account gone" taggedAs
    UnitTest in {
      val run = new Run(
        Seq(
          // carol's storage, slot by slot, until none is left: its root row vanishes too (rule 7)
          slots(carol, otherSlots.take(12).map((k, _) => k -> 0)*),
          slots(carol, otherSlots.drop(12).map((k, _) => k -> 0)*),
          delete(crowd(3)).andThen(delete(crowd(4)))
        )
      )
      run.check()
      val carols = run.diff.filter((position, _) => position.owner.contains(owner(carol)))
      carols.values.map(_.next).toSet shouldBe Set(None)
      carols.keySet shouldBe run.parentRows.keySet.filter(_.owner.contains(owner(carol)))
    }

  // Rule 6. alice's account is removed with its storage; bob holds the same storage and keeps his.
  "A storage trie replaced whole" should "lose every row the parent's state held for it, and no other account's" taggedAs
    UnitTest in {
      val run = new Run(Seq(slots(alice, 1 -> 5), delete(alice), account(dave, 1, 1)))
      run.changes.resetOwners shouldBe Seq(owner(alice))
      run.check()
      val alices = run.diff.filter((position, _) => position.owner.contains(owner(alice)))
      alices.keySet shouldBe run.parentRows.keySet.filter(_.owner.contains(owner(alice)))
      alices.values.map(_.next).toSet shouldBe Set(None)
      run.diff.keys.exists(_.owner.contains(owner(bob))) shouldBe false
    }

  // T080 (forge): tx1 writes, tx2 destroys, tx3 creates the account again and writes over positions the dead trie
  // occupied. The new trie starts empty, so its first write there names no old node; with a prior of none a reverse
  // would delete the row where the dead trie's node has to come back.
  it should "give a trie created again in the same block the dead trie's rows as priors, wherever it writes" taggedAs
    UnitTest in {
      val run = new Run(
        Seq(
          slots(alice, 1 -> 5, 2 -> 6),
          delete(alice),
          account(alice, 0, 1).andThen(slots(alice, manySlots.take(20).map((k, v) => k -> (v + 1))*)),
          slots(alice, 3 -> 0, 50 -> 50)
        )
      )
      run.check()
      // it wrote over dead positions: some entry of alice's has both a prior and a next
      run.diff.exists((position, change) =>
        position.owner.contains(owner(alice)) && change.prior.isDefined && change.next.isDefined
      ) shouldBe true
    }

  it should "leave nothing of a trie the block itself created and then replaced" taggedAs UnitTest in {
    val run =
      new Run(Seq(account(eve, 0, 1).andThen(slots(eve, 1 -> 1, 2 -> 2, 3 -> 3)), delete(eve), account(dave, 2, 2)))
    run.check()
    run.diff.keys.exists(_.owner.contains(owner(eve))) shouldBe false
  }

  it should "be replaced twice in one block, each time from where the last left it" taggedAs UnitTest in {
    val run = new Run(
      Seq(
        delete(carol),
        account(carol, 0, 1).andThen(slots(carol, 3 -> 9, 4 -> 9)),
        delete(carol),
        account(carol, 0, 2).andThen(slots(carol, 6 -> 1))
      )
    )
    run.changes.resetOwners shouldBe Seq(owner(carol))
    run.check()
  }

  // T076: three readers of a block read a world as it was before a later one was persisted into the same layer (the
  // access list builder's `before`, the request calls, index 0). A layer that kept the latest node at each position
  // would hand them the newer node under the older hash.
  "An older world of the same block" should "read every node it had, after newer ones were persisted, a reset included" taggedAs
    UnitTest in {
      val run =
        new Run(
          Seq(slots(alice, 1 -> 5, 2 -> 6).andThen(account(dave, 8, 8)), slots(alice, 1 -> 7, 2 -> 0), delete(alice))
        )
      run.onPositions.zip(run.onHashes).foreach { (staged, hashedWorld) =>
        // a fresh world at that older root, over the staged storage as it is at the end of the block
        val older = worldOver(run.staged, staged.stateRootHash, run.hashed.codes)
        Seq(alice, bob, carol, dave).foreach { address =>
          older.getAccount(address) shouldBe hashedWorld.getAccount(address)
          (1 to 45).foreach(slot =>
            older.getStorage(address).load(BigInt(slot)) shouldBe hashedWorld.getStorage(address).load(BigInt(slot))
          )
        }
      }
      // and the parent's state too: its rows are untouched until the block commits
      val parent = worldOver(run.staged, run.hashed.root, run.hashed.codes)
      manySlots.foreach((k, v) => parent.getStorage(alice).load(BigInt(k)) shouldBe BigInt(v))
    }

  // Rule 6's bound: a dead trie with too many rows to list. Nothing can be said of what the block changed under that
  // owner, and what it leaves there is still known.
  "A replaced trie whose rows are not listed" should "have no entry in the block's changes, and its new rows apart" taggedAs
    UnitTest in {
      val run = new Run(
        Seq(
          slots(alice, 1 -> 5, 2 -> 6),
          delete(alice),
          account(alice, 0, 1).andThen(slots(alice, 1 -> 2, 2 -> 3, 77 -> 7)),
          slots(carol, 3 -> 3).andThen(delete(crowd(5)))
        )
      )
      def rowsOf(o: ByteString) = run.parentRows.filter((position, _) => position.owner.contains(o))
      // an account removed with no storage is a replaced trie too, with no row to list
      run.changes.resetOwners shouldBe Seq(owner(alice), owner(crowd(5)))
      val listed = run.changes.finalise(o => Some(rowsOf(o)))
      listed.unlisted shouldBe empty
      listed.unrecorded shouldBe empty
      listed.changes shouldBe run.diff

      val unlisted = run.changes.finalise(o => Option.when(o != owner(alice))(rowsOf(o)))
      unlisted.unlisted shouldBe Seq(owner(alice))
      unlisted.changes.keys.exists(_.owner.contains(owner(alice))) shouldBe false
      // every other trie's entries are what they were
      unlisted.changes shouldBe run.diff.filterNot((position, _) => position.owner.contains(owner(alice)))
      // and what the block leaves under the owner is the trie created again, whole
      unlisted.unrecorded shouldBe run.after.filter((position, _) => position.owner.contains(owner(alice)))
      unlisted.unrecorded should not be empty
    }

  // beacon: the two state roots would still check, and the rows under the root would be a mix of both worlds.
  "A world that does not follow the last one persisted" should "be refused by the layer it is persisted on" taggedAs
    UnitTest in {
      val hashed = new HashedState
      val anchor = block(1, hashed.root)
      val source = EphemDataSource()
      seed(source, hashed, anchor)
      val staged = new PathStateStorage(new PathNodeStorage(source), new AppStateStorage(source)).stage(anchor)
      val start = worldOver(staged.storage, hashed.root, hashed.codes)

      val first = InMemoryWorldStateProxy.persistState(account(dave, 1, 1)(start))
      // a second world from the same start: a retry on a layer that was not dropped
      val refused = the[IllegalStateException] thrownBy InMemoryWorldStateProxy.persistState(account(dave, 2, 2)(start))
      refused.getMessage should (include("does not follow").and(include("the account trie")))
      // in a storage trie as well: alice's, changed twice from one start
      val one = InMemoryWorldStateProxy.persistState(slots(alice, 1 -> 5)(first))
      an[IllegalStateException] should be thrownBy InMemoryWorldStateProxy.persistState(slots(alice, 1 -> 6)(first))
      // the line itself goes on
      InMemoryWorldStateProxy.persistState(slots(alice, 1 -> 7).andThen(account(dave, 3, 3))(one)).stateRootHash should
        not be one.stateRootHash

      // A storage trie on its own, with no account trie to refuse first: two tries from one empty start.
      import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
      import com.chipprbots.ethereum.mpt.byteStringSerializer
      val empty = MerklePatriciaTrie[ByteString, ByteString](staged.storage.forOwner(Some(owner(eve))))
      val grown = empty.put(ByteString(1), ByteString(1))
      val again = the[IllegalStateException] thrownBy empty.put(ByteString(2), ByteString(2))
      again.getMessage should (include("does not follow").and(include("the storage trie of")))
      // from where the first one left it, it is the same line
      grown.put(ByteString(2), ByteString(2)).get(ByteString(1)) shouldBe Some(ByteString(1))
    }

  // ─── One trie, with keys that are chosen ──────────────────────────────────────────────────────────────────────
  //
  // Keys that are hashes give a bushy trie whose nodes are all stored. Two rules need other shapes (rule 4): a node
  // under 32 bytes of RLP lives inside its parent and has no row, so a leaf that grows past that gets a row it did not
  // have, and one that shrinks loses its own; and a trie's root has a row at any size (rule 7).

  /** One storage trie, changed step by step on the staged storage and on a hash store. */
  final private class TrieRun(parent: Seq[(ByteString, ByteString)], steps: Seq[RawTrie => RawTrie]):
    import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
    import com.chipprbots.ethereum.mpt.PositionTestKit.ReferenceStore
    import com.chipprbots.ethereum.mpt.byteStringSerializer
    private val trieOwner = owner(alice)
    private val reference = new ReferenceStore
    private def build(over: MptStorage): RawTrie =
      parent.foldLeft(MerklePatriciaTrie[ByteString, ByteString](over))((trie, entry) => trie.put(entry._1, entry._2))
    private val parentTrie = build(reference)
    reference.persist()
    private def rowsOf(root: Array[Byte]): Rows =
      RawTrieWalk.storedPositions(root, reference.load).map((row, node) => PositionKey(Some(trieOwner), row) -> node)
    val parentRows: Rows = rowsOf(parentTrie.getRootHash)

    private val source = EphemDataSource()
    private val anchor = block(1, EmptyRoot)
    seedPositions(source, Map(Option(trieOwner) -> parentRows.map((position, node) => position.hp -> node)), anchor)
    private val view = new PathStateStorage(new PathNodeStorage(source), new AppStateStorage(source)).view(anchor)
    val changes = new StagedChanges
    private val staged = PathMptStorage.staged(view, changes).forOwner(Some(trieOwner))

    private val onPositions =
      steps.foldLeft(MerklePatriciaTrie[ByteString, ByteString](parentTrie.getRootHash, staged))((trie, step) =>
        step(trie)
      )
    private val onHashes = steps.foldLeft(parentTrie)((trie, step) => step(trie))
    reference.persist()
    val after: Rows = rowsOf(onHashes.getRootHash)
    val diff: Map[PositionKey, Change] = changes.diff(_ => Iterator.empty)

    def check(): Unit =
      ByteString(onPositions.getRootHash) shouldBe ByteString(onHashes.getRootHash)
      val forward = diff.foldLeft(parentRows) { case (rows, (position, change)) =>
        change.next.fold(rows - position)(node => rows + (position -> node))
      }
      withClue("forward: ")(forward shouldBe after)
      val reverse = diff.foldLeft(after) { case (rows, (position, change)) =>
        change.prior.fold(rows - position)(node => rows + (position -> node))
      }
      withClue("reverse: ")(reverse shouldBe parentRows)
      diff.foreach((position, change) =>
        withClue(s"prior at ${position.show}: ")(change.prior shouldBe parentRows.get(position))
      )
      // both sides of every entry are stored nodes: 32 bytes or more, or at the root
      diff.foreach { (position, change) =>
        (change.prior.toSeq ++ change.next.toSeq).foreach { node =>
          withClue(s"${position.show}: ")(
            (node.length >= BlockDiff.StoredNodeLength || position.hp == PositionKey.AccountRoot.hp) shouldBe true
          )
        }
      }

  private type RawTrie = com.chipprbots.ethereum.mpt.MerklePatriciaTrie[ByteString, ByteString]
  private val small = ByteString(1.toByte)
  private val big = ByteString(Array.fill[Byte](40)(7))
  // 7777…01 and 7777…02 part at their last nibble: with small values the two leaves sit inside their branch, and the
  // branch inside the extension above it
  private val twins = Seq(chosenKey("7777", 1), chosenKey("7777", 2))
  private val others = Seq(chosenKey("7778", 0), chosenKey("80", 0), chosenKey("8f", 0xff))
  private def put(key: ByteString, value: ByteString): RawTrie => RawTrie = _.put(key, value)
  private def remove(key: ByteString): RawTrie => RawTrie = _.remove(key)

  "A node that lives inside its parent" should "have no entry of its own, whatever is done to it" taggedAs UnitTest in {
    val run = new TrieRun(
      (twins ++ others).map(_ -> small),
      Seq(put(twins(0), ByteString(2.toByte)), put(twins(1), ByteString(3.toByte)), put(twins(0), ByteString(4.toByte)))
    )
    run.check()
    // the two leaves and their branch were rewritten three times, inside the extension: its row is the deepest entry
    run.diff.keys.map(_.path.length).max should be < 63
  }

  it should "get a row with no prior when it grows to a stored node, and lose it when it shrinks back" taggedAs UnitTest in {
    // grows: the leaf, and the branch that now holds its hash, have rows where the parent had none
    val grown = new TrieRun((twins ++ others).map(_ -> small), Seq(put(twins(0), big)))
    grown.check()
    val deep = grown.diff.filter((position, _) => position.path.length >= 63)
    deep should have size 2
    all(deep.values.map(_.prior)) shouldBe None
    all(deep.values.map(_.next.isDefined)) shouldBe true

    // shrinks: the rows the parent had there are gone, with what they held as priors
    val shrunk =
      new TrieRun(twins.map(_ -> big) ++ others.map(_ -> small), Seq(put(twins(0), small), put(twins(1), small)))
    shrunk.check()
    val gone = shrunk.diff.filter((position, _) => position.path.length >= 63)
    gone should have size 3
    all(gone.values.map(_.next)) shouldBe None
    all(gone.values.map(_.prior.isDefined)) shouldBe true

    // grows and shrinks back inside one block: nothing is left of it down there
    val both = new TrieRun((twins ++ others).map(_ -> small), Seq(put(twins(0), big), put(twins(0), small)))
    both.check()
    both.diff.keys.exists(_.path.length >= 63) shouldBe false
  }

  "The root of a trie" should "have a row at any size, and none when the trie is empty" taggedAs UnitTest in {
    // keys of one byte: the whole trie is a node of a few bytes
    val a = ByteString(0x12.toByte)
    val b = ByteString(0x34.toByte)
    val root = PositionKey(Some(owner(alice)), PositionKey.AccountRoot.hp)

    val rewritten = new TrieRun(Seq(a -> small), Seq(put(a, ByteString(9.toByte)), put(b, small)))
    rewritten.check()
    rewritten.parentRows(root).length should be < BlockDiff.StoredNodeLength
    rewritten.diff.keySet shouldBe Set(root)
    rewritten.diff(root).prior shouldBe rewritten.parentRows.get(root)

    val emptied = new TrieRun(Seq(a -> small, b -> small), Seq(remove(a), remove(b)))
    emptied.check()
    emptied.diff(root) shouldBe Change(prior = emptied.parentRows.get(root), next = None)

    val created = new TrieRun(Nil, Seq(put(a, small)))
    created.check()
    created.diff(root).prior shouldBe None
  }

  "Any block" should "have a diff that takes the parent's rows to the new state's and back" taggedAs UnitTest in {
    val people = Seq(alice, bob, carol, dave, eve) ++ crowd.take(6)
    (1 to 40).foreach { seed =>
      val random = new Random(seed)
      def someone = people(random.nextInt(people.size))
      def tx: Tx =
        (1 to (1 + random.nextInt(4)))
          .map { _ =>
            random.nextInt(10) match
              case 0 | 1 => delete(someone)
              case 2     => account(someone, random.nextInt(5), 1 + random.nextInt(100))
              case 3     => slots(someone, (1 to 6).map(_ => (1 + random.nextInt(45)) -> 0)*)
              case _ =>
                slots(someone, (1 to (1 + random.nextInt(6))).map(_ => (1 + random.nextInt(45)) -> random.nextInt(9))*)
          }
          .reduce(_ andThen _)
      withClue(s"seed $seed: ")(new Run(Seq.fill(2 + random.nextInt(5))(tx)).check())
    }
  }
