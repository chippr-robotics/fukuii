package com.chipprbots.ethereum.blockchain.data

import org.apache.pekko.util.ByteString

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.testing.Tags._

class BootstrapCheckpointLoaderSpec extends AnyFlatSpec with Matchers with MockFactory {

  "BootstrapCheckpointLoader.loadBootstrapCheckpoints" should "return false when checkpoints are disabled" taggedAs (
    UnitTest,
    SyncTest
  ) in {
    val mockReader = mock[BlockchainReader]
    val mockAppStateStorage = mock[AppStateStorage]
    val loader = new BootstrapCheckpointLoader(mockReader, mockAppStateStorage)

    implicit val config: BlockchainConfig = createTestConfig(
      useBootstrapCheckpoints = false,
      bootstrapCheckpoints = List.empty
    )

    val result = loader.loadBootstrapCheckpoints()

    result shouldBe false
  }

  it should "return false when no checkpoints are configured" taggedAs (UnitTest, SyncTest) in {
    val mockReader = mock[BlockchainReader]
    val mockAppStateStorage = mock[AppStateStorage]
    val loader = new BootstrapCheckpointLoader(mockReader, mockAppStateStorage)

    implicit val config: BlockchainConfig = createTestConfig(
      useBootstrapCheckpoints = true,
      bootstrapCheckpoints = List.empty
    )

    val result = loader.loadBootstrapCheckpoints()

    result shouldBe false
  }

  it should "return false when blockchain already has blocks beyond genesis" taggedAs (UnitTest, SyncTest) in {
    val mockReader = mock[BlockchainReader]
    val mockAppStateStorage = mock[AppStateStorage]
    val loader = new BootstrapCheckpointLoader(mockReader, mockAppStateStorage)

    (mockReader.getBestBlockNumber _).expects().returning(BigInt(100))

    implicit val config: BlockchainConfig = createTestConfig(
      useBootstrapCheckpoints = true,
      bootstrapCheckpoints = List(
        BootstrapCheckpoint(BigInt(1000), ByteString(Array.fill[Byte](32)(0x01)))
      )
    )

    val result = loader.loadBootstrapCheckpoints()

    result shouldBe false
  }

  it should "return true when checkpoints are loaded successfully" taggedAs (UnitTest, SyncTest) in {
    val mockReader = mock[BlockchainReader]
    val mockAppStateStorage = mock[AppStateStorage]
    val mockDataSource = mock[com.chipprbots.ethereum.db.dataSource.DataSource]
    val loader = new BootstrapCheckpointLoader(mockReader, mockAppStateStorage)

    (mockReader.getBestBlockNumber _).expects().returning(BigInt(0))
    
    import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
    val stubUpdate = DataSourceBatchUpdate(mockDataSource, Array.empty)
    (mockAppStateStorage.putBootstrapPivotBlock _)
      .expects(BigInt(3000), ByteString(Array.fill[Byte](32)(0x03)))
      .returning(stubUpdate)
    (mockDataSource.update _).expects(*).returning(())

    implicit val config: BlockchainConfig = createTestConfig(
      useBootstrapCheckpoints = true,
      bootstrapCheckpoints = List(
        BootstrapCheckpoint(BigInt(1000), ByteString(Array.fill[Byte](32)(0x01))),
        BootstrapCheckpoint(BigInt(2000), ByteString(Array.fill[Byte](32)(0x02))),
        BootstrapCheckpoint(BigInt(3000), ByteString(Array.fill[Byte](32)(0x03)))
      )
    )

    val result = loader.loadBootstrapCheckpoints()

    result shouldBe true
  }

  it should "handle single checkpoint" taggedAs (UnitTest, SyncTest) in {
    val mockReader = mock[BlockchainReader]
    val mockAppStateStorage = mock[AppStateStorage]
    val mockDataSource = mock[com.chipprbots.ethereum.db.dataSource.DataSource]
    val loader = new BootstrapCheckpointLoader(mockReader, mockAppStateStorage)

    (mockReader.getBestBlockNumber _).expects().returning(BigInt(0))

    import com.chipprbots.ethereum.db.dataSource.DataSourceBatchUpdate
    val stubUpdate = DataSourceBatchUpdate(mockDataSource, Array.empty)
    (mockAppStateStorage.putBootstrapPivotBlock _)
      .expects(BigInt(10500839), ByteString(Array.fill[Byte](32)(0xff.toByte)))
      .returning(stubUpdate)
    (mockDataSource.update _).expects(*).returning(())

    implicit val config: BlockchainConfig = createTestConfig(
      useBootstrapCheckpoints = true,
      bootstrapCheckpoints = List(
        BootstrapCheckpoint(BigInt(10500839), ByteString(Array.fill[Byte](32)(0xff.toByte)))
      )
    )

    val result = loader.loadBootstrapCheckpoints()

    result shouldBe true
  }

  // Helper method to create test config
  private def createTestConfig(
      useBootstrapCheckpoints: Boolean,
      bootstrapCheckpoints: List[BootstrapCheckpoint]
  ): BlockchainConfig = {
    import com.chipprbots.ethereum.domain.Address
    import com.chipprbots.ethereum.utils.{ForkBlockNumbers, MonetaryPolicyConfig}

    BlockchainConfig(
      powTargetTime = None,
      forkBlockNumbers = ForkBlockNumbers.Empty,
      treasuryAddress = Address(0),
      maxCodeSize = None,
      customGenesisFileOpt = None,
      customGenesisJsonOpt = None,
      daoForkConfig = None,
      accountStartNonce = com.chipprbots.ethereum.domain.UInt256.Zero,
      chainId = 61.toByte,
      networkId = 1,
      monetaryPolicyConfig = MonetaryPolicyConfig(
        firstEraBlockReward = BigInt(5000000000000000000L),
        firstEraReducedBlockReward = BigInt(3000000000000000000L),
        firstEraConstantinopleReducedBlockReward = BigInt(2000000000000000000L),
        eraDuration = 5000000,
        rewardReductionRate = 0.2
      ),
      gasTieBreaker = false,
      ethCompatibleStorage = true,
      bootstrapNodes = Set.empty,
      checkpointPubKeys = Set.empty,
      allowedMinersPublicKeys = Set.empty,
      capabilities = List.empty,
      bootstrapCheckpoints = bootstrapCheckpoints,
      useBootstrapCheckpoints = useBootstrapCheckpoints
    )
  }
}
