package com.chipprbots.ethereum.network

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETH69
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.testing.Tags._

/** Unit tests for ETH/69 TD handling.
  *
  * ETH/69 removes totalDifficulty from the Status wire message. For ETC's PoW chain we recover TD by looking it up in
  * local ChainWeightStorage via latestBlockHash. These tests verify that:
  *   - `fromETH69Status` stores the caller-resolved chainWeight and preserves latestBlock separately
  *   - `PeerInfo.apply` initialises maxBlockNumber from latestBlock (not from chainWeight.totalDifficulty)
  *   - The fallback path (peer ahead of us) uses block-number proxy correctly
  */
class ETH69TDSpec extends AnyFlatSpec with Matchers {

  private val genesisHash = Fixtures.Blocks.Genesis.header.hash
  private val latestHash = Fixtures.Blocks.Block3125369.header.hash
  private val latestBlockNr = Fixtures.Blocks.Block3125369.header.number

  private val dummyForkId = ForkId(0L, None)

  private def eth69Status(latestBlock: BigInt, latestBlockHash: ByteString): ETH69.Status =
    ETH69.Status(
      protocolVersion = Capability.ETH69.version,
      networkId = 1L,
      genesisHash = genesisHash,
      forkId = dummyForkId,
      earliestBlock = BigInt(0),
      latestBlock = latestBlock,
      latestBlockHash = latestBlockHash
    )

  // -------------------------------------------------------------------------
  // RemoteStatus.fromETH69Status
  // -------------------------------------------------------------------------

  it should "store the resolved chainWeight (not a block-number proxy) in RemoteStatus" taggedAs UnitTest in {
    val actualTD = ChainWeight.totalDifficultyOnly(BigInt("123456789000000000000000000"))
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(status, Capability.ETH69, false, Nil, actualTD)

    remoteStatus.chainWeight shouldBe actualTD
    remoteStatus.chainWeight.totalDifficulty should not be latestBlockNr
  }

  it should "store latestBlock in the dedicated RemoteStatus.latestBlock field" taggedAs UnitTest in {
    val actualTD = ChainWeight.totalDifficultyOnly(BigInt("123456789000000000000000000"))
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(status, Capability.ETH69, false, Nil, actualTD)

    remoteStatus.latestBlock shouldBe Some(latestBlockNr)
  }

  it should "store block-number proxy in chainWeight when local lookup fails (peer ahead of us)" taggedAs UnitTest in {
    val proxy = ChainWeight.totalDifficultyOnly(latestBlockNr) // fallback
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(status, Capability.ETH69, false, Nil, proxy)

    remoteStatus.chainWeight.totalDifficulty shouldBe latestBlockNr
    remoteStatus.latestBlock shouldBe Some(latestBlockNr)
  }

  it should "set capability to the negotiated capability" taggedAs UnitTest in {
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(
      status,
      Capability.ETH69,
      supportsSnap = true,
      Nil,
      ChainWeight.totalDifficultyOnly(BigInt(0))
    )

    remoteStatus.capability shouldBe Capability.ETH69
    remoteStatus.supportsSnap shouldBe true
    remoteStatus.bestHash shouldBe latestHash
    remoteStatus.genesisHash shouldBe genesisHash
  }

  // -------------------------------------------------------------------------
  // PeerInfo.apply for ETH/69
  // -------------------------------------------------------------------------

  it should "initialise maxBlockNumber from latestBlock, not from chainWeight.totalDifficulty" taggedAs UnitTest in {
    val actualTD = ChainWeight.totalDifficultyOnly(BigInt("999999999999999999999999999"))
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(status, Capability.ETH69, false, Nil, actualTD)
    val peerInfo = PeerInfo(remoteStatus, forkAccepted = true)

    // maxBlockNumber must be the block number, not the huge TD value
    peerInfo.maxBlockNumber shouldBe latestBlockNr
    peerInfo.maxBlockNumber should not be actualTD.totalDifficulty
  }

