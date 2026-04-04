package com.chipprbots.ethereum.network.handshaker

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.generateKeyPair
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.ForkResolver
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeComplete.HandshakeFailure
import com.chipprbots.ethereum.network.handshaker.Handshaker.HandshakeComplete.HandshakeSuccess
import com.chipprbots.ethereum.network.p2p.messages.BaseETH6XMessages
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETH64
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Disconnect
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello
import com.chipprbots.ethereum.network.p2p.messages.WireProtocol.Hello.HelloEnc
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.ethereum.testing.Tags._
import com.chipprbots.ethereum.utils.ByteStringUtils._
import com.chipprbots.ethereum.utils._

class NetworkHandshakerSpec extends AnyFlatSpec with Matchers {

  it should "connect correctly after validating fork id when peer supports ETH64" taggedAs (
    UnitTest,
    NetworkTest
  ) in new LocalPeerETH64Setup with RemotePeerETH64Setup {

    val newChainWeight: ChainWeight = ChainWeight.zero.increase(genesisBlock.header).increase(firstBlock.header)

    blockchainWriter.save(firstBlock, Nil, newChainWeight, saveAsBestBlock = true)

    val newLocalStatusMsg =
      localStatusMsg
        .copy(
          bestHash = firstBlock.header.hash,
          totalDifficulty = newChainWeight.totalDifficulty,
          forkId = ForkId(0xfc64ec04L, Some(1150000))
        )

    initHandshakerWithoutResolver.nextMessage.map(_.messageToSend) shouldBe Right(localHello: HelloEnc)

    val handshakerAfterHelloOpt: Option[Handshaker[PeerInfo]] = initHandshakerWithoutResolver.applyMessage(remoteHello)
    assert(handshakerAfterHelloOpt.isDefined)

    handshakerAfterHelloOpt.get.nextMessage.map(_.messageToSend.underlyingMsg) shouldBe Right(newLocalStatusMsg)

    val handshakerAfterStatusOpt: Option[Handshaker[PeerInfo]] =
      handshakerAfterHelloOpt.get.applyMessage(remoteStatusMsg)
    assert(handshakerAfterStatusOpt.isDefined)

    handshakerAfterStatusOpt.get.nextMessage match {
      case Left(HandshakeSuccess(peerInfo)) =>
        peerInfo.remoteStatus.capability shouldBe localStatus.capability

      case other =>
        fail(s"Invalid handshaker state: $other")
    }
  }

  it should "disconnect from a useless peer after validating fork id when peer supports ETH64" taggedAs (
    UnitTest,
    NetworkTest
  ) in new LocalPeerETH64Setup with RemotePeerETH64Setup {

    val newChainWeight: ChainWeight = ChainWeight.zero.increase(genesisBlock.header).increase(firstBlock.header)

    blockchainWriter.save(firstBlock, Nil, newChainWeight, saveAsBestBlock = true)

    val newLocalStatusMsg =
      localStatusMsg
        .copy(
          bestHash = firstBlock.header.hash,
          totalDifficulty = newChainWeight.totalDifficulty,
          forkId = ForkId(0xfc64ec04L, Some(1150000))
        )

    initHandshakerWithoutResolver.nextMessage.map(_.messageToSend) shouldBe Right(localHello: HelloEnc)

    val newRemoteStatusMsg =
      remoteStatusMsg
        .copy(
          forkId = ForkId(1, None) // ForkId that is incompatible with our chain
        )

    val handshakerAfterHelloOpt: Option[Handshaker[PeerInfo]] = initHandshakerWithoutResolver.applyMessage(remoteHello)
    assert(handshakerAfterHelloOpt.isDefined)

    handshakerAfterHelloOpt.get.nextMessage.map(_.messageToSend.underlyingMsg) shouldBe Right(newLocalStatusMsg)

    val handshakerAfterStatusOpt: Option[Handshaker[PeerInfo]] =
      handshakerAfterHelloOpt.get.applyMessage(newRemoteStatusMsg)
    assert(handshakerAfterStatusOpt.isDefined)

    handshakerAfterStatusOpt.get.nextMessage match {
      case Left(HandshakeFailure(Disconnect.Reasons.UselessPeer)) => succeed
      case other =>
        fail(s"Invalid handshaker state: $other")
    }

  }

