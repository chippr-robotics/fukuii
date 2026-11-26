package com.chipprbots.ethereum.sync.util

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.typed
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.Mocks.MockValidatorsAlwaysSucceed
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcast.BlockToBroadcast
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcasterActor
import com.chipprbots.ethereum.blockchain.sync.regular.BlockBroadcasterActor.BroadcastBlock
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher
import com.chipprbots.ethereum.blockchain.sync.regular.BlockImporter
import com.chipprbots.ethereum.blockchain.sync.regular.ImportLock
import com.chipprbots.ethereum.blockchain.sync.regular.RegularSync
import com.chipprbots.ethereum.consensus.Consensus
import com.chipprbots.ethereum.consensus.ConsensusAdapter
import com.chipprbots.ethereum.consensus.ConsensusImpl
import com.chipprbots.ethereum.consensus.mining.FullMiningConfig
import com.chipprbots.ethereum.consensus.mining.MiningConfig
import com.chipprbots.ethereum.consensus.mining.Protocol.NoAdditionalPoWData
import com.chipprbots.ethereum.consensus.pow
import com.chipprbots.ethereum.consensus.pow.EthashConfig
import com.chipprbots.ethereum.consensus.pow.PoWMining
import com.chipprbots.ethereum.consensus.pow.validators.ValidatorsExecutor
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.crypto.keyPairFromPrvKey
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.*
import com.chipprbots.ethereum.nodebuilder.VmSetup
import com.chipprbots.ethereum.ommers.OmmersPool
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.rlp.UInt256RLPImplicits.*
import com.chipprbots.ethereum.sync.util.SyncCommonItSpecUtils.*
import com.chipprbots.ethereum.sync.util.SyncCommonItSpecUtils.FakePeerCustomConfig.defaultConfig
import com.chipprbots.ethereum.transactions.PendingTransactionsManager
import com.chipprbots.ethereum.utils.*

