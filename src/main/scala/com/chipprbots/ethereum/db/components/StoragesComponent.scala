package com.chipprbots.ethereum.db.components

import com.chipprbots.ethereum.db.storage._
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.BlockchainStorages

trait StoragesComponent {

  val storages: Storages

  trait Storages extends BlockchainStorages {

    val blockHeadersStorage: BlockHeadersStorage

    val blockBodiesStorage: BlockBodiesStorage

    val blockNumberMappingStorage: BlockNumberMappingStorage

    val receiptStorage: ReceiptStorage

    val nodeStorage: NodeStorage

    val evmCodeStorage: EvmCodeStorage

    val chainWeightStorage: ChainWeightStorage

    val appStateStorage: AppStateStorage

    val fastSyncStateStorage: FastSyncStateStorage

    val transactionMappingStorage: TransactionMappingStorage

    val knownNodesStorage: KnownNodesStorage

    val pruningMode: PruningMode

  }
}
