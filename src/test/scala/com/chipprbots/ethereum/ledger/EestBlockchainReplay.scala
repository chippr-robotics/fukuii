package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import scala.util.Try

import org.bouncycastle.util.encoders.Hex
import org.json4s.*

import com.chipprbots.ethereum.blockchain.data.GenesisAccount
import com.chipprbots.ethereum.blockchain.data.GenesisData
import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.db.storage.StagedBlockState
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.Block.toBlock
import com.chipprbots.ethereum.utils.BlobSchedule
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ForkTimestamps
import com.chipprbots.ethereum.utils.NetworkType

/** Replays one execution-specs `blockchain_test` fixture through the path `ChainImporter` takes for hive's chain.rlp:
  * genesis via [[GenesisDataLoader]] (the `alloc` is the fixture's `pre`), then per block
  * `validateBlockBeforeExecution` → execute → `validateBlockAfterExecution` → `validateRequestsHash`, each block
  * accepted or rejected exactly as the fixture's `expectException` says, ending on the fixture's `lastblockhash`.
  *
  * Only fields that are byte-for-byte the release's are replayed: `network`, `config.chainid`, `config.blobSchedule`
  * (through the chain-conf loader, [[withFixtureBlobSchedule]]), `pre`, `genesisRLP`, `blocks[].rlp`,
  * `blocks[].expectException` and `lastblockhash`. The JSON-rendered `blockHeader`, `transactions` and
  * `blockAccessList` are never used to rebuild a block, so a field fukuii does not model cannot be silently dropped on
  * the way in: the block hash that comes out is the hash of the release's own RLP.
  */
