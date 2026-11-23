package com.chipprbots.ethereum.consensus.engine

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import fs2.Stream

import com.chipprbots.ethereum.blockchain.data.GenesisDataLoader
import com.chipprbots.ethereum.blockchain.sync.ScenarioSetup
import com.chipprbots.ethereum.consensus.mining.Protocol
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.consensus.validators.Validators
import com.chipprbots.ethereum.db.components.DataSourceComponent
import com.chipprbots.ethereum.db.components.Storages
import com.chipprbots.ethereum.db.dataSource.DataSource
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource.IterationError
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.PruningMode
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.domain.StateReconciliation
import com.chipprbots.ethereum.jsonrpc.conformance.RpcCompatNode
import com.chipprbots.ethereum.ledger.BlockValidation
import com.chipprbots.ethereum.ledger.HeaderTrustingBlockExecution
import com.chipprbots.ethereum.ledger.StateRecovery
import com.chipprbots.ethereum.ledger.VMImpl
import com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
import com.chipprbots.ethereum.nodebuilder.PruningConfigBuilder
import com.chipprbots.ethereum.utils.BlockchainConfig

/** The child of [[CrashBetweenNewPayloadAndFcuItSpec]] (spec 018 T156, B-255): the node's Engine API service, with its
  * real execution, state recovery and the unified state layout (`fukuii.state.path-live` true for its storages), over a
  * real RocksDB datadir. The parent kills it with SIGKILL; it never shuts down.
  *
  * Arguments: the datadir, the kill point, and a comma-separated script of steps, run in order. Every answer is a line
  * on standard output, `R:<what>=<answer>`; a kill point is the line `KP:<point>`, after which the child parks.
  *   - `seed:N`: import blocks 1 to N as the node's import does; this is the synced head
  *   - `reconcile`: the reconciliation at start, as the node runs it on a datadir it did not write
  *   - `np:I`: `engine_newPayload` for `blocks(I)` (zero-based: block I + 1)
  *   - `fcu:I`: `engine_forkchoiceUpdated` naming `blocks(I)`
  *   - `recovered`: wait for the state recoveries the steps before it started, and say how each ended
  *   - `facts:I`: what the datadir holds, for `blocks(I)` as the head (`F:` lines)
  *   - `park`: print `KP:park` and wait to be killed
  *
  * Kill points, armed after the seed: `before-move` and `after-move`, on either side of the write that moves the
  * state's anchor (the head move a fork choice makes).
  */
