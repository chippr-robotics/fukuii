package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.pathstate.CanonicalChainOnPositions
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage.CommitSettings
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage.Reader
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit.*
import com.chipprbots.ethereum.db.storage.pathstate.PositionKey
import com.chipprbots.ethereum.db.storage.pathstate.RetentionPolicy
import com.chipprbots.ethereum.db.storage.pathstate.StaleStateViewException
import com.chipprbots.ethereum.db.storage.pathstate.StateConfig
import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4a part 4: the state a node on the unified layout serves to a peer by its root (SNAP). With the
  * overlay it is the state of any canonical block of the served window, read behind the block the disk holds through
  * the changeset records: `BlockchainReader.stateForRoot`.
  */
class ServedRootWindowSpec extends AnyFlatSpec with Matchers:

  /** A block that is like no other of a chain: `n` is its own. It writes an account, and a storage trie. */
  private def block(n: Int): Seq[World => World] = Seq(
    world => world.saveAccount(dave, world.getAccount(dave).getOrElse(Account(0, 0)).copy(nonce = n, balance = n)),
    withSlots(alice, Seq((n % 50 + 1) -> (n + 1)))
  )

  /** Every stored node of the state at `root`, read through `storage` at its position. */
  private def reads(chain: CanonicalChainOnPositions, storage: MptStorage, root: ByteString): Unit =
    RawTrieWalk.statePositions(root.toArray, chain.hashed.nodes.load).foreach { (trie, rows) =>
      rows.foreach { (hp, node) =>
        val position = PositionKey(trie, hp)
        withClue(s"${position.show}: ") {
          ByteString(storage.forOwner(trie).getAt(position.path, kec256(node.toArray)).encode) shouldBe node
        }
      }
    }

  private def served(chain: CanonicalChainOnPositions, header: BlockHeader): Unit =
    withClue(s"the root of block ${header.number.value}: ") {
      val storage = chain.reader.stateForRoot(header.stateRoot.value)
      storage shouldBe defined
      reads(chain, storage.get, header.stateRoot.value)
    }

  private def notServed(chain: CanonicalChainOnPositions, root: ByteString): Unit =
    chain.reader.stateForRoot(root) shouldBe None

  "The state served for a root" should "be that of the canonical block which has it, the block the disk holds and those behind it" taggedAs
    UnitTest in {
      val chain = new CanonicalChainOnPositions()
      (1 to 6).foreach(n => chain.next(block(n)*))
      chain.headers.map(_.stateRoot.value).distinct should have size chain.headers.size
      chain.headers.foreach(served(chain, _))
      notServed(chain, ByteString(kec256("a root nobody holds".getBytes)))
      notServed(chain, EmptyRoot)
    }

  it should "reach back as far as the served window behind the best block, and no further" taggedAs UnitTest in {
    val chain = new CanonicalChainOnPositions()
    (1 to BlockchainReader.ServedRootWindow + 5).foreach(n => chain.next(block(n)*))
    val best = chain.head.number.value
    def at(number: BigInt): BlockHeader = chain.headers.find(_.number.value == number).get
    // the oldest block of the window, and the block before it
    served(chain, at(best - BlockchainReader.ServedRootWindow))
    notServed(chain, at(best - BlockchainReader.ServedRootWindow - 1).stateRoot.value)
    served(chain, chain.head)

    // the window is behind the best block: when that is ahead of the block the disk holds, the window has moved on
    chain.appState.putBestBlockNumber(best + 1).commit()
    notServed(chain, at(best - BlockchainReader.ServedRootWindow).stateRoot.value)
    served(chain, at(best - BlockchainReader.ServedRootWindow + 1))

    // and when the best block's number is behind the block the disk holds, that block and those under it are served
    // still, and the window ends 128 behind the number
    chain.appState.putBestBlockNumber(best - 2).commit()
    Seq(best, best - 1, best - 2, best - 3).foreach(number => served(chain, at(number)))
    served(chain, at(best - 2 - BlockchainReader.ServedRootWindow))
    notServed(chain, at(best - 2 - BlockchainReader.ServedRootWindow - 1).stateRoot.value)
  }

  it should "not be served where the records no longer lead back to its block, and be read at the newest block that has the root" taggedAs
    UnitTest in {
      // the records of three blocks are kept
      val chain = new CanonicalChainOnPositions(
        CommitSettings(retention = new RetentionPolicy(StateConfig.Mode.Basic, (_, _) => 3))
      )
      (1 to 2).foreach(n => chain.next(block(n)*))
      val older = chain.next(block(3)*)
      // a block that changes nothing: it has its parent's state root
      val newer = chain.next()
      newer.stateRoot shouldBe older.stateRoot
      (5 to 7).foreach(n => chain.next(block(n)*))

      // three blocks behind the head are led back to, and `newer` is the third
      chain.headers.takeRight(4).foreach(served(chain, _))
      newer shouldBe chain.headers(chain.headers.size - 4)
      // so the root the two share is served, at the newer: the older is one record too far
      a[StateFault] should be thrownBy chain.state.readOnlyBehind(chain.refOf(older), Reader.Peer)
      served(chain, older)
      // and what is behind it is not
      chain.headers.take(3).foreach(header => notServed(chain, header.stateRoot.value))
    }

  it should "never be that of an executed block kept beside the chain, in the index by number or not" taggedAs
    UnitTest in {
      val chain = new CanonicalChainOnPositions()
      (1 to 4).foreach(n => chain.next(block(n)*))
      val onHead = chain.beside(chain.head, salt = 1)(block(70)*)
      // a sibling of the head, which the index by number is made to name at the head's height: a side branch's
      // headers were written under the chain
      val parent = chain.headers(chain.headers.size - 2)
      val sibling = chain.beside(parent, salt = 2)(block(71)*)
      sibling.number shouldBe chain.head.number
      val under = chain.beside(chain.headers(chain.headers.size - 3), salt = 3)(block(72)*)
      under.number shouldBe parent.number
      chain.hashesByNumber.put(under.number.value, under.hash.value).commit()

      // each is a state this node holds, and can read
      Seq(onHead, sibling, under).foreach { header =>
        chain.state.forwardLayer(header.hash.value) should not be empty
      }
      Seq(onHead, sibling, under).foreach(header => notServed(chain, header.stateRoot.value))
      // the chain's own blocks are, the one whose place in the index was taken among them... which is now not found
      // by its number, and so not served: what is served is found on the canonical chain, or not at all
      served(chain, chain.head)
      notServed(chain, parent.stateRoot.value)
      served(chain, chain.headers(chain.headers.size - 3))
    }

  it should "follow the chain: a new block's root is served at once, and a state handed out earlier goes stale, never wrong" taggedAs
    UnitTest in {
      val chain = new CanonicalChainOnPositions(maxViewAgeMoves = 3)
      (1 to 3).foreach(n => chain.next(block(n)*))
      val behind = chain.headers(2)
      val handedOut = chain.reader.stateForRoot(behind.stateRoot.value).get
      reads(chain, handedOut, behind.stateRoot.value)

      // as many blocks as a view outlives: it reads its state still, and the new head's root is served
      (4 to 6).foreach { n =>
        val made = chain.next(block(n)*)
        served(chain, made)
        reads(chain, handedOut, behind.stateRoot.value)
      }
      // one more, and what was handed out says so; asked for again, the state is handed out again
      chain.next(block(7)*)
      a[StaleStateViewException] should be thrownBy reads(chain, handedOut, behind.stateRoot.value)
      served(chain, behind)

      // The best block's number was written ahead of its state, and then the disk catches up: the block the disk
      // holds is another, and the best block's number is what it was. The new block's root is served all the same.
      chain.appState.putBestBlockNumber(chain.head.number.value + 1).commit()
      served(chain, chain.head)
      val caughtUp = chain.next(block(8)*)
      chain.reader.getBestBlockNumber shouldBe caughtUp.number.value
      served(chain, caughtUp)
    }
