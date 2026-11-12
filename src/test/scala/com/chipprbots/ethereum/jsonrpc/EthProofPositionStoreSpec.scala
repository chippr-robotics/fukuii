package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.unsafe.implicits.global

import org.bouncycastle.util.encoders.Hex
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.consensus.blocks.BlockGenerator
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.components.DataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainImpl
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.BlockchainWriter
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.ProofAccount
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProofKey
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C2b (T051): `eth_getProof` answers from the position store what it answers from the hash store,
  * byte for byte, and those bytes are frozen here.
  *
  * A proof is a list of node encodings on the path from a root to a leaf. Where a node is stored has no place in it:
  * the same state must give the same proof whether its nodes are kept by hash or by position. The state is the one
  * every position-store spec uses, in which `alice` and `bob` hold the same storage trie, node for node, so that a
  * storage proof has to be read under the right account.
  */
class EthProofPositionStoreSpec extends AnyFlatSpec with Matchers with MockFactory:

  import PathStateTestKit.*

  private trait Given(source: EphemDataSource) extends DataSourceComponent with Storages.PruningModeComponent:
    override val dataSource: EphemDataSource = source
    override val pruningMode: PruningMode = ArchivePruning

  /** A node whose best block is `block`, with the in-development guard on or off. */
  private class Node(guard: Boolean, val block: Block, source: EphemDataSource)
      extends Given(source)
      with Storages.DefaultStorages:
    override protected def statePathLive: Boolean = guard

    val reader: BlockchainReader = BlockchainReader(storages)
    val blockchain: BlockchainImpl = BlockchainImpl(storages, reader)
    private val writer = BlockchainWriter(storages)
    writer.storeBlock(block).commit()
    writer.saveBestKnownBlocks(block.header.hash, block.header.number.value)

    val service = new EthProofService(blockchain, reader, mock[BlockGenerator], ethCompatibleStorage = true)

    def proof(address: Address, slots: Seq[Int], at: BlockParam): Either[JsonRpcError, ProofAccount] =
      service
        .getProof(GetProofRequest(address, slots.map(slot => StorageProofKey(BigInt(slot))), at))
        .unsafeRunSync()
        .map(_.proofAccount)

  final private class Fixture:
    val hashed = new HashedState
    val block: Block = Block(
      Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(1), stateRoot = TrieRoot(hashed.root)),
      BlockBody.empty
    )
    private def header: BlockHeader = block.header

    /** The state as rows of CF `t` and CF `u`, with the anchor at the block: the unified layout. */
    val onPositions: Node =
      val source = EphemDataSource()
      seed(source, hashed, BlockRef(header.hash.value, header.number.value, header.stateRoot.value))
      new Node(guard = true, block, source)

    /** The same state written through the hash store, as a block's execution writes it. */
    val onHashes: Node =
      val node = new Node(guard = false, block, EphemDataSource())
      val storage = node.storages.stateStorage.getBackingStorage(1)
      val copied =
        hashed.positions().values.flatMap(_.values).map(rlp => ByteString(kec256(rlp.toArray)) -> rlp.toArray)
      storage.storeRawNodes(copied.toSeq)
      storage.persist()
      // the world reads it back, so the copy is whole
      InMemoryWorldStateProxy
        .persistState(worldOver(storage, hashed.root, node.storages.evmCodeStorage))
        .stateRootHash shouldBe hashed.root
      node

  private val byHash: Block => BlockParam = block => BlockParam.WithHash(block.header.hash.value)

  /** Everything `eth_getProof` returns for an account, as one digest. */
  private def digest(proof: ProofAccount): String =
    val bytes = proof.accountProof.foldLeft(ByteString.empty)(_ ++ _) ++
      ByteString(proof.balance.toByteArray) ++ proof.codeHash ++ proof.nonce.bytes ++ proof.storageHash ++
      proof.storageProof.foldLeft(ByteString.empty) { (acc, slot) =>
        acc ++ ByteString(slot.key.v.toByteArray) ++ ByteString(slot.value.toByteArray) ++
          slot.proof.foldLeft(ByteString.empty)(_ ++ _)
      }
    Hex.toHexString(kec256(bytes.toArray))

  private val asked: Seq[(Address, Seq[Int])] = Seq(
    alice -> (manySlots.take(3).map(_._1) :+ 99999),
    bob -> manySlots.takeRight(2).map(_._1),
    carol -> otherSlots.take(2).map(_._1),
    dave -> Seq(1),
    crowd.head -> Nil
  )

  "eth_getProof" should "answer from the position store, byte for byte, what it answers from the hash store" taggedAs
    UnitTest in {
      val f = new Fixture
      asked.foreach { case (address, slots) =>
        withClue(s"$address, slots $slots: ") {
          val onPositions = f.onPositions.proof(address, slots, byHash(f.block))
          onPositions.isRight shouldBe true
          onPositions shouldBe f.onHashes.proof(address, slots, byHash(f.block))
          onPositions shouldBe f.onPositions.proof(address, slots, BlockParam.Latest)
        }
      }
    }

  // Spec 018 Track C, C3c: the pending block's state is held nowhere on the unified layout, so "pending" is the head
  // there. The generator is a mock that expects no call: what it last prepared is not asked for.
  it should "answer for the head when asked for the pending block on the unified layout" taggedAs UnitTest in {
    val f = new Fixture
    val (address, slots) = asked.head
    val pending = f.onPositions.proof(address, slots, BlockParam.Pending)
    pending.isRight shouldBe true
    pending shouldBe f.onPositions.proof(address, slots, BlockParam.Latest)
  }

  // Frozen. A change here is a change to what a light client verifies against a state root.
  private val golden: Map[Address, String] = Map(
    alice -> "bd469d58828db776cdd70b06abd5281d3c32dcf2adbc96e00b81cd797673b186",
    bob -> "9dcfbb4a6e7911ae9e6b59697a92083ec4871b3d8eb92a5e310b72ed8c34d367",
    carol -> "e02a3b9098cd8caf98c4d5e384078e5be9431fb98496f2bc6ef14a0511ff5361",
    dave -> "71079b972b11b3479eaa1393f4dec80a78e25a530ad4c8aab5e4d5443b0868e3",
    crowd.head -> "bf010bf953d0760994811dece90c759f29526531db03655d16b75a7006c96da6"
  )

  it should "answer with the frozen bytes, on either store" taggedAs UnitTest in {
    val f = new Fixture
    def digests(node: Node): Map[Address, Either[JsonRpcError, String]] =
      asked.map { case (address, slots) => address -> node.proof(address, slots, byHash(f.block)).map(digest) }.toMap
    val frozen: Map[Address, Either[JsonRpcError, String]] = golden.map((address, hex) => address -> Right(hex))
    digests(f.onPositions) shouldBe frozen
    digests(f.onHashes) shouldBe frozen
  }

  it should "prove a slot under the account that was asked about, where two accounts hold the same storage" taggedAs
    UnitTest in {
      val f = new Fixture
      val slot = manySlots.head._1
      val ofAlice = f.onPositions.proof(alice, Seq(slot), byHash(f.block)).toOption.get
      val ofBob = f.onPositions.proof(bob, Seq(slot), byHash(f.block)).toOption.get
      // the same storage trie: the same root, the same storage proof
      ofAlice.storageHash shouldBe ofBob.storageHash
      ofAlice.storageProof shouldBe ofBob.storageProof
      ofAlice.storageProof.head.value shouldBe BigInt(manySlots.head._2)
      // and two accounts: two account proofs
      ofAlice.accountProof should not be ofBob.accountProof
    }

  it should "say that the state is not there for a block the position store does not hold" taggedAs UnitTest in {
    val f = new Fixture
    val other = Block(f.block.header.copy(extraData = ByteString("a side block")), BlockBody.empty)
    BlockchainWriter(f.onPositions.storages).storeBlock(other).commit()
    f.onPositions.proof(alice, Seq(1), byHash(other)) shouldBe Left(JsonRpcError.StateNotHeld)
  }
