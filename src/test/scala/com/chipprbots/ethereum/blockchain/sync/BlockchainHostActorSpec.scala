package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.testkit.TestProbe
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*
import scala.language.postfixOps

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.blockchain.sync.codec.MptNodeCodecs.*
import com.chipprbots.ethereum.blockchain.sync.codec.ReceiptCodecs.*
import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerSelector
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscribeCmd
import com.chipprbots.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.PeerManagerActor.FastSyncHostConfiguration
import com.chipprbots.ethereum.network.PeerManagerActor.PeerConfiguration
import com.chipprbots.ethereum.network.p2p.messages.Codes
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockBodies
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.BlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetBlockHeaders
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.GetNodeData
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.network.rlpx.RLPxConnectionHandler.RLPxConfiguration
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

class BlockchainHostActorSpec extends AnyFlatSpec with Matchers:

  it should "return Receipts for block hashes" taggedAs (UnitTest) in new TestSetup:
    peerEventBus.expectMsgType[SubscribeCmd].to shouldBe MessageClassifier(
      Set(
        Codes.GetPooledTransactionsCode,
        Codes.GetNodeDataCode,
        Codes.GetReceiptsCode,
        Codes.GetBlockBodiesCode,
        Codes.GetBlockHeadersCode,
        // eth/71 (EIP-8159) and eth/72 (EIP-8070): served alongside the other ETH request codes.
        Codes.GetBlockAccessListsCode,
        Codes.GetCellsCode
      ),
      PeerSelector.AllPeers
    )

    // given
    val receiptsHashes: Seq[ByteString] = Seq(
      ByteString(Hex.decode("a218e2c611f21232d857e3c8cecdcdf1f65f25a4477f98f6f47e4063807f2308")),
      ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))
    )

    val receipts: Seq[Seq[Receipt]] = Seq(Seq(), Seq())

    blockchainWriter
      .storeReceipts(BlockHash(receiptsHashes.head), receipts.head)
      .and(blockchainWriter.storeReceipts(BlockHash(receiptsHashes(1)), receipts(1)))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(ETHPackets.GetReceipts(BigInt(0), receiptsHashes), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(
        ETHPackets.Receipts68(
          BigInt(0),
          com.chipprbots.ethereum.rlp
            .RLPList(receipts.map(rs => com.chipprbots.ethereum.rlp.RLPList(rs.map(_.toRLPEncodable)*))*)
        ),
        peerId
      )
    )

  it should "return BlockBodies for block hashes" taggedAs (UnitTest) in new TestSetup:
    // given
    val blockBodiesHashes: Seq[ByteString] = Seq(
      ByteString(Hex.decode("a218e2c611f21232d857e3c8cecdcdf1f65f25a4477f98f6f47e4063807f2308")),
      ByteString(Hex.decode("1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"))
    )

    val blockBodies: Seq[BlockBody] = Seq(baseBlockBody, baseBlockBody)

    blockchainWriter
      .storeBlockBody(BlockHash(blockBodiesHashes(0)), blockBodies(0))
      .and(blockchainWriter.storeBlockBody(BlockHash(blockBodiesHashes(1)), blockBodies(1)))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(ETHPackets.GetBlockBodies(BigInt(0), blockBodiesHashes), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(ETHPackets.BlockBodies(BigInt(0), blockBodies), peerId)
    )

  it should "return block headers by block number" taggedAs (UnitTest) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(4))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(5))))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(6))))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Left(3), 2, 0, reverse = false), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers by block number when response is shorter then what was requested" taggedAs (
    UnitTest
  ) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(4))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Left(3), 3, 0, reverse = false), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers by block number in reverse order" taggedAs (UnitTest) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(2))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(1))))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Left(3), 2, 0, reverse = true), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers by block hash" taggedAs (UnitTest) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(4))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(5))))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(6))))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Right(firstHeader.hash.value), 2, 0, reverse = false), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers by block hash when skipping headers" taggedAs (UnitTest) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(5))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(4))))
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(6))))
      .and(blockchainWriter.storeBlockHeader(baseBlockHeader.copy(number = BlockNumber(7))))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(
        ETHPackets.GetBlockHeaders(BigInt(0), Right(firstHeader.hash.value), maxHeaders = 2, skip = 1, reverse = false),
        peerId
      )
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers in reverse when there are skipped blocks" taggedAs (
    UnitTest
  ) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(1))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Right(firstHeader.hash.value), 2, 1, reverse = true), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers in reverse when there are skipped blocks and we are asking for blocks before genesis" taggedAs (
    UnitTest
  ) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(3))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(1))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Right(firstHeader.hash.value), 3, 1, reverse = true), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader)), peerId)
    )

  it should "return block headers in reverse when there are skipped blocks ending at genesis" taggedAs (
    UnitTest
  ) in new TestSetup:
    // given
    val firstHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(4))
    val secondHeader: BlockHeader = baseBlockHeader.copy(number = BlockNumber(2))

    blockchainWriter
      .storeBlockHeader(firstHeader)
      .and(blockchainWriter.storeBlockHeader(secondHeader))
      .commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetBlockHeaders(BigInt(0), Right(firstHeader.hash.value), 4, 1, reverse = true), peerId)
    )

    // then
    networkPeerManager.expectMsg(
      NetworkPeerManagerActor.SendMessageCmd(
        BlockHeaders(BigInt(0), Seq(firstHeader, secondHeader, blockchainReader.genesisHeader)),
        peerId
      )
    )

  it should "return evm code for hash" taggedAs (UnitTest) in new TestSetup:
    // given
    val fakeEvmCode: ByteString = ByteString(Hex.decode("ffddaaffddaaffddaaffddaaffddaa"))
    val evmCodeHash: ByteString = ByteString(crypto.kec256(fakeEvmCode.toArray[Byte]))

    storagesInstance.storages.evmCodeStorage.put(evmCodeHash, fakeEvmCode).commit()

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(MessageFromPeer(GetNodeData(Seq(evmCodeHash)), peerId))

    // then
    networkPeerManager.expectMsg(NetworkPeerManagerActor.SendMessageCmd(NodeData(Seq(fakeEvmCode)), peerId))

  it should "return mptNode for hash" taggedAs (UnitTest) in new TestSetup:
    // given
    val exampleNibbles: ByteString = ByteString(HexPrefix.bytesToNibbles(Hex.decode("ffddaa")))
    val exampleHash: ByteString = ByteString(Hex.decode("ab" * 32))
    val extensionNode: MptNode = ExtensionNode(exampleNibbles, HashNode(exampleHash.toArray[Byte]))

    storagesInstance.storages.stateStorage.saveNode(
      ByteString(extensionNode.hash),
      extensionNode.toBytes: Array[Byte],
      0
    )

    // when
    blockchainHost ! BlockchainHostActor.PeerEventReceived(
      MessageFromPeer(GetNodeData(Seq(ByteString(extensionNode.hash))), peerId)
    )

    // then
    networkPeerManager.expectMsg(NetworkPeerManagerActor.SendMessageCmd(NodeData(Seq(extensionNode.toBytes)), peerId))

  trait TestSetup extends EphemBlockchainTestSetup:
    implicit override lazy val classicSystem: ActorSystem = ActorSystem("BlockchainHostActor_System")

    blockchainWriter.storeBlockHeader(Fixtures.Blocks.Genesis.header).commit()

    val peerConf: PeerConfiguration = new PeerConfiguration:
      override val fastSyncHostConfiguration: FastSyncHostConfiguration = new FastSyncHostConfiguration:
        val maxBlocksHeadersPerMessage: Int = 200
        val maxBlocksBodiesPerMessage: Int = 200
        val maxReceiptsPerMessage: Int = 200
        val maxMptComponentsPerMessage: Int = 200
      override val rlpxConfiguration: RLPxConfiguration = new RLPxConfiguration:
        override val waitForTcpAckTimeout: FiniteDuration = Timeouts.normalTimeout
        override val waitForHandshakeTimeout: FiniteDuration = Timeouts.normalTimeout
      override val waitForHelloTimeout: FiniteDuration = 30 seconds
      override val waitForStatusTimeout: FiniteDuration = 30 seconds
      override val waitForChainCheckTimeout: FiniteDuration = 15 seconds
      override val connectMaxRetries: Int = 3
      override val connectRetryDelay: FiniteDuration = 1 second
      override val disconnectPoisonPillTimeout: FiniteDuration = 5 seconds
      override val minOutgoingPeers = 5
      override val maxOutgoingPeers = 10
      override val maxIncomingPeers = 5
      override val maxPendingPeers = 5
      override val pruneIncomingPeers = 0
      override val minPruneAge: FiniteDuration = 1.minute
      override val networkId: Long = 1L
      override val p2pVersion: Int = Config.Network.peer.p2pVersion

      override val updateNodesInitialDelay: FiniteDuration = 5.seconds
      override val updateNodesInterval: FiniteDuration = 20.seconds
      override val shortBlacklistDuration: FiniteDuration = 1.minute
      override val longBlacklistDuration: FiniteDuration = 3.minutes
      override val statSlotDuration: FiniteDuration = 1.minute
      override val statSlotCount: Int = 30

    val baseBlockHeader = Fixtures.Blocks.Block3125369.header
    val baseBlockBody: BlockBody = BlockBody(Nil, Nil)

    val peerId: PeerId = PeerId("1")

    val peerEventBus: TestProbe = TestProbe()
    val networkPeerManager: TestProbe = TestProbe()
    val pendingTxManager: TestProbe = TestProbe()

    val blockchainHost: org.apache.pekko.actor.typed.ActorRef[BlockchainHostActor.Command] =
      classicSystem.spawn(
        BlockchainHostActor(
          blockchainReader,
          storagesInstance.storages.evmCodeStorage,
          peerConf,
          peerEventBus.ref,
          networkPeerManager.ref,
          pendingTxManager.ref.toTyped[com.chipprbots.ethereum.transactions.PendingTransactionsManager.Command]
        ),
        s"blockchain-host-${System.nanoTime()}"
      )