  it should "skip fork block exchange for ETH64+ when ForkId validation passes (EIP-2124 compliance)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new LocalPeerETH64Setup with RemotePeerETH64Setup {
    // This test verifies the EIP-2124 fix: for ETH64+ protocols with ForkId in status,
    // we should skip the fork block exchange and go directly to connected state
    // even if a fork resolver is configured.
    // Previously, we would incorrectly send a GetBlockHeaders request after status exchange.

    val newChainWeight: ChainWeight = ChainWeight.zero.increase(genesisBlock.header).increase(firstBlock.header)
    blockchainWriter.save(firstBlock, Nil, newChainWeight, saveAsBestBlock = true)

    // Use a handshaker WITH fork resolver configured
    val eth64HandshakerWithResolver = NetworkHandshaker(networkHandshakerConfigurationWithResolver)

    // Complete Hello exchange
    val handshakerAfterHelloOpt: Option[Handshaker[PeerInfo]] = eth64HandshakerWithResolver.applyMessage(remoteHello)
    assert(handshakerAfterHelloOpt.isDefined)

    // Complete Status exchange
    val handshakerAfterStatusOpt: Option[Handshaker[PeerInfo]] =
      handshakerAfterHelloOpt.get.applyMessage(remoteStatusMsg)
    assert(handshakerAfterStatusOpt.isDefined)

