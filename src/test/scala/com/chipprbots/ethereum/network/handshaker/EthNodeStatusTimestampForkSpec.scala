package com.chipprbots.ethereum.network.handshaker

import java.util.concurrent.atomic.AtomicReference

import cats.effect.unsafe.IORuntime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.ForkResolver
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.NodeStatus
import com.chipprbots.ethereum.utils.ServerStatus

/** WI-13 / issue #1429: eth/68, eth/69 and eth/70+ STATUS handshakes must validate a peer's ForkId against the LOCAL
  * head's TIMESTAMP, not just its block number, on timestamp-fork chains.
  *
  * Fixture: Platåberget (`Config.blockchains.blockchains("plataberget")`), which per `ForkIdPlatabergetSpec` carries
  * ZERO block-number forks — every fork on that chain is timestamp-based. This isolates the bug precisely: the local
  * best block NUMBER is deliberately tiny (5) while its TIMESTAMP is past Amsterdam (the chain's last scheduled fork).
  * Before this fix, `ForkIdValidator.validatePeer` had no timestamp parameter and every one of the exchange states
  * passed `blockchainReader.getBestBlockNumber` as the sole axis of comparison. Since a Platåberget-scale block count
  * never reaches a real unix timestamp (~1.79 billion), no timestamp fork ever counted as passed no matter how far the
  * chain had genuinely progressed, and a stale (pre-Amsterdam) peer validated as compatible.
  */
