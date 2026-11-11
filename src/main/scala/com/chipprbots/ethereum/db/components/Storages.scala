package com.chipprbots.ethereum.db.components

import com.chipprbots.ethereum.db.cache.AppCaches
import com.chipprbots.ethereum.db.cache.LruCache
import com.chipprbots.ethereum.db.storage.*
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.StateConfig
import com.chipprbots.ethereum.db.storage.pathstate.StateMetrics
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.StateLayoutConfig

object Storages:

  trait PruningModeComponent:
    val pruningMode: PruningMode

  trait DefaultStorages extends StoragesComponent:

    dataSourcesComp: DataSourceComponent & PruningModeComponent =>

    /** Whether the unified state layout's storage is built (spec 018 Track C, in-development guard). A def, read while
      * `storages` is being built.
      */
    protected def statePathLive: Boolean = StateLayoutConfig.pathLive

    /** The `state` section the unified layout runs on. Read only when the guard is on: reading it refuses `pruning.mode
      * \= "inmemory"`, which has no place on that layout.
      */
    protected def stateConfig: StateConfig = StateConfig(Config.config)

    override val storages: Storages = new DefaultStorages(pruningMode)

    class DefaultStorages(override val pruningMode: PruningMode) extends Storages with AppCaches:

      override val blockHeadersStorage: BlockHeadersStorage = new BlockHeadersStorage(dataSource)

      override val blockBodiesStorage: BlockBodiesStorage = new BlockBodiesStorage(dataSource)

      override val blockNumberMappingStorage: BlockNumberMappingStorage = new BlockNumberMappingStorage(dataSource)

      override val receiptStorage: ReceiptStorage = new ReceiptStorage(dataSource)

      override val blockAccessListStorage: BlockAccessListStorage = new BlockAccessListStorage(dataSource)

      override val nodeStorage: NodeStorage = new NodeStorage(dataSource)

      override val fastSyncStateStorage: FastSyncStateStorage = new FastSyncStateStorage(dataSource)

      override val evmCodeStorage: EvmCodeStorage = new EvmCodeStorage(dataSource)

      override val chainWeightStorage: ChainWeightStorage =
        new ChainWeightStorage(dataSource)

      override val appStateStorage: AppStateStorage = new AppStateStorage(dataSource)

      override val transactionMappingStorage: TransactionMappingStorage = new TransactionMappingStorage(dataSource)

      override val knownNodesStorage: KnownNodesStorage = new KnownNodesStorage(dataSource)

      override val blockFirstSeenStorage: BlockFirstSeenRocksDbStorage =
        new BlockFirstSeenRocksDbStorage(dataSource)

      override val flatSlotStorage: FlatSlotStorage = new FlatSlotStorage(dataSource)

      override val flatAccountStorage: FlatAccountStorage = new FlatAccountStorage(dataSource)

      override val stateStorage: StateStorage =
        StateStorage(
          pruningMode,
          nodeStorage,
          new LruCache[NodeHash, HeapEntry](
            Config.inMemoryPruningNodeCacheConfig,
            Some(CachedReferenceCountedStorage.saveOnlyNotificationHandler(nodeStorage))
          ),
          // Whether the datadir's counts are proven exact, read at each prune so a SNAP start in this process takes
          // effect on the next one (#1692). A datadir with no stamp deletes no trie node.
          () => RefCountTrust.nodesMayBeDeleted(appStateStorage)
        )

      // After `appStateStorage`, which it reads the state anchor from. Built only with the guard on: with it off this
      // object graph is what it was before the track.
      override val pathStateStorage: Option[PathStateStorage] =
        Option.when(statePathLive) {
          val config = stateConfig
          new PathStateStorage(
            new PathNodeStorage(dataSource),
            appStateStorage,
            config.overlay.maxViewAgeMoves,
            // No finalized block is named yet: nothing follows a consensus client on this layout before the overlay
            // (C4d), and without one the floor is the larger, `state.retention.without-finality`.
            PathStateStorage.CommitSettings.of(config, finalized = () => None, observer = StateMetrics)
          )
        }