    // Verify we go directly to HandshakeSuccess without fork block exchange
    // Per EIP-2124, ForkId validation in ETH64+ replaces the fork block exchange
    handshakerAfterStatusOpt.get.nextMessage match {
      case Left(HandshakeSuccess(peerInfo)) =>
        peerInfo.remoteStatus.capability shouldBe localStatus.capability
        peerInfo.forkAccepted shouldBe true

      case Left(HandshakeFailure(reason)) =>
        fail(s"Expected HandshakeSuccess but got HandshakeFailure($reason)")

      case Right(nextMsg) =>
        // This would fail before the fix - we would incorrectly transition to
        // EtcForkBlockExchangeState and send GetBlockHeaders
        fail(s"Expected direct HandshakeSuccess but got NextMessage(${nextMsg.messageToSend})")
    }
  }

  it should "fail if a timeout happened during hello exchange" taggedAs (UnitTest, NetworkTest) in new TestSetup {
    val handshakerAfterTimeout = initHandshakerWithoutResolver.processTimeout
    handshakerAfterTimeout.nextMessage.map(_.messageToSend) shouldBe Left(
      HandshakeFailure(Disconnect.Reasons.TimeoutOnReceivingAMessage)
    )
  }

  it should "fail if a timeout happened during status exchange" taggedAs (
    UnitTest,
    NetworkTest
  ) in new RemotePeerETH63Setup {
    val handshakerAfterHelloOpt: Option[Handshaker[PeerInfo]] = initHandshakerWithResolver.applyMessage(remoteHello)
    val handshakerAfterTimeout = handshakerAfterHelloOpt.get.processTimeout
    handshakerAfterTimeout.nextMessage.map(_.messageToSend) shouldBe Left(
      HandshakeFailure(Disconnect.Reasons.TimeoutOnReceivingAMessage)
    )
  }

  // ETH68 uses ForkId as the network discriminator (EIP-2124), not the networkId field.
  // A peer with wrong networkId but valid ForkId is accepted — the ForkId encodes network identity.
  it should "accept ETH68 status msg with mismatched networkId (ForkId is the ETH68 network discriminator)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new RemotePeerETH64Setup {
    val wrongNetworkId: Int = Config.Network.peer.networkId + 1

    val handshakerAfterHelloOpt: Option[Handshaker[PeerInfo]] = initHandshakerWithResolver.applyMessage(remoteHello)
    val handshakerAfterStatusOpt: Option[Handshaker[PeerInfo]] =
      handshakerAfterHelloOpt.get.applyMessage(remoteStatusMsg.copy(networkId = wrongNetworkId))
    // ETH68 does not reject connections based on networkId alone — ForkId validation is the gate
    handshakerAfterStatusOpt.get.nextMessage match {
      case Left(HandshakeSuccess(_)) => succeed
      case other                     => fail(s"Expected HandshakeSuccess but got: $other")
    }
  }

  it should "fail if a status msg is received with invalid genesisHash" taggedAs (
    UnitTest,
    NetworkTest
  ) in new RemotePeerETH64Setup {
    val wrongGenesisHash: ByteString =
      concatByteStrings((genesisBlock.header.hash.head + 1).toByte, genesisBlock.header.hash.tail)

    val handshakerAfterHelloOpt: Option[Handshaker[PeerInfo]] = initHandshakerWithResolver.applyMessage(remoteHello)
    val handshakerAfterStatusOpt: Option[Handshaker[PeerInfo]] =
      handshakerAfterHelloOpt.get.applyMessage(remoteStatusMsg.copy(genesisHash = wrongGenesisHash))
    handshakerAfterStatusOpt.get.nextMessage.map(_.messageToSend) shouldBe Left(
      HandshakeFailure(Disconnect.Reasons.UselessPeer)
    )
  }

  it should "fail if the remote peer doesn't support ETH63+" taggedAs (
    UnitTest,
    NetworkTest
  ) in new RemotePeerETH63Setup {
    val handshakerAfterHelloOpt: Option[Handshaker[PeerInfo]] =
      initHandshakerWithResolver.applyMessage(remoteHello.copy(capabilities = Nil))
    assert(handshakerAfterHelloOpt.isDefined)
    handshakerAfterHelloOpt.get.nextMessage.leftSide shouldBe Left(
      HandshakeFailure(Disconnect.Reasons.IncompatibleP2pProtocolVersion)
    )
  }

  it should "use actual block number for ForkId (core-geth alignment)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new LocalPeerETH64Setup with RemotePeerETH64Setup {
    // ALIGNMENT WITH CORE-GETH: ForkId should always use the actual current block number
    // Core-geth implementation (eth/handler.go):
    //   head = h.chain.CurrentHeader()
    //   number = head.Number.Uint64()
    //   forkID := forkid.NewID(h.chain.Config(), genesis, number, head.Time)
    //
    // Core-geth does NOT use checkpoints or pivot blocks for ForkId calculation.
    // It always uses the actual current block for both bestHash and ForkId calculation.
    //
    // This test verifies our implementation matches core-geth behavior.

    // Advance blockchain to a low block number
    val lowBlockNumber = BigInt(1000)
    val lowBlock = firstBlock.copy(header = firstBlock.header.copy(number = lowBlockNumber))
    val lowBlockWeight: ChainWeight = genesisWeight.increase(lowBlock.header)
    blockchainWriter.save(lowBlock, Nil, lowBlockWeight, saveAsBestBlock = true)

    // Perform handshake
    val handshakerAfterHelloOpt: Option[Handshaker[PeerInfo]] = initHandshakerWithoutResolver.applyMessage(remoteHello)
    assert(handshakerAfterHelloOpt.isDefined)

    // The status message should use the actual block number for ForkId calculation
    // This matches core-geth behavior where ForkId and bestHash use the same block
    handshakerAfterHelloOpt.get.nextMessage match {
      case Right(nextMsg) =>
        nextMsg.messageToSend match {
          case statusEnc: ETH64.Status.StatusEnc =>
            val statusMsg = statusEnc.underlyingMsg
            // Best block should be the low block
            statusMsg.bestHash shouldBe lowBlock.header.hash
            // ForkId should be calculated using actual block number (1000), matching core-geth
            val expectedForkId = ForkId.create(genesisBlock.header.hash, blockchainConfig)(lowBlockNumber)
            statusMsg.forkId shouldBe expectedForkId
          case other =>
            fail(s"Expected ETH64.Status.StatusEnc message but got: $other")
        }
      case other =>
        fail(s"Expected status message but got: $other")
    }

    val handshakerAfterStatusOpt: Option[Handshaker[PeerInfo]] =
      handshakerAfterHelloOpt.get.applyMessage(remoteStatusMsg)
    assert(handshakerAfterStatusOpt.isDefined)

    // Should successfully connect
    handshakerAfterStatusOpt.get.nextMessage match {
      case Left(HandshakeSuccess(peerInfo)) =>
        peerInfo.remoteStatus.capability shouldBe localStatus.capability
      case other =>
        fail(s"Expected successful handshake but got: $other")
    }
  }

  it should "use actual block number for ForkId at high block numbers (core-geth alignment)" taggedAs (
    UnitTest,
    NetworkTest
  ) in new LocalPeerETH64Setup with RemotePeerETH64Setup {
    // ALIGNMENT WITH CORE-GETH: ForkId should always use the actual current block number
    // This test verifies the behavior at high block numbers matches core-geth.

    // Advance blockchain to a high block number
    val highBlockNumber = BigInt(19200000)
    val highBlock = firstBlock.copy(header = firstBlock.header.copy(number = highBlockNumber))
    val highBlockWeight: ChainWeight = genesisWeight.increase(highBlock.header)
    blockchainWriter.save(highBlock, Nil, highBlockWeight, saveAsBestBlock = true)

    // Perform handshake
    val handshakerAfterHelloOpt: Option[Handshaker[PeerInfo]] = initHandshakerWithoutResolver.applyMessage(remoteHello)
    assert(handshakerAfterHelloOpt.isDefined)

    // The status message should use the actual block number for ForkId
    // This matches core-geth behavior where ForkId and bestHash use the same block
    handshakerAfterHelloOpt.get.nextMessage match {
      case Right(nextMsg) =>
        nextMsg.messageToSend match {
          case statusEnc: ETH64.Status.StatusEnc =>
            val statusMsg = statusEnc.underlyingMsg
            statusMsg.bestHash shouldBe highBlock.header.hash
            // ForkId should be calculated using actual block number (19,200,000), matching core-geth
            val expectedForkId = ForkId.create(genesisBlock.header.hash, blockchainConfig)(highBlockNumber)
            statusMsg.forkId shouldBe expectedForkId
          case other =>
            fail(s"Expected ETH64.Status.StatusEnc message but got: $other")
        }
      case other =>
        fail(s"Expected status message but got: $other")
    }
  }

  trait TestSetup extends SecureRandomBuilder with EphemBlockchainTestSetup {

    val genesisBlock: Block = Block(
      Fixtures.Blocks.Genesis.header,
      Fixtures.Blocks.Genesis.body
    )

    val genesisWeight: ChainWeight = ChainWeight.zero.increase(genesisBlock.header)

    val forkBlockHeader = Fixtures.Blocks.DaoForkBlock.header

    blockchainWriter.save(genesisBlock, Nil, genesisWeight, saveAsBestBlock = true)

    val nodeStatus: NodeStatus = NodeStatus(
      key = generateKeyPair(secureRandom),
      serverStatus = ServerStatus.NotListening,
      discoveryStatus = ServerStatus.NotListening
    )
    lazy val nodeStatusHolder = new AtomicReference(nodeStatus)

    class MockNetworkHandshakerConfiguration(pv: List[Capability] = Config.supportedCapabilities)
        extends NetworkHandshakerConfiguration {
      override val forkResolverOpt: Option[ForkResolver] = None
      override val nodeStatusHolder: AtomicReference[NodeStatus] = TestSetup.this.nodeStatusHolder
      override val peerConfiguration: PeerConfiguration = Config.Network.peer
      override val blockchain: Blockchain = TestSetup.this.blockchain
      override val appStateStorage: AppStateStorage = TestSetup.this.storagesInstance.storages.appStateStorage
      override val blockchainReader: BlockchainReader = TestSetup.this.blockchainReader
      override val blockchainConfig: BlockchainConfig = TestSetup.this.blockchainConfig
    }

    val networkHandshakerConfigurationWithResolver: MockNetworkHandshakerConfiguration =
      new MockNetworkHandshakerConfiguration {
        override val forkResolverOpt: Option[ForkResolver] = Some(
          new ForkResolver.EtcForkResolver(blockchainConfig.daoForkConfig.get)
        )
      }

    val initHandshakerWithoutResolver: NetworkHandshaker = NetworkHandshaker(
      new MockNetworkHandshakerConfiguration(List(Capability.ETH68, Capability.ETH68))
    )

    val initHandshakerWithResolver: NetworkHandshaker = NetworkHandshaker(networkHandshakerConfigurationWithResolver)

    val firstBlock: Block =
      genesisBlock.copy(header = genesisBlock.header.copy(parentHash = genesisBlock.header.hash, number = 1))
  }

  trait LocalPeerSetup extends TestSetup {
    val localHello: Hello = Hello(
      p2pVersion = EtcHelloExchangeState.P2pVersion,
      clientId = Config.clientId,
      capabilities = Config.supportedCapabilities, // Use actual supported capabilities from Config
      listenPort = 0, // Local node not listening
      nodeId = ByteString(nodeStatus.nodeId)
    )
  }

  trait LocalPeerETH64Setup extends LocalPeerSetup {
    val localStatusMsg: ETH64.Status = ETH64.Status(
      protocolVersion = Capability.ETH68.version,
      networkId = Config.Network.peer.networkId,
      totalDifficulty = genesisBlock.header.difficulty,
      bestHash = genesisBlock.header.hash,
      genesisHash = genesisBlock.header.hash,
      forkId = ForkId(1L, None)
    )
    val localStatus: RemoteStatus = RemoteStatus(localStatusMsg)
  }

  trait RemotePeerSetup extends TestSetup {
    val remoteNodeStatus: NodeStatus = NodeStatus(
      key = generateKeyPair(secureRandom),
      serverStatus = ServerStatus.NotListening,
      discoveryStatus = ServerStatus.NotListening
    )
    val remotePort = 8545
  }

  trait RemotePeerETH63Setup extends RemotePeerSetup {
    val remoteHello: Hello = Hello(
      p2pVersion = EtcHelloExchangeState.P2pVersion,
      clientId = "remote-peer",
      capabilities = Seq(Capability.ETH68),
      listenPort = remotePort,
      nodeId = ByteString(remoteNodeStatus.nodeId)
    )

    val remoteStatusMsg: BaseETH6XMessages.Status = BaseETH6XMessages.Status(
      protocolVersion = Capability.ETH68.version,
      networkId = Config.Network.peer.networkId,
      totalDifficulty = 0,
      bestHash = genesisBlock.header.hash,
      genesisHash = genesisBlock.header.hash
    )

    val remoteStatus: RemoteStatus = RemoteStatus(
      remoteStatusMsg,
      Capability.ETH68,
      false,
      Seq(Capability.ETH68).toList
    )
  }

  trait RemotePeerETH64Setup extends RemotePeerSetup {
    val remoteHello: Hello = Hello(
      p2pVersion = EtcHelloExchangeState.P2pVersion,
      clientId = "remote-peer",
      capabilities = Seq(Capability.ETH68),
      listenPort = remotePort,
      nodeId = ByteString(remoteNodeStatus.nodeId)
    )

    val remoteStatusMsg: ETH64.Status = ETH64.Status(
      protocolVersion = Capability.ETH68.version,
      networkId = Config.Network.peer.networkId,
      totalDifficulty = 0,
      bestHash = genesisBlock.header.hash,
      genesisHash = genesisBlock.header.hash,
      forkId = ForkId(0xfc64ec04L, Some(1150000))
    )

    val remoteStatus: RemoteStatus = RemoteStatus(remoteStatusMsg)
  }
}