  it should "initialise maxBlockNumber from latestBlock when chainWeight is a block-number proxy" taggedAs UnitTest in {
    val proxy = ChainWeight.totalDifficultyOnly(latestBlockNr)
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(status, Capability.ETH69, false, Nil, proxy)
    val peerInfo = PeerInfo(remoteStatus, forkAccepted = true)

    peerInfo.maxBlockNumber shouldBe latestBlockNr
  }

  it should "default maxBlockNumber to 0 when latestBlock is absent (ETH68 path)" taggedAs UnitTest in {
    // ETH68 RemoteStatus has latestBlock = None
    val eth68Status = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1L,
      chainWeight = ChainWeight.totalDifficultyOnly(BigInt("100000000000000000000")),
      bestHash = latestHash,
      genesisHash = genesisHash
      // latestBlock defaults to None
    )
    val peerInfo = PeerInfo(eth68Status, forkAccepted = true)

    peerInfo.maxBlockNumber shouldBe BigInt(0)
  }

  it should "set chainWeight in PeerInfo from RemoteStatus chainWeight" taggedAs UnitTest in {
    val actualTD = ChainWeight.totalDifficultyOnly(BigInt("123456789000000000000000000"))
    val status = eth69Status(latestBlockNr, latestHash)
    val remoteStatus = RemoteStatus.fromETH69Status(status, Capability.ETH69, false, Nil, actualTD)
    val peerInfo = PeerInfo(remoteStatus, forkAccepted = true)

    peerInfo.chainWeight shouldBe actualTD
  }

  // -------------------------------------------------------------------------
  // ETH/68 TD handling (wire provides totalDifficulty directly)
  // -------------------------------------------------------------------------

  it should "ETH68: store totalDifficulty from wire in chainWeight" taggedAs UnitTest in {
    val wireTD = BigInt("123456789000000000000000000")
    val eth68RemoteStatus = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1L,
      chainWeight = ChainWeight.totalDifficultyOnly(wireTD),
      bestHash = latestHash,
      genesisHash = genesisHash
    )

    eth68RemoteStatus.chainWeight.totalDifficulty shouldBe wireTD
    eth68RemoteStatus.latestBlock shouldBe None
  }

  it should "ETH68: PeerInfo.apply initialises maxBlockNumber to 0 (updated reactively)" taggedAs UnitTest in {
    // ETH68 maxBlockNumber is NOT read from chainWeight — it starts at 0 and is
    // updated via peerHasUpdatedBestBlock messages as the peer announces new blocks.
    val eth68RemoteStatus = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1L,
      chainWeight = ChainWeight.totalDifficultyOnly(BigInt("100000000000000000000000000")),
      bestHash = latestHash,
      genesisHash = genesisHash
    )
    val peerInfo = PeerInfo(eth68RemoteStatus, forkAccepted = true)

    peerInfo.maxBlockNumber shouldBe BigInt(0)
    peerInfo.chainWeight.totalDifficulty shouldBe BigInt("100000000000000000000000000")
  }

  it should "ETH68: chainWeight.totalDifficulty is the actual wire TD, not a block number" taggedAs UnitTest in {
    val wireTD = BigInt("987654321000000000000000000")
    val eth68RemoteStatus = RemoteStatus(
      capability = Capability.ETH68,
      networkId = 1L,
      chainWeight = ChainWeight.totalDifficultyOnly(wireTD),
      bestHash = latestHash,
      genesisHash = genesisHash
    )
    val peerInfo = PeerInfo(eth68RemoteStatus, forkAccepted = true)

    // For ETH68 the TD comparison in BlockBroadcast uses peerInfo.chainWeight — it must be accurate
    peerInfo.chainWeight.totalDifficulty shouldBe wireTD
    peerInfo.chainWeight.totalDifficulty should be > BigInt(100_000_000L) // clearly not a block number
  }
}