object RegularSyncItSpecUtils:

  class ValidatorsExecutorAlwaysSucceed extends MockValidatorsAlwaysSucceed:
    override def validateBlockAfterExecution(
        block: Block,
        stateRootHash: ByteString,
        receipts: Seq[Receipt],
        gasUsed: BigInt
    )(implicit blockchainConfig: BlockchainConfig): Either[BlockExecutionError, BlockExecutionSuccess] = Right(
      BlockExecutionSuccess
    )

  object ValidatorsExecutorAlwaysSucceed extends ValidatorsExecutorAlwaysSucceed

  class FakePeer(peerName: String, fakePeerCustomConfig: FakePeerCustomConfig)
      extends CommonFakePeer(peerName, fakePeerCustomConfig):

    def buildEthashMining(): pow.PoWMining =
      val miningConfig: MiningConfig = MiningConfig(Config.config)
      val specificConfig: EthashConfig = pow.EthashConfig(config)
      val fullConfig = FullMiningConfig(miningConfig, specificConfig)
      val vm = VmSetup.vm(VmConfig(config))
      val mining =
        PoWMining(
          vm,
          storagesInstance.storages.evmCodeStorage,
          bl,
          blockchainReader,
          fullConfig,
          ValidatorsExecutorAlwaysSucceed,
          NoAdditionalPoWData
        )
      mining

    lazy val peersClient: typed.ActorRef[PeersClient.Command] =
      system.spawn(
        PeersClient.behavior(etcPeerManager, peerEventBus, blacklist, testSyncConfig),
        "peers-client"
      )

    lazy val mining: PoWMining = buildEthashMining()

    lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, syncConfig)
    lazy val blockValidation = new BlockValidation(mining, blockchainReader, blockQueue)
    lazy val blockExecution =
      new BlockExecution(
        bl,
        blockchainReader,
        blockchainWriter,
        storagesInstance.storages.evmCodeStorage,
        mining.blockPreparator,
        blockValidation
      )
    lazy val consensus: Consensus =
      new ConsensusImpl(
        blockchainReader,
        blockchainWriter,
        blockExecution,
        reorgState = bl
      )
    lazy val consensusAdapter = new ConsensusAdapter(
      consensus,
      blockchainReader,
      blockQueue,
      blockValidation,
      IORuntime.global
    )

    lazy val ommersPool: typed.ActorRef[OmmersPool.Command] =
      system.spawn(OmmersPool(blockchainReader, 1), "ommers-pool")

    lazy val pendingTxTopic: typed.ActorRef[
      org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewPendingTransaction]
    ] = system.spawn(
      org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewPendingTransaction](
        "pending-tx-topic"
      ),
      "pending-tx-topic"
    )

    lazy val pendingTransactionsManager: typed.ActorRef[PendingTransactionsManager.Command] = system.spawn(
      PendingTransactionsManager(TxPoolConfig(config), peerManager, etcPeerManager, peerEventBus, pendingTxTopic),
      "pending-transactions-manager"
    )

    lazy val blockTopic: typed.ActorRef[
      org.apache.pekko.actor.typed.pubsub.Topic.Command[com.chipprbots.ethereum.jsonrpc.NewBlockImported]
    ] = system.spawn(
      org.apache.pekko.actor.typed.pubsub.Topic[com.chipprbots.ethereum.jsonrpc.NewBlockImported](
        "block-imported-topic"
      ),
      "block-imported-topic"
    )

    lazy val validators: ValidatorsExecutor = buildEthashMining().validators

    val broadcasterRef: typed.ActorRef[BlockBroadcasterActor.BroadcasterMsg] =
      system.spawn(
        BlockBroadcasterActor.apply(
          new BlockBroadcast(etcPeerManager),
          peerEventBus,
          etcPeerManager,
          blacklist,
          syncConfig
        ),
        "block-broadcaster"
      )

    val fetcher: typed.ActorRef[BlockFetcher.FetchCommand] =
      system.spawn(
        BlockFetcher(
          peersClient,
          peerEventBus,
          regularSync.toTyped[RegularSync.ProgressProtocol],
          syncConfig,
          validators.blockValidator
        ),
        "block-fetcher"
      )

    lazy val blockImporter: typed.ActorRef[BlockImporter.Command] =
      system.spawn(
        BlockImporter.apply(
          fetcher,
          consensusAdapter,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.evmCodeStorage,
          new BranchResolution(blockchainReader),
          syncConfig,
          ommersPool,
          broadcasterRef,
          pendingTransactionsManager,
          blockTopic,
          regularSync.toTyped[RegularSync.Command],
          peerEventBus.toClassic,
          etcPeerManager,
          bl,
          blacklist,
          this,
          exhausts = new BlockImporter.ExhaustCount,
          importLock = importLock
        ),
        "block-importer"
      )

    // This peer's node's one import lock, as SyncController's (#1705).
    lazy val importLock: ImportLock = ImportLock()

    lazy val regularSync: ActorRef = system
      .spawnAnonymous(
        RegularSync.apply(
          peersClient,
          etcPeerManager,
          peerEventBus.toClassic,
          consensusAdapter,
          bl,
          blockchainReader,
          blockchainWriter,
          storagesInstance.storages.stateStorage,
          storagesInstance.storages.evmCodeStorage,
          new BranchResolution(blockchainReader),
          validators.blockValidator,
          blacklist,
          testSyncConfig,
          ommersPool,
          pendingTransactionsManager,
          blockTopic,
          this,
          system.toTyped.ignoreRef[com.chipprbots.ethereum.blockchain.sync.SyncController.Command],
          importLock
        )
      )
      .toClassic

    def startRegularSync(): IO[Unit] = IO {
      regularSync ! SyncProtocol.Start
    }

    def broadcastBlock(
        blockNumber: Option[Int] = None
    )(updateWorldForBlock: (BigInt, InMemoryWorldStateProxy) => InMemoryWorldStateProxy): IO[Unit] =
      IO(blockNumber match
        case Some(bNumber) =>
          blockchainReader
            .getBlockByNumber(blockchainReader.getBestBranch, bNumber)
            .getOrElse(throw new RuntimeException(s"block by number: $bNumber doesn't exist"))
        case None => blockchainReader.getBestBlock.get
      ).flatMap { block =>
        IO {
          val currentWeight = blockchainReader
            .getChainWeightByHash(block.hash)
            .getOrElse(throw new RuntimeException(s"ChainWeight by hash: ${block.hash} doesn't exist"))
          val currentWorld = getMptForBlock(block)
          val (newBlock, newWeight, _) = createChildBlock(block, currentWeight, currentWorld)(updateWorldForBlock)
          broadcastBlock(newBlock, newWeight)
        }
      }

    def waitForRegularSyncLoadLastBlock(blockNumber: BigInt): IO[Boolean] =
      // Scale timeout based on block number - larger syncs need more time
      // Use minimum 90 retries, but add 1 retry per 20 blocks for large syncs
      val baseRetries = 90
      val additionalRetries = if blockNumber > 1000 then ((blockNumber - 1000) / 20).toInt else 0
      val maxRetries = baseRetries + additionalRetries
      retryUntilWithDelay(IO(blockchainReader.getBestBlockNumber == blockNumber), 1.second, maxRetries)(isDone =>
        isDone
      )

    def mineNewBlock(
        plusDifficulty: BigInt = 0
    )(updateWorldForBlock: (BigInt, InMemoryWorldStateProxy) => InMemoryWorldStateProxy): IO[Unit] = IO {
      val block: Block = blockchainReader.getBestBlock.get
      val currentWeight = blockchainReader
        .getChainWeightByHash(block.hash)
        .getOrElse(throw new RuntimeException(s"ChainWeight by hash: ${block.hash} doesn't exist"))
      val currentWorld = getMptForBlock(block)
      val (newBlock, _, _) =
        createChildBlock(block, currentWeight, currentWorld, plusDifficulty)(updateWorldForBlock)
      regularSync ! SyncProtocol.MinedBlock(newBlock)
    }

    def mineNewBlocks(delay: FiniteDuration, nBlocks: Int)(
        updateWorldForBlock: (BigInt, InMemoryWorldStateProxy) => InMemoryWorldStateProxy
    ): IO[Unit] =
      if nBlocks > 0 then
        mineNewBlock()(updateWorldForBlock)
          .delayBy(delay)
          .flatMap(_ => mineNewBlocks(delay, nBlocks - 1)(updateWorldForBlock))
      else IO(())

    /** A child of `parent` that carries the transactions `plans` describe, signed by [[ExecutedChain.Sender]] with its
      * next nonces, and sealed with the state root, gas used and receipts that executing it gives. Executing writes the
      * block's state nodes into this peer's store, so the state the header names is here.
      */
    def sealChild(parent: Block, plans: Seq[ExecutedChain.TxPlan], beneficiary: Address): (Block, Seq[Receipt]) =
      val firstNonce = getMptForBlock(parent).getAccount(ExecutedChain.Sender).fold(BigInt(0))(_.nonce.toBigInt)
      val txs = plans.zipWithIndex.map { case (plan, i) =>
        SignedTransaction.sign(
          LegacyTransaction(
            nonce = firstNonce + i,
            gasPrice = GasPrice(1),
            gasLimit = GasAmount(plan.gasLimit),
            receivingAddress = plan.receiver,
            value = plan.value,
            payload = plan.payload
          ),
          ExecutedChain.SenderKeyPair,
          None
        )
      }
      val template = parent.header.copy(
        parentHash = parent.hash,
        number = parent.number + 1,
        beneficiary = beneficiary.bytes,
        gasLimit = GasAmount(ExecutedChain.BlockGasLimit),
        gasUsed = GasAmount.Zero
      )
      val unsealed = Block(template, BlockBody(txs, Nil))
      blockExecution.executeBlockNoValidation(unsealed) match
        case Right((receipts, gasUsed, stateRoot)) =>
          (
            unsealed.copy(header = template.copy(stateRoot = TrieRoot(stateRoot), gasUsed = GasAmount(gasUsed))),
            receipts
          )
        case Left(error) =>
          throw new IllegalStateException(s"$peerName could not execute its own block ${template.number}: $error")

    /** Extends this peer's chain to block `n` with executed blocks (see [[sealChild]]), saving and broadcasting each.
      * `plansFor` gives the transactions of the block with the number it is given.
      */
    def importExecutedBlocksUntil(n: BigInt, beneficiary: Address = ExecutedChain.Miner)(
        plansFor: BigInt => Seq[ExecutedChain.TxPlan]
    ): IO[Unit] =
      IO(blockchainReader.getBestBlock.get).flatMap { best =>
        if best.number.value >= n then IO.unit
        else
          IO {
            val weight = blockchainReader.getChainWeightByHash(best.hash).get
            val (child, receipts) = sealChild(best, plansFor(best.number.value + 1), beneficiary)
            val childWeight = weight.increase(child.header)
            blockchainWriter.save(child, receipts, childWeight, saveAsBestBlock = true)
            broadcastBlock(child, childWeight)
          }.flatMap(_ => importExecutedBlocksUntil(n, beneficiary)(plansFor))
      }

    /** Mines `count` executed blocks on top of the best block through this peer's running regular sync, one at a time:
      * each is handed to the importer, and the next is built only once the importer has made it the best block. Regular
      * sync must already be started, or the importer does not exist and the block is dropped; the wait then times out.
      */
    def mineExecutedBlocks(count: Int, beneficiary: Address = ExecutedChain.Miner)(
        plansFor: BigInt => Seq[ExecutedChain.TxPlan]
    ): IO[Unit] =
      if count <= 0 then IO.unit
      else
        IO {
          val best = blockchainReader.getBestBlock.get
          val (child, _) = sealChild(best, plansFor(best.number.value + 1), beneficiary)
          regularSync ! SyncProtocol.MinedBlock(child)
          child
        }.flatMap { child =>
          retryUntilWithDelay(IO(blockchainReader.getBestBlock.map(_.hash)), 50.millis, 400)(
            _.contains(child.hash)
          )
        }.flatMap(_ => mineExecutedBlocks(count - 1, beneficiary)(plansFor))

    private def broadcastBlock(block: Block, weight: ChainWeight) =
      broadcasterActor ! BroadcastBlock(BlockToBroadcast(block, weight))

    private def createChildBlock(
        parent: Block,
        parentWeight: ChainWeight,
        parentWorld: InMemoryWorldStateProxy,
        plusDifficulty: BigInt = 0
    )(
        updateWorldForBlock: (BigInt, InMemoryWorldStateProxy) => InMemoryWorldStateProxy
    ): (Block, ChainWeight, InMemoryWorldStateProxy) =
      val newBlockNumber = parent.header.number + 1
      val newWorld = updateWorldForBlock(newBlockNumber.value, parentWorld)
      val newBlock = parent.copy(header =
        parent.header.copy(
          parentHash = parent.header.hash,
          number = newBlockNumber,
          stateRoot = TrieRoot(newWorld.stateRootHash),
          difficulty = Difficulty(plusDifficulty) + parent.header.difficulty
        )
      )
      val newWeight = parentWeight.increase(newBlock.header)
      (newBlock, newWeight, parentWorld)

  /** What the executed-block builders of [[FakePeer]] need: one sender, whose key the tests hold, and one miner. The
    * sender is funded by mining to it, so its transactions start at block 2.
    */
  object ExecutedChain:
    val SenderKeyPair: AsymmetricCipherKeyPair =
      keyPairFromPrvKey(BigInt(1, Hex.decode("f3202185c84325302d43887e90a2e23e7bc058d0450bb58ef2f7585765d7d48b")))
    val Sender: Address = Address(SenderKeyPair)

    /** The beneficiary of the blocks the tests build: the sender, so block rewards fund its transactions. */
    val Miner: Address = Sender
    val OtherMiner: Address = Address(0x6d696e)
    val BlockGasLimit: BigInt = 8000000

    /** One transaction of a block: `receiver` empty creates a contract from `payload`. */
    final case class TxPlan(receiver: Option[Address], value: BigInt, payload: ByteString, gasLimit: BigInt = 300000)

    val NoTransactions: BigInt => Seq[TxPlan] = _ => Nil

    def transferTo(receiver: Address, value: BigInt): TxPlan = TxPlan(Some(receiver), value, ByteString.empty)

    /** Init code that stores each `(key, value)` and stops, creating a contract with no code but that storage. Keys and
      * values must fit two bytes.
      */
    def storingInitCode(entries: Seq[(BigInt, BigInt)]): ByteString =
      val sstore = 0x55
      val push2 = 0x61
      def push(v: BigInt): Seq[Byte] = Seq(push2.toByte, (v >> 8).toByte, v.toByte)
      ByteString(entries.flatMap { case (k, v) => push(v) ++ push(k) :+ sstore.toByte }.toArray :+ 0.toByte)

    /** The address a contract created by `creator` with account nonce `nonce` has (yellow paper equation 82). */
    def contractAddress(creator: Address, nonce: BigInt): Address =
      Address(kec256(rlp.encode(RLPList(RLPValue(creator.bytes.toArray), UInt256(nonce).toRLPEncodable))))

  object FakePeer:

    def startFakePeer(peerName: String, fakePeerCustomConfig: FakePeerCustomConfig): IO[FakePeer] =
      for
        peer <- IO(new FakePeer(peerName, fakePeerCustomConfig))
        _ <- peer.startPeer()
      yield peer

    def start1FakePeerRes(
        fakePeerCustomConfig: FakePeerCustomConfig = defaultConfig,
        name: String
    ): Resource[IO, FakePeer] =
      Resource.make {
        startFakePeer(name, fakePeerCustomConfig)
      } { peer =>
        peer.shutdown()
      }

    def start2FakePeersRes(
        fakePeerCustomConfig1: FakePeerCustomConfig = defaultConfig,
        fakePeerCustomConfig2: FakePeerCustomConfig = defaultConfig
    ): Resource[IO, (FakePeer, FakePeer)] =
      for
        peer1 <- start1FakePeerRes(fakePeerCustomConfig1, "Peer1")
        peer2 <- start1FakePeerRes(fakePeerCustomConfig2, "Peer2")
      yield (peer1, peer2)
