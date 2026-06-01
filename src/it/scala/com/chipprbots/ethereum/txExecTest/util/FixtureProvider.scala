package com.chipprbots.ethereum.txExecTest.util

import java.io.Closeable

import org.apache.pekko.util.ByteString

import scala.io.Source
import scala.util.Try

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.db.cache.AppCaches
import com.chipprbots.ethereum.db.cache.LruCache
import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage._
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.BlockBody._
import com.chipprbots.ethereum.domain.BlockHeaderImplicits._
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.network.p2p.messages.ETH63._
import com.chipprbots.ethereum.utils.Config

import MptNodeEncoders._
import ReceiptImplicits._

object FixtureProvider {

  case class Fixture(
      blockByNumber: Map[BigInt, Block],
      blockByHash: Map[ByteString, Block],
      blockHeaders: Map[ByteString, BlockHeader],
      blockBodies: Map[ByteString, BlockBody],
      receipts: Map[ByteString, Seq[Receipt]],
      stateMpt: Map[ByteString, MptNode],
      contractMpts: Map[ByteString, MptNode],
      evmCode: Map[ByteString, ByteString]
  )

  // scalastyle:off
  def prepareStorages(blockNumber: BigInt, fixtures: Fixture): BlockchainStorages = {

    val storages: BlockchainStorages = new BlockchainStorages with AppCaches with EphemDataSourceComponent {

      override val receiptStorage: ReceiptStorage = new ReceiptStorage(dataSource)
      override val evmCodeStorage: EvmCodeStorage = new EvmCodeStorage(dataSource)
      override val blockHeadersStorage: BlockHeadersStorage = new BlockHeadersStorage(dataSource)
      override val blockNumberMappingStorage: BlockNumberMappingStorage = new BlockNumberMappingStorage(dataSource)
      override val blockBodiesStorage: BlockBodiesStorage = new BlockBodiesStorage(dataSource)
      override val chainWeightStorage: ChainWeightStorage = new ChainWeightStorage(dataSource)
      override val transactionMappingStorage: TransactionMappingStorage = new TransactionMappingStorage(dataSource)
      override val appStateStorage: AppStateStorage = new AppStateStorage(dataSource)
      val nodeStorage: NodeStorage = new NodeStorage(dataSource)
      val pruningMode: PruningMode = ArchivePruning
      override val stateStorage: StateStorage =
        StateStorage(
          pruningMode,
          nodeStorage,
          new LruCache[NodeHash, HeapEntry](
            Config.inMemoryPruningNodeCacheConfig,
            Some(CachedReferenceCountedStorage.saveOnlyNotificationHandler(nodeStorage))
          )
        )
    }

    // Pre-load ALL EVM code from fixtures into storage
    // This is necessary because some fixtures have account codeHash values that don't match
    // the actual code hash (they may have empty codeHash when they should have the real hash)
    fixtures.evmCode.foreach { case (codeHash, code) =>
      storages.evmCodeStorage.put(codeHash, code).commit()
    }

    // Iterate through headers in block number order using original hash keys
    fixtures.blockHeaders.toSeq
      .sortBy { case (_, header) => header.number }
      .foreach { case (originalHash, header) =>
        if (header.number <= blockNumber) {
          val receiptsUpdates = fixtures.receipts
            .get(originalHash)
            .map(r => storages.receiptStorage.put(originalHash, r))
            .getOrElse(storages.receiptStorage.emptyBatchUpdate)

          storages.blockBodiesStorage
            .put(originalHash, fixtures.blockBodies(originalHash))
            .and(storages.blockHeadersStorage.put(originalHash, header))
            .and(storages.blockNumberMappingStorage.put(header.number, originalHash))
            .and(receiptsUpdates)
            .commit()

          def traverse(nodeHash: ByteString): Unit =
            fixtures.stateMpt.get(nodeHash).orElse(fixtures.contractMpts.get(nodeHash)) match {
              case Some(m: BranchNode) =>
                storages.stateStorage.saveNode(ByteString(m.hash), m.toBytes, header.number)
                m.children.collect { case HashNode(hash) => traverse(ByteString(hash)) }

              case Some(m: ExtensionNode) =>
                storages.stateStorage.saveNode(ByteString(m.hash), m.toBytes, header.number)
                m.next match {
                  case HashNode(hash) if hash.nonEmpty => traverse(ByteString(hash))
                  case _                               =>
                }

              case Some(m: LeafNode) =>
                import AccountImplicits._
                storages.stateStorage.saveNode(ByteString(m.hash), m.toBytes, header.number)
                Try(m.value.toArray[Byte].toAccount).toOption.foreach { account =>
                  // Note: We've already saved all EVM code above, so this check is now redundant
                  // but kept for backwards compatibility with fixtures that have correct codeHash
                  if (account.codeHash != DumpChainActor.emptyEvm) {
                    fixtures.evmCode.get(account.codeHash).foreach { code =>
                      storages.evmCodeStorage.put(account.codeHash, code).commit()
                    }
                  }
                  if (account.storageRoot != DumpChainActor.emptyStorage) {
                    traverse(account.storageRoot)
                  }
                }

              case _ =>

            }

          traverse(header.stateRoot)
        }
      }

    storages
  }

