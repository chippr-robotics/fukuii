package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.components.DataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pathstate.StateUnavailable
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.db.storage.pruning.InMemoryPruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProof
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C2b: the seam that hands a reader the state of a block.
  *
  * With the in-development guard off, which is how the node ships, `stateAt` and `scratchStateAt` hand out the
  * read-only store every reader could already take. Some of the readers that move onto the seam read through the store
  * of a block number until now (`getBackingStorage(n)`), so this pins, in each pruning mode, that the answers are the
  * ones that store gives: the account, its proof, a slot, its proof.
  *
  * With the guard on they hand out a view of the position store, which holds the state of one block.
  */
class BlockchainReaderSeamSpec extends AnyFlatSpec with Matchers:

  import PathStateTestKit.*

  private trait Given(source: EphemDataSource, mode: PruningMode)
      extends DataSourceComponent
      with Storages.PruningModeComponent:
    override val dataSource: EphemDataSource = source
    override val pruningMode: PruningMode = mode

  private class Node(source: EphemDataSource, mode: PruningMode, guard: Boolean)
      extends Given(source, mode)
      with Storages.DefaultStorages:
    override protected def statePathLive: Boolean = guard
    // the node's best block is block 1, the block every header of this spec is at
    storages.appStateStorage.putBestBlockNumber(1).commit()
    lazy val reader: BlockchainReader = BlockchainReader(storages)
    lazy val blockchain: BlockchainImpl = BlockchainImpl(storages, reader)

  private def headerOf(number: Int, stateRoot: ByteString): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(number), stateRoot = TrieRoot(stateRoot))

  private def refOf(header: BlockHeader): BlockRef =
    BlockRef(header.hash.value, header.number.value, header.stateRoot.value)

  /** The state of [[PathStateTestKit.HashedState]], written through `storage` as a block's execution writes it. */
  private def writeThrough(storage: MptStorage, node: Node): ByteString =
    val empty = worldOver(storage, EmptyRoot, node.storages.evmCodeStorage)
    val peopled = crowd.zipWithIndex.foldLeft(empty) { case (world, (address, i)) =>
      world.saveAccount(address, Account(i, 1000 + i))
    }
    val full = withSlots(carol, otherSlots)(withSlots(bob, manySlots)(withSlots(alice, manySlots)(peopled)))
      .saveAccount(dave, Account(7, 7))
    InMemoryWorldStateProxy.persistState(full).stateRootHash

  private def accountTrie(storage: MptStorage, root: ByteString): MerklePatriciaTrie[Address, Account] =
    MerklePatriciaTrie[Address, Account](root.toArray, storage)(using
      Address.hashedAddressEncoder,
      Account.accountSerializer
    )

  private def encoded(nodes: Option[Vector[MptNode]]): Option[Vector[ByteString]] =
    nodes.map(_.map(node => ByteString(node.encode)))

  Seq[(String, PruningMode)](
    "archive" -> ArchivePruning,
    "basic" -> BasicPruning(64),
    "inmemory" -> InMemoryPruning(64)
  ).foreach { case (name, mode) =>
    s"The seam with the guard off, under $name pruning" should
      "answer an account, its proof, a slot and its proof as the store of the block number does" taggedAs UnitTest in {
        val node = new Node(EphemDataSource(), mode, guard = false)
        val root = writeThrough(node.storages.stateStorage.getBackingStorage(1), node)
        val header = headerOf(1, root)

        // what the readers took until now: the store of the block number, and block 0's for a storage trie
        val byNumber = accountTrie(node.storages.stateStorage.getBackingStorage(1), root)
        val storageByRoot = (storageRoot: ByteString) =>
          EthereumUInt256Mpt.storageMpt(storageRoot, node.storages.stateStorage.getBackingStorage(0))

        (Seq(alice, bob, carol, dave, Address(0xdead)) ++ crowd.take(5)).foreach { address =>
          withClue(s"account $address: ") {
            node.reader.accountAt(header, address) shouldBe byNumber.get(address)
            encoded(node.reader.accountProofIn(node.reader.stateAt(header), header, address)) shouldBe
              encoded(byNumber.getProof(address))
          }
        }

        val state = node.reader.stateAt(header)
        Seq(alice -> manySlots, bob -> manySlots, carol -> otherSlots).foreach { case (address, slots) =>
          val account = byNumber.get(address).get
          val trie = storageByRoot(account.storageRoot.value)
          (slots.map(_._1) :+ 99999).foreach { slot =>
            withClue(s"slot $slot of $address: ") {
              val expected = trie.get(BigInt(slot)).getOrElse(BigInt(0))
              BigInt(
                1,
                node.blockchain
                  .getAccountStorageAt(state, address, account.storageRoot.value, slot, ethCompatibleStorage = true)
                  .toArray
              ) shouldBe expected
              val proof = node.blockchain
                .getStorageProofAt(state, address, account.storageRoot.value, slot, ethCompatibleStorage = true)
              proof shouldBe StorageProof(BigInt(slot), trie.get(BigInt(slot)), trie.getProof(BigInt(slot)))
            }
          }
        }
      }

    it should "hand out a buffer: what a reader persists by accident never reaches the store" taggedAs UnitTest in {
      val node = new Node(EphemDataSource(), mode, guard = false)
      val root = writeThrough(node.storages.stateStorage.getBackingStorage(1), node)
      val header = headerOf(1, root)

      Seq(node.reader.stateAt(header), node.reader.scratchStateAt(header)).foreach { state =>
        val changed = InMemoryWorldStateProxy.persistState(
          worldOver(state, root, node.storages.evmCodeStorage).saveAccount(Address(0x5c4a7c4), Account(1, 2))
        )
        val newRoot = changed.stateRootHash
        newRoot should not be root
        // the execution reads back what it wrote, on the storage it wrote it to
        accountTrie(state, newRoot).get(Address(0x5c4a7c4)) shouldBe Some(Account(1, 2))
        // and nobody else sees it: not the store, not the next reader
        a[MissingNodeException] should be thrownBy
          accountTrie(node.storages.stateStorage.getBackingStorage(1), newRoot).get(Address(0x5c4a7c4))
        a[MissingNodeException] should be thrownBy
          accountTrie(node.reader.stateAt(header), newRoot).get(Address(0x5c4a7c4))
      }
    }
  }

  final private class OnPositions:
    val hashed = new HashedState
    val header: BlockHeader = headerOf(1, hashed.root)
    val source: EphemDataSource = EphemDataSource()
    seed(source, hashed, refOf(header))
    val node = new Node(source, ArchivePruning, guard = true)
    val reference: MerklePatriciaTrie[Address, Account] = accountTrie(hashed.nodes, hashed.root)

  "The seam with the guard on" should "answer from the position store what the same state answers on a hash store" taggedAs
    UnitTest in {
      val f = new OnPositions
      f.node.storages.pathStateStorage.flatMap(_.anchor) shouldBe Some(refOf(f.header))

      (Seq(alice, bob, carol, dave, Address(0xdead)) ++ crowd.take(5)).foreach { address =>
        withClue(s"account $address: ") {
          f.node.reader.accountAt(f.header, address) shouldBe f.reference.get(address)
          encoded(f.node.reader.accountProofIn(f.node.reader.stateAt(f.header), f.header, address)) shouldBe
            encoded(f.reference.getProof(address))
        }
      }

      // alice and bob hold the same storage trie, node for node: each is read under its own account
      val state = f.node.reader.stateAt(f.header)
      Seq(alice -> manySlots, bob -> manySlots, carol -> otherSlots).foreach { case (address, slots) =>
        val account = f.reference.get(address).get
        val trie = EthereumUInt256Mpt.storageMpt(account.storageRoot.value, f.hashed.nodes)
        (slots.map(_._1) :+ 99999).foreach { slot =>
          withClue(s"slot $slot of $address: ") {
            val proof = f.node.blockchain
              .getStorageProofAt(state, address, account.storageRoot.value, slot, ethCompatibleStorage = true)
            proof shouldBe StorageProof(BigInt(slot), trie.get(BigInt(slot)), trie.getProof(BigInt(slot)))
            BigInt(
              1,
              f.node.blockchain
                .getAccountStorageAt(state, address, account.storageRoot.value, slot, ethCompatibleStorage = true)
                .toArray
            ) shouldBe trie.get(BigInt(slot)).getOrElse(BigInt(0))
          }
        }
      }
    }

  it should "refuse a storage read that names the wrong account, since a position is under its owner" taggedAs
    UnitTest in {
      val f = new OnPositions
      val state = f.node.reader.stateAt(f.header)
      val carolsRoot = f.reference.get(carol).get.storageRoot.value
      // dave has no storage: carol's root is nowhere under dave
      a[MissingNodeException] should be thrownBy f.node.blockchain
        .getAccountStorageAt(state, dave, carolsRoot, otherSlots.head._1, ethCompatibleStorage = true)
    }

  it should "say that the state of any other block is not held" taggedAs UnitTest in {
    val f = new OnPositions
    val sibling = f.header.copy(extraData = ByteString("a side block at the same height"))
    sibling.hash should not be f.header.hash
    Seq(sibling, headerOf(0, f.hashed.root), headerOf(1, ByteString(kec256("another root".getBytes)))).foreach {
      other =>
        a[StateUnavailable] should be thrownBy f.node.reader.stateAt(other)
        a[StateUnavailable] should be thrownBy f.node.reader.scratchStateAt(other)
        a[StateUnavailable] should be thrownBy f.node.reader.accountAt(other, alice)
    }
  }

  // The by-number reads answered a block above the best block with no account; the pending block is such a block.
  Seq(false, true).foreach { guard =>
    it should s"answer a block above the best block with no account, as the read by number did (guard ${
        if guard then "on" else "off"
      })" taggedAs UnitTest in {
      val f = new OnPositions
      val node = if guard then f.node else new Node(EphemDataSource(), ArchivePruning, guard = false)
      val pending = headerOf(2, f.hashed.root)
      node.reader.accountIn(f.hashed.nodes, pending, alice) shouldBe None
      node.reader.accountProofIn(f.hashed.nodes, pending, alice) shouldBe None
      node.reader.accountIn(f.hashed.nodes, f.header, alice) shouldBe f.reference.get(alice)
      encoded(node.reader.accountProofIn(f.hashed.nodes, f.header, alice)) shouldBe
        encoded(f.reference.getProof(alice))
    }
  }

  it should "give an execution a state it can persist to, and a reader one it cannot" taggedAs UnitTest in {
    val f = new OnPositions
    val codes = f.node.storages.evmCodeStorage

    val scratch = f.node.reader.scratchStateAt(f.header)
    val changed = InMemoryWorldStateProxy.persistState(
      worldOver(scratch, f.hashed.root, codes).saveAccount(Address(0x5c4a7c4), Account(1, 2))
    )
    worldOver(scratch, changed.stateRootHash, codes).getAccount(Address(0x5c4a7c4)) shouldBe Some(Account(1, 2))
    // dropped with the storage: the next execution starts from the block's state
    a[MissingNodeException] should be thrownBy
      worldOver(f.node.reader.scratchStateAt(f.header), changed.stateRootHash, codes).getAccount(alice)
    f.node.reader.accountAt(f.header, Address(0x5c4a7c4)) shouldBe None

    an[UnsupportedOperationException] should be thrownBy InMemoryWorldStateProxy.persistState(
      worldOver(f.node.reader.stateAt(f.header), f.hashed.root, codes).saveAccount(Address(0x5c4a7c4), Account(1, 2))
    )
  }

  // SNAP serves a state by its root. With the guard off the serve is handed the read-only store whatever the root, and
  // finds the root's node there or answers with nothing. With it on there is one state, the anchor's.
  "A state served to a peer by its root" should "be the read-only store, for any root, with the guard off" taggedAs
    UnitTest in {
      val node = new Node(EphemDataSource(), ArchivePruning, guard = false)
      val root = writeThrough(node.storages.stateStorage.getBackingStorage(1), node)
      val served = node.reader.stateForRoot(root)
      served shouldBe defined
      accountTrie(served.get, root).get(alice) shouldBe accountTrie(
        node.storages.stateStorage.getBackingStorage(1),
        root
      ).get(alice)
      node.reader.stateForRoot(ByteString(kec256("a root nobody holds".getBytes))) shouldBe defined
    }

  it should "be the anchor's state for the anchor's root and nothing for any other, with the guard on" taggedAs
    UnitTest in {
      val f = new OnPositions
      val served = f.node.reader.stateForRoot(f.hashed.root)
      served shouldBe defined
      f.node.reader.accountsIn(served.get, f.header).get(alice) shouldBe f.reference.get(alice)
      f.node.reader.stateForRoot(ByteString(kec256("a root nobody holds".getBytes))) shouldBe None
      f.node.reader.stateForRoot(EmptyRoot) shouldBe None
    }

  // The strict chain import asks whether the head's state is there before it takes a block as present (forge).
  "Whether a block's state can be built on" should "be said by the store the chain's state is in" taggedAs UnitTest in {
    val onHashes = new Node(EphemDataSource(), ArchivePruning, guard = false)
    val root = writeThrough(onHashes.storages.stateStorage.getBackingStorage(1), onHashes)
    onHashes.reader.holdsStateOf(headerOf(1, root)) shouldBe true
    onHashes.reader.holdsStateOf(headerOf(1, ByteString(kec256("a root nobody holds".getBytes)))) shouldBe false

    // on the unified layout: the block whose state the disk holds, and no other, whatever the hash store has
    val f = new OnPositions
    f.node.reader.holdsStateOf(f.header) shouldBe true
    f.node.reader.holdsStateOf(headerOf(2, f.hashed.root)) shouldBe false
    f.node.reader.getMptNodeByHash(f.hashed.root) shouldBe None
  }

  it should "stop being served once the best block is more than the served window past it" taggedAs UnitTest in {
    val f = new OnPositions
    val appState = f.node.storages.appStateStorage
    // the anchor is block 1
    appState.putBestBlockNumber(1 + BlockchainReader.ServedRootWindow).commit()
    f.node.reader.stateForRoot(f.hashed.root) shouldBe defined
    appState.putBestBlockNumber(1 + BlockchainReader.ServedRootWindow + 1).commit()
    f.node.reader.stateForRoot(f.hashed.root) shouldBe None
  }