object CrashBetweenNewPayloadAndFcuChild:

  def main(args: Array[String]): Unit =
    try run(args(0), args(1), args(2).split(',').toSeq.filter(_.nonEmpty))
    catch
      case t: Throwable =>
        System.out.println(s"ERR:$t")
        t.printStackTrace(System.out)
        System.out.flush()
        sys.exit(2)

  def say(line: String): Unit =
    System.out.println(line)
    System.out.flush()

  private def hex(b: ByteString): String = b.map(x => f"$x%02x").mkString

  private def run(dbPath: String, killAt: String, script: Seq[String]): Unit =
    val kit = ActorTestKit("crash-child")
    given ActorTestKit = kit
    given ActorSystem = kit.system.toClassic
    val gate = new Gate(RocksDbDataSource(rocksConfig(dbPath), Namespaces.nsSeq), killAt)
    val on = new ChildNode(gate)
    script.foreach(step(on, gate, _))
    say("DONE")
    // the actor system and the thread pools are not stopped by a return: the child ends, as it ends, by exit
    sys.exit(0)

  private def step(on: ChildNode, gate: Gate, command: String): Unit =
    val Array(name, arg) = command.split(":", 2).padTo(2, "")
    name match
      case "seed" =>
        on.blocks.take(arg.toInt).foreach(on.imports)
        say(s"R:seed=${arg}")
      case "reconcile" =>
        say(s"R:reconcile=${on.reconcile().getClass.getSimpleName.stripSuffix("$")}")
        // from here on the child's own writes are the ones a kill point is for
        gate.arm()
      case "np" =>
        say(s"R:np:$arg=${on.newPayload(on.blocks(arg.toInt)).status.value}")
      case "fcu" =>
        say(
          s"R:fcu:$arg=${on.forkchoice(on.blocks(arg.toInt)).map(_.payloadStatus.status.value).fold(identity, identity)}"
        )
      case "recovered" =>
        say(s"R:recovered=${on.recovery.outcomes.mkString("[", ";", "]")}")
      case "facts" => on.facts(arg.toInt)
      case "park" =>
        say("KP:park")
        new CountDownLatch(1).await()
      case other => throw new IllegalArgumentException(s"no such step: $other")

  def rocksConfig(dbPath: String): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = false
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 64
      override val verifyChecksums: Boolean = false
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432

  trait ArchivePruningBuilder extends PruningConfigBuilder with com.chipprbots.ethereum.TestInstanceConfigProvider:
    override val pruningMode: PruningMode = ArchivePruning

  /** The node over the real datadir: the chain of the execution-apis tests, the node's own execution and Engine API. */
  final class ChildNode(gate: Gate)(using system: ActorSystem, kit: ActorTestKit) extends ScenarioSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = RpcCompatNode.chainConfig
    override lazy val validators: Validators = ValidatorsExecutor(Protocol.EngineApi)
    override lazy val vm: VMImpl = new VMImpl

    private trait Gated extends DataSourceComponent:
      override def dataSource: DataSource = gate

    // `fukuii.state.path-live` is true for this node's storages and not for the process: the reference chain that says
    // what each block's state is runs on the hash store in the same process
    override lazy val storagesInstance: DataSourceComponent & ArchivePruningBuilder & Storages.DefaultStorages =
      new Gated
        with ArchivePruningBuilder
        with Storages.DefaultStorages
        with com.chipprbots.ethereum.TestInstanceConfigProvider:
        override protected def statePathLive: Boolean = true

    val blocks: Vector[Block] = RpcCompatNode.blocks.toVector

    GenesisDataLoader.over(blockchainReader, blockchainWriter, storagesInstance.storages).loadGenesisData()

    private def pathState: PathStateStorage =
      storagesInstance.storages.pathStateStorage.getOrElse(throw new IllegalStateException("not the unified layout"))
    private def appState: AppStateStorage = storagesInstance.storages.appStateStorage

    private val execution = new HeaderTrustingBlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      new BlockValidation(mining, blockchainReader, blockQueue)
    )

    /** The recovery as the node builds it, which keeps the tickets it hands out. */
    final class Recovery
        extends StateRecovery(
          blockchainReader,
          blockchainWriter,
          execution,
          pathState,
          maxBlocks = 8,
          maxAdoptedBlocks = 8
        )(using
          blockchainConfig
        ):
      private val tickets = new java.util.concurrent.ConcurrentLinkedQueue[StateRecovery.Ticket]()
      override def requestFor(
          head: ByteString,
          sink: StateRecovery.Sink,
          why: String,
          authority: StateRecovery.Authority
      ): StateRecovery.Ticket =
        val ticket = super.requestFor(head, sink, why, authority)
        tickets.add(ticket)
        ticket

      def outcomes: Seq[StateRecovery.Outcome] =
        // a bound on a recovery that hangs; each ends on its own and says how
        tickets.toArray(Array.empty[StateRecovery.Ticket]).toSeq.map(_.outcome.get(120, TimeUnit.SECONDS))

    val recovery = new Recovery

    private given IORuntime = IORuntime.global
    private val forkChoices = new ForkChoiceManager(blockchainReader, blockchainWriter)
    private val engine: EngineApiService =
      given org.apache.pekko.actor.typed.Scheduler = kit.system.scheduler
      new EngineApiService(
        blockchainReader,
        blockchainWriter,
        execution,
        forkChoices,
        None,
        stateHeldInPlace = Some(() => pathState.anchor.map(_.hash)),
        stateRecovery = Some(recovery)
      )

    def imports(block: Block): Unit =
      val weight = blockchainReader.getChainWeightByHash(block.header.parentHash).getOrElse(ChainWeight.zero)
      execution.executeAndValidateBlocks(List(block), weight, adoptEachBlock = true) match
        case (List(_), None) => ()
        case other => throw new IllegalStateException(s"block ${block.header.number.value} not imported: $other")

    def reconcile(): StateReconciliation.Outcome =
      StateReconciliation.run(appState, pathState, blockchainReader, blockchainWriter, false)

    def newPayload(block: Block): PayloadStatusV1 = engine.newPayload(payloadOf(block)).unsafeRunSync()

    def forkchoice(head: Block): Either[String, ForkchoiceUpdatedResponse] =
      engine
        .forkchoiceUpdated(
          ForkChoiceState(head.header.hash.value, ForkChoiceManager.ZeroHash, ForkChoiceManager.ZeroHash),
          None
        )
        .unsafeRunSync()

    /** The disk is the state of `blocks(i)` as the head: anchor, best block, index of blocks by number, positions. */
    def facts(i: Int): Unit =
      val header = blocks(i).header
      say(s"F:anchor=${pathState.anchor.map(a => hex(a.hash)).getOrElse("none")}")
      say(s"F:anchorStored=${appState.getStateAnchor().map(a => hex(a.hash)).getOrElse("none")}")
      say(s"F:best=${blockchainReader.getBestBlockNumber}")
      say(s"F:bestHash=${hex(appState.getBestBlockInfo().hash)}")
      val index = Iterator
        .from(1)
        .map(n => blockchainReader.getCanonicalHashByNumber(BigInt(n)))
        .takeWhile(_.isDefined)
        .map(_.get.value)
        .toVector
      say(s"F:index=${if index == blocks.take(i + 1).map(_.header.hash.value) then "ok" else s"bad(${index.size})"}")
      say(s"F:fault=${appState.getStateFault().getOrElse("none")}")
      // the reference: the same chain on the hash store, which says what the state of each block is
      val reference = new RpcCompatNode
      reference.importChain()
      val nodes = reference.storagesInstance.storages.nodeStorage
      val positions = RawTrieWalk.statePositions(
        header.stateRoot.value.toArray,
        hash => nodes.get(ByteString(hash)).getOrElse(throw new IllegalStateException("the reference lacks a node"))
      )
      val expected = (
        positions.getOrElse(None, Map.empty),
        positions.collect { case (Some(owner), trie) => trie.map((hp, node) => (owner ++ hp) -> node) }.flatten.toMap
      )
      say(s"F:disk=${if PathStateTestKit.allRows(new PathNodeStorage(gate)) == expected then "ok" else "bad"}")

  /** The payload a consensus client sends for `block`: the fields its header and body carry. */
  def payloadOf(block: Block): ExecutionPayload =
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
    import com.chipprbots.ethereum.rlp.encode as rlpEncode
    val h = block.header
    ExecutionPayload(
      parentHash = h.parentHash.value,
      feeRecipient = com.chipprbots.ethereum.domain.Address(h.beneficiary),
      stateRoot = h.stateRoot.value,
      receiptsRoot = h.receiptsRoot.value,
      logsBloom = h.logsBloom.value,
      prevRandao = h.mixHash.value,
      blockNumber = h.number.value,
      gasLimit = h.gasLimit.value,
      gasUsed = h.gasUsed.value,
      timestamp = h.unixTimestamp.toLong,
      extraData = h.extraData,
      baseFeePerGas = h.baseFee.getOrElse(BigInt(0)),
      blockHash = h.hash.value,
      transactions =
        block.body.transactionList.map(stx => ByteString(rlpEncode(SignedTransactionEnc(stx).toRLPEncodable))),
      withdrawals = block.body.withdrawals,
      blobGasUsed = h.blobGasUsed,
      excessBlobGas = h.excessBlobGas,
      parentBeaconBlockRoot = h.parentBeaconBlockRoot.map(_.value),
      executionRequests = h.requestsHash.map(_ => Nil)
    )

  /** The datadir, stopped on either side of the first write of the state's anchor once armed: the head move. */
  final class Gate(underlying: DataSource, point: String) extends DataSource:
    import DataSource.*
    private val armed = new AtomicBoolean(false)
    private val taken = new AtomicBoolean(false)
    private val anchorKey = AppStateStorage.Keys.StateAnchor.getBytes.toSeq

    def arm(): Unit = armed.set(true)

    private def movesAnchor(update: DataUpdate): Boolean = update match
      case DataSourceUpdate(namespace, _, upserts) =>
        namespace == Namespaces.AppStateNamespace && upserts.exists(_._1.toSeq == anchorKey)
      case DataSourceUpdateOptimized(namespace, _, upserts) =>
        namespace == Namespaces.AppStateNamespace && upserts.exists(_._1.toSeq == anchorKey)

    private def stopAt(name: String): Unit =
      if point == name then
        say(s"KP:$name")
        new CountDownLatch(1).await()

    private def around(updates: Seq[DataUpdate])(write: => Unit): Unit =
      if armed.get() && point != "none" && updates.exists(movesAnchor) && taken.compareAndSet(false, true) then
        stopAt("before-move")
        write
        stopAt("after-move")
      else write

    override def updateSync(dataSourceUpdates: Seq[DataUpdate]): Unit =
      around(dataSourceUpdates)(underlying.updateSync(dataSourceUpdates))
    def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      around(dataSourceUpdates)(underlying.update(dataSourceUpdates))

    def deleteRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
      underlying.deleteRange(namespace, fromKey, toKeyExclusive)
    override def compactRange(namespace: Namespace, fromKey: Array[Byte], toKeyExclusive: Array[Byte]): Unit =
      underlying.compactRange(namespace, fromKey, toKeyExclusive)
    def get(namespace: Namespace, key: Key): Option[Value] = underlying.get(namespace, key)
    def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] =
      underlying.getOptimized(namespace, key)
    override def getOptimizedNoFill(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] =
      underlying.getOptimizedNoFill(namespace, key)
    override def multiGetOptimized(namespace: Namespace, keys: Seq[Array[Byte]]): Seq[Option[Array[Byte]]] =
      underlying.multiGetOptimized(namespace, keys)
    override def multiGetOptimizedNoFill(namespace: Namespace, keys: Seq[Array[Byte]]): Seq[Option[Array[Byte]]] =
      underlying.multiGetOptimizedNoFill(namespace, keys)
    def scanRange(
        namespace: Namespace,
        fromKey: Array[Byte],
        toKeyExclusive: Array[Byte]
    ): Iterator[(Array[Byte], Array[Byte])] = underlying.scanRange(namespace, fromKey, toKeyExclusive)
    override def scanRangeLimit(
        namespace: Namespace,
        fromKey: Array[Byte],
        toKeyExclusive: Array[Byte],
        limit: Int
    ): Iterator[(Array[Byte], Array[Byte])] = underlying.scanRangeLimit(namespace, fromKey, toKeyExclusive, limit)
    def clear(): Unit = underlying.clear()
    def close(): Unit = underlying.close()
    def destroy(): Unit = underlying.destroy()
    def iterate(): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] = underlying.iterate()
    def iterate(namespace: Namespace): Stream[IO, Either[IterationError, (Array[Byte], Array[Byte])]] =
      underlying.iterate(namespace)
