package com.chipprbots.ethereum.mpt

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pathstate.StaleStateViewException
import com.chipprbots.ethereum.mpt.MptVisitors.PathTrackingLeafWalkVisitor
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C2b (T055, T061): the walkers of a trie on a position store.
  *
  * A walker that resolves a hash node has to say where that node sits, or a position store cannot find it. The leaf
  * walk tracks the nibbles it has come down, to give each leaf its key; it now reads each node at them. The shard
  * enumerator carries the prefix of each shard; it reads at it. The state here has what hashed keys almost never give:
  * extensions 4 and 62 nibbles long, and leaves and a branch that sit inside their parents, which the path runs through
  * though nothing is read there.
  */
class WalkersPositionSpec extends AnyFlatSpec with Matchers:

  import PathStateTestKit.*

  private def hex(bytes: ByteString): String = bytes.map("%02x".format(_)).mkString

  final private class Fixture:
    val chosen = new ChosenState
    val state = new TwoWays(chosen.root, chosen.nodes, chosen.load)

    /** The tries of the state: the account trie, and each account's storage trie, with its root and its keys. */
    val tries: Seq[(Option[ByteString], ByteString, Seq[ByteString])] =
      (None, chosen.root, chosen.accounts.map(_._1)) +:
        chosen.accounts.collect {
          case (account, held) if state.positions.contains(Some(account)) =>
            (Some(account), held.storageRoot.value, chosen.slotKeys)
        }

  /** The keys of the leaves a walk of `root` over `nodes` comes to, in order, and the prefixes it could not read. */
  private def walk(nodes: MptStorage, root: ByteString, tolerant: Boolean): (Seq[ByteString], Seq[ByteString]) =
    val keys = mutable.ArrayBuffer.empty[ByteString]
    val holes = mutable.ArrayBuffer.empty[ByteString]
    val onHole = Option.when(tolerant)((prefix: ByteString) => holes += prefix: Unit)
    MptTraversals.dispatch(
      HashNode(root.toArray),
      new PathTrackingLeafWalkVisitor(nodes, ByteString.empty, (key, _) => keys += key, onHole)
    )
    (keys.toSeq, holes.toSeq)

  "The leaf walk, on a position store" should
    "come to every leaf of every trie with its key, through extensions and through nodes inside their parents" taggedAs
    UnitTest in {
      val f = new Fixture
      f.tries should have size 4 // the account trie, and the storage of holder, twin and small
      for (trie, root, keys) <- f.tries do
        withClue(s"trie of ${trie.fold("the accounts")(hex)}: ") {
          val (onPositions, _) = walk(f.state.onPositions.forOwner(trie), root, tolerant = false)
          onPositions shouldBe keys.sortBy(hex)
          onPositions shouldBe walk(f.state.onHashes.forOwner(trie), root, tolerant = false)._1
        }
    }

  // A walk that goes on past what it cannot read has to say what that was. It reports the nibbles to the node: on a
  // position store those are the row it asked for, and found empty.
  it should "report a row that is not there by the very position it read, and go on over the rest" taggedAs UnitTest in {
    val f = new Fixture
    for
      (trie, root, keys) <- f.tries
      lost <- f.state.stored(trie).drop(1) // every stored position but the root
    do
      val path = ByteString(f.state.path(lost))
      withClue(s"trie of ${trie.fold("the accounts")(hex)}, row at ${hex(path)}: ") {
        val (reached, holes) = walk(f.state.positionsWithout(lost).forOwner(trie), root, tolerant = true)
        holes shouldBe Seq(path)
        // every leaf but those under the lost row, whose keys start with its path
        def under(key: ByteString): Boolean = HexPrefix.bytesToNibbles(key.toArray).startsWith(path.toArray)
        reached shouldBe keys.sortBy(hex).filterNot(under)
        keys.exists(under) shouldBe true
        // a walk that does not go on ends there
        a[MerklePatriciaTrie.MissingNodeException] should be thrownBy walk(
          f.state.positionsWithout(lost).forOwner(trie),
          root,
          tolerant = false
        )
      }
  }

  it should "end when the state moves under it, and not count that as a row that is not there" taggedAs UnitTest in {
    val f = new Fixture
    val counted = new StaleAfter(f.state.onPositions, healthy = Int.MaxValue)
    walk(counted.forOwner(None), f.chosen.root, tolerant = true)
    val reads = counted.readsAsked
    reads should be > 5
    (0 until reads).foreach { healthy =>
      withClue(s"the state moved after $healthy of $reads reads: ") {
        a[StaleStateViewException] should be thrownBy walk(
          new StaleAfter(f.state.onPositions, healthy).forOwner(None),
          f.chosen.root,
          tolerant = true
        )
      }
    }
  }

  "The shard enumerator, on a position store" should
    "split the account trie as it does on the hash store, each shard's walk reaching its own leaves" taggedAs UnitTest in {
      val f = new Fixture
      for depth <- 1 to 3 do
        withClue(s"depth $depth: ") {
          val onPositions = ShardEnumerator.enumShards(f.chosen.root, f.state.onPositions, depth)
          val onHashes = ShardEnumerator.enumShards(f.chosen.root, f.state.onHashes, depth)
          onPositions.map(shard => shard.pathPrefix -> ByteString(shard.root.hash)) shouldBe
            onHashes.map(shard => shard.pathPrefix -> ByteString(shard.root.hash))

          // each shard is walked from its own prefix, and together they are the trie
          val accounts = f.state.onPositions.forOwner(None)
          val reached = onPositions.flatMap { shard =>
            val keys = mutable.ArrayBuffer.empty[ByteString]
            MptTraversals.dispatch(
              shard.root,
              new PathTrackingLeafWalkVisitor(accounts, shard.pathPrefix, (key, _) => keys += key)
            )
            keys.foreach(key =>
              HexPrefix.bytesToNibbles(key.toArray).startsWith(shard.pathPrefix.toArray) shouldBe true
            )
            keys
          }
          reached.sortBy(hex) shouldBe f.chosen.accounts.map(_._1).sortBy(hex)
        }
    }

  it should "leave a subtree whose top row is not there as one shard, to be met by its walk" taggedAs UnitTest in {
    val f = new Fixture
    // the row at "1": the extension over the two accounts that part 62 nibbles further down
    val lost = f.state.stored(None).find(position => f.state.path(position).toSeq == Seq[Byte](1)).get
    val onPositions = ShardEnumerator.enumShards(f.chosen.root, f.state.positionsWithout(lost), 2)
    val onHashes = ShardEnumerator.enumShards(f.chosen.root, f.state.hashesWithout(lost), 2)
    onPositions.map(shard => shard.pathPrefix -> ByteString(shard.root.hash)) shouldBe
      onHashes.map(shard => shard.pathPrefix -> ByteString(shard.root.hash))
    onPositions.find(_.pathPrefix == ByteString(1.toByte)).map(_.root) shouldBe Some(
      HashNode(f.state.hashAt(lost).toArray)
    )
    // with no root there is no trie to split
    a[MerklePatriciaTrie.MissingNodeException] should be thrownBy ShardEnumerator.enumShards(
      f.chosen.root,
      f.state.positionsWithout(f.state.stored(None).head),
      1
    )
  }
