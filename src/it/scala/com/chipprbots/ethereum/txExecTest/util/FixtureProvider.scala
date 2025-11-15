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
            Config.InMemoryPruningNodeCacheConfig,
            Some(CachedReferenceCountedStorage.saveOnlyNotificationHandler(nodeStorage))
          )
        )
    }

    // Iterate through headers with their original hash keys
    fixtures.blockHeaders.foreach { case (originalHash, header) =>
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
                if (account.codeHash != DumpChainActor.emptyEvm) {
                  storages.evmCodeStorage.put(account.codeHash, fixtures.evmCode(account.codeHash)).commit()
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
}
