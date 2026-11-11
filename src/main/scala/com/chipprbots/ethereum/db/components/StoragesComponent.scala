package com.chipprbots.ethereum.db.components

import com.chipprbots.ethereum.db.storage.*
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.BlockchainStorages

trait StoragesComponent:

  val storages: Storages

  trait Storages extends BlockchainStorages:

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

    val blockFirstSeenStorage: BlockFirstSeenRocksDbStorage

    val flatSlotStorage: FlatSlotStorage

    val flatAccountStorage: FlatAccountStorage

    /** The read side of the unified, position-keyed state layout (spec 018 Track C). Present only behind the
      * in-development guard `fukuii.state.path-live`; nothing reads it otherwise.
      */
    def pathStateStorage: Option[PathStateStorage] = None

    val pruningMode: PruningMode