  def loadFixtures(path: String): Fixture = {
    val bodies: Map[ByteString, BlockBody] =
      withClose(Source.fromFile(getClass.getResource(s"$path/bodies.txt").getPath))(
        _.getLines()
          .map(s => s.split(" ").toSeq)
          .collect { case Seq(h, v) =>
            val key = ByteString(Hex.decode(h))
            val value: BlockBody = Hex.decode(v).toBlockBody
            key -> value
          }
          .toMap
      )

    val headers: Map[ByteString, BlockHeader] =
      withClose(Source.fromFile(getClass.getResource(s"$path/headers.txt").getPath))(
        _.getLines()
          .map(s => s.split(" ").toSeq)
          .collect { case Seq(h, v) =>
            val key = ByteString(Hex.decode(h))
            val value: BlockHeader = Hex.decode(v).toBlockHeader
            key -> value
          }
          .toMap
      )

    val receipts: Map[ByteString, Seq[Receipt]] =
      withClose(Source.fromFile(getClass.getResource(s"$path/receipts.txt").getPath))(
        _.getLines()
          .map(s => s.split(" ").toSeq)
          .collect { case Seq(h, v) =>
            val key = ByteString(Hex.decode(h))
            val value: Seq[Receipt] = Hex.decode(v).toReceipts
            key -> value
          }
          .toMap
      )

    val stateTree: Map[ByteString, MptNode] =
      withClose(Source.fromFile(getClass.getResource(s"$path/stateTree.txt").getPath))(
        _.getLines()
          .map(s => s.split(" ").toSeq)
          .collect { case Seq(h, v) =>
            val key = ByteString(Hex.decode(h))
            val value: MptNode = Hex.decode(v).toMptNode
            key -> value
          }
          .toMap
      )

    val contractTrees: Map[ByteString, MptNode] =
      withClose(Source.fromFile(getClass.getResource(s"$path/contractTrees.txt").getPath))(
        _.getLines()
          .map(s => s.split(" ").toSeq)
          .collect { case Seq(h, v) =>
            val key = ByteString(Hex.decode(h))
            val value: MptNode = Hex.decode(v).toMptNode
            key -> value
          }
          .toMap
      )

    val evmCode: Map[ByteString, ByteString] =
      withClose(Source.fromFile(getClass.getResource(s"$path/evmCode.txt").getPath))(
        _.getLines()
          .map(s => s.split(" ").toSeq)
          .collect { case Seq(h, v) =>
            ByteString(Hex.decode(h)) -> ByteString(Hex.decode(v))
          }
          .toMap
      )

    // Self-consistency guard (ForksTest follow-up, PR #1294): every block header's stateRoot must
    // resolve to a node present in stateTree.txt. A corrupted/truncated stateTree.txt (the failure
    // mode that silently broke ForksTest for ~6 months) leaves a header pointing at a stateRoot that
    // never appears as a node key, so `prepareStorages.traverse(header.stateRoot)` walks into the
    // `case _ =>` no-op and the chain executes against an empty/partial trie. Fail loudly here, at
    // load time, with the exact missing root and block number, instead of producing a confusing
    // downstream MissingNode/validation error.
    assertStateTreeConsistent(path, headers, stateTree, contractTrees)

    // Match headers and bodies by their hash keys to create blocks
    // Note: We keep both the original hash key and the block for later lookup
    val blocksByOriginalHash = headers.flatMap { case (originalHash, header) =>
      bodies.get(originalHash).map(body => (originalHash, Block(header, body)))
    }

    val blocks = blocksByOriginalHash.values.toList

    Fixture(
      blocks.map(b => b.header.number -> b).toMap,
      blocksByOriginalHash.toMap, // Use original hash keys for blockByHash
      headers,
      bodies,
      receipts,
      stateTree,
      contractTrees,
      evmCode
    )
  }

  private def withClose[A, B <: Closeable](closeable: B)(f: B => A): A =
    try f(closeable)
    finally closeable.close()

  /** Fail fast if any block header references a state root that cannot be resolved to a dumped node.
    *
    * This mirrors [[prepareStorages]]'s `traverse` lookup exactly: the root node is resolvable iff it
    * appears as a key in stateTree.txt or contractTrees.txt. The genesis header (number 0) is exempt
    * because the empty-trie / pre-allocation root is synthesised by the node rather than dumped.
    *
    * A header whose stateRoot is unresolvable means stateTree.txt is truncated or out of sync with
    * headers.txt — the failure mode that silently broke ForksTest for ~6 months (PR #1294). Catching
    * it here turns a confusing downstream MissingNode/validation error into an explicit fixture-corruption
    * report naming the offending block number and root.
    */
  private def assertStateTreeConsistent(
      path: String,
      headers: Map[ByteString, BlockHeader],
      stateTree: Map[ByteString, MptNode],
      contractTrees: Map[ByteString, MptNode]
  ): Unit = {
    val emptyRoot = ByteString(MerklePatriciaTrie.EmptyRootHash)
    val missing = headers.values
      .filter(_.number > 0)
      .filterNot(_.stateRoot == emptyRoot) // an all-empty state needs no dumped node
      .filterNot(header => stateTree.contains(header.stateRoot) || contractTrees.contains(header.stateRoot))
      .map(header => header.number -> Hex.toHexString(header.stateRoot.toArray))
      .toSeq
      .sortBy(_._1)

    if (missing.nonEmpty) {
      val details = missing.map { case (number, root) => s"  block $number -> stateRoot 0x$root" }.mkString("\n")
      throw new IllegalStateException(
        s"Corrupt txExecTest fixture at '$path': ${missing.size} block header(s) reference a stateRoot " +
          s"with no matching node in stateTree.txt. The fixture is truncated or out of sync with " +
          s"headers.txt and cannot reproduce the chain:\n$details"
      )
    }
  }
}