class EthNodeStatusTimestampForkSpec extends AnyFlatSpec with Matchers:

  implicit val ioRuntimeForForkId: IORuntime = IORuntime.global

  trait TestSetup extends SecureRandomBuilder with EphemBlockchainTestSetup:

    // Platåberget carries no block-number forks (ForkIdPlatabergetSpec), so any block count validates the same
    // way on the block axis — this test picks a tiny one deliberately, to prove the TIMESTAMP axis is what
    // decides the outcome, not the number of blocks actually imported.
    implicit override lazy val blockchainConfig: BlockchainConfig = Config.blockchains.blockchains("plataberget")

    val genesisHeader: BlockHeader = Fixtures.Blocks.Genesis.header.copy(unixTimestamp = Timestamp(0))
    val genesisBlock: Block = Block(genesisHeader, Fixtures.Blocks.Genesis.body)
    val genesisWeight: ChainWeight = ChainWeight.zero.increase(genesisBlock.header)
    blockchainWriter.save(genesisBlock, Nil, genesisWeight, saveAsBestBlock = true)

    // Amsterdam is Platåberget's last scheduled fork (ForkIdPlatabergetSpec) — past it, next=None.
    val amsterdamTimestamp: Long = blockchainConfig.forkTimestamps.amsterdamTimestamp.getOrElse(
      throw new IllegalStateException("plataberget config has no amsterdamTimestamp — fixture is stale")
    )

    val tinyBlockNumber: BlockNumber = BlockNumber(5)
    val bestHeader: BlockHeader = genesisHeader.copy(
      parentHash = genesisHeader.hash,
      number = tinyBlockNumber,
      unixTimestamp = Timestamp(amsterdamTimestamp) // past Amsterdam by clock, tiny by block count
    )
    val bestBlock: Block = Block(bestHeader, Fixtures.Blocks.Genesis.body)
    val bestWeight: ChainWeight = genesisWeight.increase(bestHeader)
    blockchainWriter.save(bestBlock, Nil, bestWeight, saveAsBestBlock = true)

    val nodeStatus: NodeStatus = NodeStatus(
      key = generateKeyPair(secureRandom),
      serverStatus = ServerStatus.NotListening,
      discoveryStatus = ServerStatus.NotListening
    )
    lazy val nodeStatusHolder = new AtomicReference(nodeStatus)

    class TestHandshakerConfiguration extends NetworkHandshakerConfiguration:
      override val forkResolverOpt: Option[ForkResolver] = None
      override val nodeStatusHolder: AtomicReference[NodeStatus] = TestSetup.this.nodeStatusHolder
      override val peerConfiguration: PeerConfiguration = Config.Network.peer
      override val blockchain: Blockchain = TestSetup.this.blockchain
      override val appStateStorage: AppStateStorage = TestSetup.this.storagesInstance.storages.appStateStorage
      override val blockchainReader: BlockchainReader = TestSetup.this.blockchainReader
      override val blockchainConfig: BlockchainConfig = TestSetup.this.blockchainConfig

    val handshakerConfig: TestHandshakerConfiguration = new TestHandshakerConfiguration

    // A remote ForkId that predates every known fork: the bare genesis checksum, with next=None. This represents
    // software old enough that it has never heard of ANY of Platåberget's timestamp forks — genuinely stale, as
    // distinct from a peer merely behind but correctly aware of what its own next fork is.
    val staleForkId: ForkId =
      ForkId(ForkId.create(genesisHeader.hash.value, 0L, blockchainConfig)(BigInt(0), 0L).hash, None)

    // A remote ForkId matching exactly what an honestly-upgraded, past-Amsterdam peer announces.
    val upgradedForkId: ForkId =
      ForkId.create(genesisHeader.hash.value, 0L, blockchainConfig)(BigInt(999999999), amsterdamTimestamp)

  behavior of "EthNodeStatus68ExchangeState"

  it should "reject a stale pre-Amsterdam peer once the local head has passed Amsterdam by TIMESTAMP" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    val state: EthNodeStatus68ExchangeState = EthNodeStatus68ExchangeState(handshakerConfig, Capability.ETH68)
    val remoteStatus: ETHPackets.Status68.Status68 = ETHPackets.Status68.Status68(
      protocolVersion = Capability.ETH68.version,
      networkId = Config.Network.peer.networkId,
      totalDifficulty = 0,
      bestHash = bestHeader.hash.value,
      genesisHash = genesisHeader.hash.value,
      forkId = staleForkId
    )
    state.applyResponseMessage(remoteStatus) match
      case DisconnectedState(Disconnect.Reasons.UselessPeer, false) => succeed // ForkId reject, not wrong-network (#88)
      case other => fail(s"expected a UselessPeer disconnect, got: $other")

  it should "accept an upgraded past-Amsterdam peer at the same tiny block count" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    val state: EthNodeStatus68ExchangeState = EthNodeStatus68ExchangeState(handshakerConfig, Capability.ETH68)
    val remoteStatus: ETHPackets.Status68.Status68 = ETHPackets.Status68.Status68(
      protocolVersion = Capability.ETH68.version,
      networkId = Config.Network.peer.networkId,
      totalDifficulty = 0,
      bestHash = bestHeader.hash.value,
      genesisHash = genesisHeader.hash.value,
      forkId = upgradedForkId
    )
    state.applyResponseMessage(remoteStatus) match
      case ConnectedState(_) => succeed
      case other             => fail(s"expected Connected, got: $other")

  behavior of "EthNodeStatus69ExchangeState"

  it should "reject a stale pre-Amsterdam peer once the local head has passed Amsterdam by TIMESTAMP" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    val state: EthNodeStatus69ExchangeState = EthNodeStatus69ExchangeState(handshakerConfig, Capability.ETH69)
    val remoteStatus: ETHPackets.Status69.Status69 = ETHPackets.Status69.Status69(
      protocolVersion = Capability.ETH69.version,
      networkId = Config.Network.peer.networkId,
      genesisHash = genesisHeader.hash.value,
      forkId = staleForkId,
      earliestBlock = BigInt(0),
      latestBlock = tinyBlockNumber.value,
      latestBlockHash = bestHeader.hash.value
    )
    state.applyResponseMessage(remoteStatus) match
      case DisconnectedState(Disconnect.Reasons.UselessPeer, false) => succeed // ForkId reject, not wrong-network (#88)
      case other => fail(s"expected a UselessPeer disconnect, got: $other")

  it should "accept an upgraded past-Amsterdam peer at the same tiny block count" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    val state: EthNodeStatus69ExchangeState = EthNodeStatus69ExchangeState(handshakerConfig, Capability.ETH69)
    val remoteStatus: ETHPackets.Status69.Status69 = ETHPackets.Status69.Status69(
      protocolVersion = Capability.ETH69.version,
      networkId = Config.Network.peer.networkId,
      genesisHash = genesisHeader.hash.value,
      forkId = upgradedForkId,
      earliestBlock = BigInt(0),
      latestBlock = tinyBlockNumber.value,
      latestBlockHash = bestHeader.hash.value
    )
    state.applyResponseMessage(remoteStatus) match
      case ConnectedState(_) => succeed
      case other             => fail(s"expected Connected, got: $other")

  behavior of "EthNodeStatus70ExchangeState"

  it should "reject a stale pre-Amsterdam peer once the local head has passed Amsterdam by TIMESTAMP" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    val state: EthNodeStatus70ExchangeState = EthNodeStatus70ExchangeState(handshakerConfig, Capability.ETH70)
    val remoteStatus: ETHPackets.Status70.Status70 = ETHPackets.Status70.Status70(
      protocolVersion = Capability.ETH70.version,
      networkId = Config.Network.peer.networkId,
      genesisHash = genesisHeader.hash.value,
      forkId = staleForkId,
      earliestBlock = BigInt(0),
      latestBlock = tinyBlockNumber.value,
      latestBlockHash = bestHeader.hash.value
    )
    state.applyResponseMessage(remoteStatus) match
      case DisconnectedState(Disconnect.Reasons.UselessPeer, false) => succeed // ForkId reject, not wrong-network (#88)
      case other => fail(s"expected a UselessPeer disconnect, got: $other")

  it should "accept an upgraded past-Amsterdam peer at the same tiny block count" taggedAs (
    UnitTest,
    NetworkTest
  ) in new TestSetup:
    val state: EthNodeStatus70ExchangeState = EthNodeStatus70ExchangeState(handshakerConfig, Capability.ETH70)
    val remoteStatus: ETHPackets.Status70.Status70 = ETHPackets.Status70.Status70(
      protocolVersion = Capability.ETH70.version,
      networkId = Config.Network.peer.networkId,
      genesisHash = genesisHeader.hash.value,
      forkId = upgradedForkId,
      earliestBlock = BigInt(0),
      latestBlock = tinyBlockNumber.value,
      latestBlockHash = bestHeader.hash.value
    )
    state.applyResponseMessage(remoteStatus) match
      case ConnectedState(_) => succeed
      case other             => fail(s"expected Connected, got: $other")