object EestBlockchainReplay:

  /** Timestamp forks in activation order. A fixture network is a prefix of this list, all active at genesis, or an
    * `XToYAtTime15k` transition in which the last fork of the prefix activates at timestamp 15,000.
    */
  private val TimestampForks: Seq[String] = Seq("Shanghai", "Cancun", "Prague", "Osaka", "BPO1", "BPO2", "Amsterdam")

  private val Transition = "(\\w+)To(\\w+)AtTime15k".r

  private def schedule(active: Map[String, Long]): ForkTimestamps =
    ForkTimestamps(
      shanghaiTimestamp = active.get("Shanghai"),
      cancunTimestamp = active.get("Cancun"),
      pragueTimestamp = active.get("Prague"),
      osakaTimestamp = active.get("Osaka"),
      amsterdamTimestamp = active.get("Amsterdam"),
      bpo1Timestamp = active.get("BPO1"),
      bpo2Timestamp = active.get("BPO2")
    )

  /** The fork schedule a fixture `network` name denotes, or `None` for a name this replay does not model. Paris is the
    * base every schedule builds on, so it has no timestamp forks.
    */
  def forkTimestamps(network: String): Option[ForkTimestamps] =
    network match
      case "Paris" => Some(ForkTimestamps())
      case Transition(from, to)
          if TimestampForks.contains(to) && TimestampForks.indexOf(to) == TimestampForks.indexOf(from) + 1 =>
        val before = TimestampForks.take(TimestampForks.indexOf(to))
        Some(schedule(before.map(_ -> 0L).toMap + (to -> 15000L)))
      case fork if TimestampForks.contains(fork) =>
        Some(schedule(TimestampForks.take(TimestampForks.indexOf(fork) + 1).map(_ -> 0L).toMap))
      case _ => None

  /** hive's post-merge ETH shape: every block fork at 0, ETC-only forks off, TTD 0, the fixture's timestamp forks. */
  def configFor(base: BlockchainConfig, network: String, chainId: BigInt): Either[String, BlockchainConfig] =
    forkTimestamps(network).toRight(s"network $network not modelled").map { timestamps =>
      base
        .copy(
          networkType = NetworkType.ETH,
          chainId = ChainId(chainId),
          forkTimestamps = timestamps,
          terminalTotalDifficulty = Some(BigInt(0))
        )
        .withUpdatedForkBlocks(
          _.copy(
            frontierBlockNumber = 0,
            homesteadBlockNumber = 0,
            eip106BlockNumber = 0,
            eip150BlockNumber = 0,
            eip155BlockNumber = 0,
            eip160BlockNumber = 0,
            eip161BlockNumber = 0,
            byzantiumBlockNumber = 0,
            constantinopleBlockNumber = 0,
            petersburgBlockNumber = 0,
            istanbulBlockNumber = 0,
            berlinBlockNumber = 0,
            muirGlacierBlockNumber = 0,
            olympiaBlockNumber = 0,
            atlantisBlockNumber = BigInt(Long.MaxValue),
            aghartaBlockNumber = BigInt(Long.MaxValue),
            phoenixBlockNumber = BigInt(Long.MaxValue),
            magnetoBlockNumber = BigInt(Long.MaxValue),
            mystiqueBlockNumber = BigInt(Long.MaxValue),
            spiralBlockNumber = BigInt(Long.MaxValue),
            ecip1099BlockNumber = BigInt(Long.MaxValue)
          )
        )
    }

  /** The fixture's own `config.blobSchedule` (EIP-7840, fork names capitalised, values hex), put through the chain-conf
    * loader exactly as a `blob-schedule` block (#1637): an Osaka or Amsterdam entry must equal the one it inherits, an
    * entry is required for every scheduled blob fork, and BPO3+ is refused. A fixture without one keeps `config`'s.
    */
  def withFixtureBlobSchedule(config: BlockchainConfig, t: JValue): Either[String, BlockchainConfig] =
    t \ "config" \ "blobSchedule" match
      case JObject(entries) =>
        import scala.jdk.CollectionConverters.*
        val hocon: java.util.Map[String, AnyRef] = entries
          .map { case (fork, params) =>
            val fields =
              Seq("target" -> "target", "max" -> "max", "baseFeeUpdateFraction" -> "base-fee-update-fraction")
                .flatMap((json, key) => (params \ json).toOption.map(v => key -> (bi(str(v)).toString: AnyRef)))
            s"blob-schedule.${fork.toLowerCase}" -> (fields.toMap.asJava: AnyRef)
          }
          .toMap
          .asJava
        Try(
          BlobSchedule.fromConfig(com.typesafe.config.ConfigFactory.parseMap(hocon), config.forkTimestamps)
        ).toEither.left
          .map(e => s"fixture blobSchedule: ${e.getMessage}")
          .map(schedule => config.copy(blobSchedule = schedule))
      case _ => Right(config)

  private def hx(s: String): Array[Byte] = Hex.decode(s.stripPrefix("0x"))
  private def bi(s: String): BigInt = if s.stripPrefix("0x").isEmpty then 0 else BigInt(s.stripPrefix("0x"), 16)
  private def str(v: JValue): String = v.values.toString

  /** A debugging aid for an `INVALID_BLOCK_ACCESS_LIST` divergence: where the list execution produced differs from the
    * fixture's `blockAccessList` JSON (execution-specs' own list, rendered for humans). The replay never builds
    * anything from that JSON — the verdict is the header hash alone — so this only names the difference, first few
    * entries first.
    */
  private def accessListDiff(json: JValue, computed: Option[BlockAccessList]): String =
    import BlockAccessList.*
    def idx(v: JValue): Long = bi(str(v \ "blockAccessIndex")).toLong
    def arr(v: JValue): List[JValue] = v match
      case JArray(items) => items
      case _             => Nil
    val expected: Map[Address, AccountChanges] = arr(json).map { a =>
      val address = Address(hx(str(a \ "address")))
      address -> AccountChanges(
        address,
        arr(a \ "storageChanges").map { sc =>
          SlotChanges(
            UInt256(bi(str(sc \ "slot"))),
            arr(sc \ "slotChanges").map(c => StorageChange(idx(c), UInt256(bi(str(c \ "postValue")))))
          )
        },
        arr(a \ "storageReads").map(r => UInt256(bi(str(r)))),
        arr(a \ "balanceChanges").map(c => BalanceChange(idx(c), UInt256(bi(str(c \ "postBalance"))))),
        arr(a \ "nonceChanges").map(c => NonceChange(idx(c), bi(str(c \ "postNonce")))),
        arr(a \ "codeChanges").map(c => CodeChange(idx(c), ByteString(hx(str(c \ "newCode")))))
      )
    }.toMap
    val actual: Map[Address, AccountChanges] = computed.toSeq.flatMap(_.accounts).map(a => a.address -> a).toMap
    if expected.isEmpty then "" // no JSON for this block
    else
      val diffs = (expected.keySet ++ actual.keySet).toSeq.sortBy(_.toString).flatMap { address =>
        (expected.get(address), actual.get(address)) match
          case (Some(_), None) => Seq(s"$address missing")
          case (None, Some(a)) => Seq(s"$address extra: $a")
          case (Some(e), Some(a)) =>
            Seq(
              Option.when(e.storageChanges != a.storageChanges)(
                s"$address storage_changes want ${e.storageChanges} got ${a.storageChanges}"
              ),
              Option.when(e.storageReads != a.storageReads)(
                s"$address storage_reads want ${e.storageReads} got ${a.storageReads}"
              ),
              Option.when(e.balanceChanges != a.balanceChanges)(
                s"$address balance_changes want ${e.balanceChanges} got ${a.balanceChanges}"
              ),
              Option.when(e.nonceChanges != a.nonceChanges)(
                s"$address nonce_changes want ${e.nonceChanges} got ${a.nonceChanges}"
              ),
              Option.when(e.codeChanges != a.codeChanges)(s"$address code_changes differ")
            ).flatten
          case (None, None) => Nil
      }
      if diffs.isEmpty then " (content equals the fixture's JSON list)"
      else diffs.take(6).mkString(" | vs fixture JSON: ", "; ", if diffs.size > 6 then s"; +${diffs.size - 6}" else "")

  private[ethereum] class Env extends EphemBlockchainTestSetup:
    override lazy val validators: Validators = ValidatorsExecutor(Protocol.EngineApi)
    override lazy val vm: VMImpl = new VMImpl

  /** Sees the trie-node storage a replay runs on, and where each unit of state changes begins and ends: the genesis
    * load, then each block. The default sees nothing and changes nothing.
    */
  trait StorageObserver:
    /** The storage a world of the replay is built over, given the one it would have been built over. */
    def wrap(storage: MptStorage): MptStorage = storage
    def genesisStarting(): Unit = ()
    def blockStarting(): Unit = ()

    /** The unit that began last has ended. `stateRoot` is the state it left when it was accepted, and `None` when it
      * was rejected. `load` finds a trie node of the replay's store by hash.
      */
    def ended(stateRoot: Option[ByteString], load: Array[Byte] => Array[Byte]): Unit = ()

  private object Unobserved extends StorageObserver

  /** `delegate`, with the storage the genesis load builds into seen by `observer`. */
  final private class ObservedGenesisStorage(delegate: StateStorage, observer: StorageObserver) extends StateStorage:
    override def getBackingStorage(bn: BigInt): MptStorage = delegate.getBackingStorage(bn)
    override def getReadOnlyStorage: MptStorage = observer.wrap(delegate.getReadOnlyStorage)
    override def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
      delegate.onBlockSave(bn, currentBestSavedBlock)(updateBestBlocksData)
    override def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
      delegate.onBlockRollback(bn, currentBestSavedBlock)(updateBestBlocksData)
    override def saveNode(nodeHash: NodeHash, nodeEncoded: NodeEncoded, bn: BigInt): Unit =
      delegate.saveNode(nodeHash, nodeEncoded, bn)
    override def forcePersist(reason: StateStorage.FlushSituation): Boolean = delegate.forcePersist(reason)

  /** The genesis a fixture starts from: its `pre` accounts under the header fields of its genesis block. */
  private def genesisData(t: JValue, gh: BlockHeader): GenesisData =
    val JObject(pre) = t \ "pre": @unchecked
    val alloc = pre.map { case (address, acc) =>
      val code = ByteString(hx(str(acc \ "code")))
      val JObject(storage) = acc \ "storage": @unchecked
      address.stripPrefix("0x") -> GenesisAccount(
        precompiled = None,
        balance = UInt256(bi(str(acc \ "balance"))),
        code = Option.when(code.nonEmpty)(code),
        nonce = Some(UInt256(bi(str(acc \ "nonce")))),
        storage = Option.when(storage.nonEmpty)(storage.map { case (k, v) =>
          UInt256(bi(k)) -> UInt256(bi(str(v)))
        }.toMap)
      )
    }.toMap
    GenesisData(
      nonce = gh.nonce,
      mixHash = Some(gh.mixHash.value),
      difficulty = gh.difficulty.value.toString,
      extraData = gh.extraData,
      gasLimit = gh.gasLimit.value.toString,
      coinbase = gh.beneficiary,
      timestamp = gh.unixTimestamp.toString,
      alloc = alloc,
      baseFeePerGas = gh.baseFee.map(_.toString),
      excessBlobGas = gh.excessBlobGas.map(_.toString),
      blobGasUsed = gh.blobGasUsed.map(_.toString)
    )

  /** Replays one fixture and returns its divergences from the release; empty means the fixture passes. */
  def replay(t: JValue): Seq[String] = replay(t, Unobserved)

  /** Loads the genesis of fixture `t` into `env`'s datadir by the node's own loader, under the fixture's own genesis
    * header: on the hash store as a node stores a genesis there, on the unified state layout as the genesis commit
    * (spec 018 Track C, C3c). The loader builds the state from the fixture's `pre`; the header is the fixture's when
    * its state root is that state's, and `Left` says so when it is not.
    */
  private[ethereum] def loadFixtureGenesis(env: EphemBlockchainTestSetup, t: JValue, genesis: Block)(using
      BlockchainConfig
  ): Either[String, Unit] =
    val gh = genesis.header
    val storages = env.storagesInstance.storages
    val loader = new GenesisDataLoader(
      env.blockchainReader,
      env.blockchainWriter,
      storages.evmCodeStorage,
      storages.stateStorage,
      countsExact => storages.appStateStorage.markDatadirCreated(countsExact).commitSync(),
      storages.pathStateStorage,
      () => storages.appStateStorage.getStateLayout()
    ):
      override protected def prepareHeader(data: GenesisData, root: Array[Byte])(implicit
          blockchainConfig: BlockchainConfig
      ): BlockHeader =
        if ByteString(root) == gh.stateRoot.value then gh else super.prepareHeader(data, root)
    loader.loadGenesisData(genesisData(t, gh)).toEither.left.map(e => s"the genesis was not loaded: $e").flatMap { _ =>
      env.blockchainReader.getBlockByNumber(env.blockchainReader.getBestBranch, 0) match
        case Some(stored) if stored.header.hash == gh.hash && stored.body == genesis.body => Right(())
        case Some(stored) if stored.header.hash == gh.hash => Left("the genesis body stored is not the fixture's")
        case Some(stored) => Left(s"genesis state root ${stored.header.stateRoot} != ${gh.stateRoot}")
        case None         => Left("no genesis block was stored")
    }

  /** What says a datadir is NOT one the unified state layout wrote alone and left whole: nodes in the hash store, no
    * layout stamp or another's, no state anchor, or an anchor that is not the head. Empty for a datadir a replay on
    * positions leaves; the harness canary (T110) holds that a run on the hash store is seen by it.
    */
  private[ethereum] def notOnPositions(env: EphemBlockchainTestSetup): Seq[String] =
    import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
    val storages = env.storagesInstance.storages
    Seq(
      Option.when(PathStateTestKit.hashStoreRows(env.storagesInstance.dataSource).nonEmpty)(
        "the hash store was written to"
      ),
      Option.unless(
        storages.appStateStorage
          .getStateLayout()
          .contains(com.chipprbots.ethereum.db.storage.pathstate.StateLayout.PathLive1)
      )(
        "the datadir has no layout stamp"
      ),
      storages.appStateStorage.getStateAnchor() match
        case None => Some("the datadir has no state anchor")
        // the state on disk and the head move in one batch: an anchor that is not the head is a state left behind
        case Some(anchor) =>
          Option.unless(env.blockchainReader.getBestBlockHeader.exists(_.hash.value == anchor.hash))(
            s"the state anchor ${anchor.show} is not the head"
          )
    ).flatten

  /** What [[replayOnPositions]] saw of one fixture. */
  final case class OnPositions(
      divergences: Seq[String],
      blocksKept: Int,
      blocksRejected: Int,
      positionsChanged: Int,
      storageRowsDeleted: Int,
      storageTriesEmptied: Int,
      tombstones: Int
  )

  /** [[Env]] over a data source of the caller's, on the hash store or, with `guard`, on the unified state layout. */
  private[ethereum] class EnvOver(source: com.chipprbots.ethereum.db.dataSource.EphemDataSource, guard: Boolean)
      extends Env:
    import com.chipprbots.ethereum.db.components.EphemDataSourceComponent
    import com.chipprbots.ethereum.db.components.Storages
    private trait Shared extends EphemDataSourceComponent:
      override val dataSource: com.chipprbots.ethereum.db.dataSource.EphemDataSource = source
    override lazy val storagesInstance
        : EphemDataSourceComponent & LocalPruningConfigBuilder & Storages.DefaultStorages =
      new Shared
        with LocalPruningConfigBuilder
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = guard

  /** Replays one fixture '''on the unified state layout''' (spec 018 Track C, C3b), through the node's import path:
    * `BlockExecution.executeAndValidateBlocks`, which stages each block over its parent's state and commits it in one
    * batch with the block.
    *
    * The genesis is committed by the node's loader, under the fixture's own genesis header ([[loadFixtureGenesis]]): no
    * node of the state is written to the hash store, at the genesis or after it. After every block the fixture keeps,
    * the rows of CF `t` and CF `u` must be exactly the block's state: a walk of the raw nodes from the header's state
    * root, finding each node among the rows by its hash, says which row belongs at which position, and the disk must
    * hold those rows and no other. Then the block's changeset record is held to take the rows back to what they were
    * before it. A block the fixture rejects must leave rows, anchor and best block as they were.
    */
  def replayOnPositions(t: JValue): OnPositions =
    import com.chipprbots.ethereum.crypto.kec256
    import com.chipprbots.ethereum.db.dataSource.EphemDataSource
    import com.chipprbots.ethereum.db.storage.PathNodeStorage
    import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
    import com.chipprbots.ethereum.db.storage.pathstate.ChangesetStorage
    import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
    import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk

    type Disk = (Map[ByteString, ByteString], Map[ByteString, ByteString])
    def refOf(header: BlockHeader): BlockRef = BlockRef(header.hash.value, header.number.value, header.stateRoot.value)
    def none(divergence: String): OnPositions = OnPositions(Seq(divergence), 0, 0, 0, 0, 0, 0)

    val source = EphemDataSource()
    // The node on the unified layout, from an empty datadir.
    val env = new EnvOver(source, guard = true)
    val chainId = (t \ "config" \ "chainid").toOption.map(v => bi(str(v))).getOrElse(BigInt(1))
    configFor(env.blockchainConfig, str(t \ "network"), chainId).flatMap(withFixtureBlobSchedule(_, t)) match
      case Left(unmodelled) => none(unmodelled)
      case Right(config) =>
        given BlockchainConfig = config
        val genesis = hx(str(t \ "genesisRLP")).toBlock
        val gh = genesis.header
        val loaded = loadFixtureGenesis(env, t, genesis)
        if loaded.isLeft then none(loaded.left.toOption.get)
        else if !env.storagesInstance.storages.pathStateStorage.flatMap(_.anchor).contains(refOf(gh)) then
          none("the genesis commit did not leave the genesis as the anchor")
        else
          val pathState = env.storagesInstance.storages.pathStateStorage.get
          val rows = new PathNodeStorage(source)
          val records = new ChangesetStorage(source)
          val blockValidation = new BlockValidation(env.mining, env.blockchainReader, env.blockQueue)
          val blockExecution = new BlockExecution(
            env.blockchain,
            env.blockchainReader,
            env.blockchainWriter,
            env.storagesInstance.storages.evmCodeStorage,
            env.mining.blockPreparator,
            blockValidation
          )
          def disk: Disk = PathStateTestKit.allRows(rows)

          /** What the disk must hold for the state at `root`, each node found among the rows by its hash. */
          def stateOnDisk(root: ByteString, held: Disk): Either[String, Disk] =
            val byHash =
              (held._1.valuesIterator ++ held._2.valuesIterator).map(n => ByteString(kec256(n.toArray)) -> n).toMap
            Try(
              RawTrieWalk.statePositions(
                root.toArray,
                hash =>
                  byHash
                    .getOrElse(ByteString(hash), throw new NoSuchElementException("a node of the state has no row"))
                    .toArray
              )
            ).toEither.left.map(e => s"the state cannot be walked from the rows: $e").map { positions =>
              (
                positions.getOrElse(None, Map.empty),
                positions
                  .collect { case (Some(owner), trie) => trie.map((hp, node) => (owner ++ hp) -> node) }
                  .flatten
                  .toMap
              )
            }

          // The genesis commit is trusted for nothing: the rows it left must be the genesis state, each of its nodes
          // and no other. A row missing here could hide behind a block the fixture expects to be rejected.
          val genesisFault: Option[String] =
            stateOnDisk(gh.stateRoot.value, disk).fold(
              fault => Some(s"genesis: $fault"),
              state => Option.when(state != disk)("genesis: the rows are not the genesis state")
            )

          var kept, rejected, positionsChanged, storageRowsDeleted, storageTriesEmptied, tombstones = 0
          val JArray(blocks) = t \ "blocks": @unchecked
          val divergences = blocks.zipWithIndex.flatMap { case (b, i) =>
            val expectException = (b \ "expectException").toOption.map(str)
            val before = disk
            val anchorBefore = pathState.anchor
            val bestBefore = env.blockchainReader.getBestBlockHeader.map(_.hash)
            val outcome: Either[String, Block] =
              Try(hx(str(b \ "rlp")).toBlock).toEither.left.map(e => s"undecodable: $e").flatMap { block =>
                val weight =
                  env.blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
                blockExecution.executeAndValidateBlocks(List(block), weight, adoptEachBlock = true) match
                  case (List(_), None)  => Right(block)
                  case (_, Some(error)) => Left(error.describe)
                  case (other, None)    => Left(s"imported ${other.size} blocks of one")
              }
            (outcome, expectException) match
              case (Right(block), None) =>
                kept += 1
                val header = block.header
                val now = disk
                val record = records.get(header.hash.value).flatMap(_.toOption)
                val reversed: Option[Disk] = record.map { diff =>
                  diff.changes.foldLeft(now) { case ((accounts, storage), (position, change)) =>
                    val key = ByteString(position.rowKey)
                    if position.owner.isEmpty then
                      (change.prior.fold(accounts - key)(n => accounts + (key -> n)), storage)
                    else (accounts, change.prior.fold(storage - key)(n => storage + (key -> n)))
                  }
                }
                record.foreach { diff =>
                  positionsChanged += diff.changes.size
                  tombstones += (if diff.incomplete then 1 else 0)
                  storageRowsDeleted += diff.changes.count((p, c) => p.owner.isDefined && c.next.isEmpty)
                  storageTriesEmptied += diff.changes.count((p, c) =>
                    p.owner.isDefined && p.path.length == 0 && c.next.isEmpty
                  )
                }
                Seq(
                  stateOnDisk(header.stateRoot.value, now).fold(
                    Some(_),
                    state =>
                      Option.when(state != now)(
                        s"the rows are not the block's state: ${(now._1.keySet ++ now._2.keySet).diff(state._1.keySet ++ state._2.keySet).size} rows too many, " +
                          s"${(state._1.keySet ++ state._2.keySet).diff(now._1.keySet ++ now._2.keySet).size} missing"
                      )
                  ),
                  Option.when(!pathState.anchor.contains(refOf(header)))(
                    s"the anchor is ${pathState.anchor.map(_.show)}"
                  ),
                  Option.when(!env.blockchainReader.getBestBlockHeader.map(_.hash).contains(header.hash))(
                    "the best block is not the block"
                  ),
                  Option.when(record.isEmpty)("no changeset record"),
                  record.flatMap(diff =>
                    Option.when(diff.validate.nonEmpty)(s"the record: ${diff.validate.mkString("; ")}")
                  ),
                  record.flatMap(diff =>
                    Option.when(!diff.incomplete && !reversed.contains(before))(
                      "the record does not take the rows back"
                    )
                  )
                ).flatten.map(d => s"block[$i] (number ${header.number}): $d")
              case (Left(_), Some(_)) =>
                rejected += 1
                Seq(
                  Option.when(disk != before)("a rejected block changed the rows"),
                  Option.when(pathState.anchor != anchorBefore)("a rejected block moved the anchor"),
                  Option.when(env.blockchainReader.getBestBlockHeader.map(_.hash) != bestBefore)(
                    "a rejected block moved the best block"
                  )
                ).flatten.map(d => s"block[$i]: $d")
              case (Right(_), Some(ex)) => Seq(s"block[$i] accepted, fixture expects $ex")
              case (Left(err), None)    => Seq(s"block[$i] rejected: $err")
          }
          val head = env.blockchainReader.getBestBlock.map(b => "0x" + Hex.toHexString(b.header.hash.value.toArray))
          val expectedHead = str(t \ "lastblockhash")
          // one writer of the state, and not the hash store's (data-model 7.6)
          OnPositions(
            genesisFault.toSeq ++ divergences ++ notOnPositions(env) ++
              (if head.contains(expectedHead) then Nil else Seq(s"head $head != $expectedHead")),
            kept,
            rejected,
            positionsChanged,
            storageRowsDeleted,
            storageTriesEmptied,
            tombstones
          )

  /** [[replay]] with `observer` on the storage of the genesis load and of every block's execution. Each world is still
    * built by the production code, over the storage it would have used with the observer's in front.
    */
  def replay(t: JValue, observer: StorageObserver): Seq[String] =
    val observed = observer ne Unobserved
    val env = new Env
    import env.{blockQueue, blockchain, blockchainReader, blockchainWriter, mining, storagesInstance}
    val chainId = (t \ "config" \ "chainid").toOption.map(v => bi(str(v))).getOrElse(BigInt(1))
    configFor(env.blockchainConfig, str(t \ "network"), chainId).flatMap(withFixtureBlobSchedule(_, t)) match
      case Left(unmodelled) => Seq(unmodelled)
      case Right(config) =>
        given BlockchainConfig = config

        val genesis = hx(str(t \ "genesisRLP")).toBlock
        val gh = genesis.header
        val stateStorage = storagesInstance.storages.stateStorage
        def loadNode(hash: Array[Byte]): Array[Byte] = stateStorage.getReadOnlyStorage.get(hash).encode
        observer.genesisStarting()
        new GenesisDataLoader(
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.evmCodeStorage,
          if observed then new ObservedGenesisStorage(stateStorage, observer) else stateStorage
        ).loadGenesisData(genesisData(t, gh)).get
        val loadedRoot = blockchainReader.getBlockHeaderByNumber(0).map(_.stateRoot)
        observer.ended(loadedRoot.filter(_ == gh.stateRoot).map(_.value), loadNode)
        if !loadedRoot.contains(gh.stateRoot) then Seq(s"genesis state root $loadedRoot != ${gh.stateRoot}")
        else
          // The loader's header omits fields the fixture's genesis carries (e.g. parentBeaconBlockRoot); the STATE is
          // what is under test, so the fixture's own genesis header becomes the chain's parent.
          blockchainWriter.save(genesis, Nil, ChainWeight.zero.increase(gh), saveAsBestBlock = true)

          val blockValidation = new BlockValidation(mining, blockchainReader, blockQueue)
          val blockExecution =
            if !observed then
              new BlockExecution(
                blockchain,
                blockchainReader,
                blockchainWriter,
                storagesInstance.storages.evmCodeStorage,
                mining.blockPreparator,
                blockValidation
              )
            else
              new BlockExecution(
                blockchain,
                blockchainReader,
                blockchainWriter,
                storagesInstance.storages.evmCodeStorage,
                mining.blockPreparator,
                blockValidation
              ):
                // The storage is the one the production method picks, decoded-node cache included, with the
                // observer's in front of it. The world itself is built by the production method.
                override protected def buildInitialWorld(
                    block: Block,
                    parentHeader: BlockHeader,
                    isProposer: Boolean,
                    staged: Option[StagedBlockState]
                )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
                  val storage =
                    staged.fold(blockchain.getBackingMptStorage(block.header.number.value))(_.storage) match
                      case serializing: SerializingMptStorage => serializing.cachedForExecution
                      case other                              => other
                  super.buildInitialWorld(
                    block,
                    parentHeader,
                    isProposer,
                    Some(StagedBlockState.direct(observer.wrap(storage)))
                  )
          val JArray(blocks) = t \ "blocks": @unchecked
          val blockDivergences = blocks.zipWithIndex.flatMap { case (b, i) =>
            val expectException = (b \ "expectException").toOption.map(str)
            observer.blockStarting()
            // An invalid block's RLP need not decode (INCORRECT_BLOCK_FORMAT): failing to decode is a rejection.
            val outcome: Either[String, (Block, Seq[Receipt])] =
              Try(hx(str(b \ "rlp")).toBlock).toEither.left.map(e => s"undecodable: $e").flatMap { block =>
                blockValidation.validateBlockBeforeExecution(block).left.map(_.toString).flatMap { _ =>
                  blockExecution.executeBlockNoValidationWithRequests(block).left.map(_.describe).flatMap {
                    case (receipts, gasUsed, root, requests, accessList) =>
                      blockValidation
                        .validateBlockAfterExecution(block, root, receipts, gasUsed)
                        .left
                        .map(_.toString)
                        .flatMap(_ => blockExecution.validateRequestsHash(block, requests).left.map(_.describe))
                        .flatMap { _ =>
                          blockExecution
                            .validateBlockAccessList(block, accessList)
                            .left
                            .map(err => err.describe + accessListDiff(b \ "blockAccessList", accessList))
                        }
                        .map(_ => (block, receipts))
                  }
                }
              }
            // A block is kept exactly when it is accepted and the fixture expects that, the first case below.
            observer.ended(
              outcome.toOption.filter(_ => expectException.isEmpty).map((block, _) => block.header.stateRoot.value),
              loadNode
            )
            (outcome, expectException) match
              case (Right((block, receipts)), None) =>
                val w = blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
                blockchainWriter.save(block, receipts, w.increase(block.header), saveAsBestBlock = true)
                Nil
              case (Left(_), Some(_))   => Nil
              case (Right(_), Some(ex)) => Seq(s"block[$i] accepted, fixture expects $ex")
              case (Left(err), None)    => Seq(s"block[$i] rejected: $err")
          }
          val head = blockchainReader.getBestBlock.map(b => "0x" + Hex.toHexString(b.header.hash.value.toArray))
          val expectedHead = str(t \ "lastblockhash")
          blockDivergences ++ (if head.contains(expectedHead) then Nil else Seq(s"head $head != $expectedHead"))
