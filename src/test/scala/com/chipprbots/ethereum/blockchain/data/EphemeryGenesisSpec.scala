package com.chipprbots.ethereum.blockchain.data

import java.io.File

import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.forkid.ForkId
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlobParams
import com.chipprbots.ethereum.utils.BlobSchedule
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.BlockchainsConfig
import com.chipprbots.ethereum.utils.NetworkType

/** Ephemery, the periodically resetting ETH testnet, loaded the way an operator runs it: through
  * `fukuii.blockchains.custom-chains-dir`, from the files `scripts/ephemery/fetch-genesis.sh` GENERATES.
  *
  * Nothing iteration-specific is shipped in `src/main` (each 28-day reset changes chain id, genesis and timestamps).
  * The fixture `src/test/resources/ephemery/ephemery-164/` is the script's verbatim output for release `ephemery-164`
  * (`fetch-genesis.sh --from-dir <extracted testnet-all.tar.gz>`), so this spec checks the generator's mapping and the
  * genesis re-encoding against ground truth that is independent of fukuii:
  *   - genesis hash 0x39c91f8e…68f0: the release's `deposit_contract_block_hash.txt` / `nodevars_env.txt`
  *     GENESIS_BLOCK, and block 0 on the live network (`eth_getBlockByNumber 0x0` on https://otter.bordel.wtf/erigon,
  *     2026-10-17), which also gives the state root and every header field asserted below
  *   - chain id 0x259c754 = 39438164 (`eth_chainId`; 39438000 + iteration)
  *   - fork schedule: the release's genesis.json `config` (shanghai..osakaTime 0, bpo1Time 1791063432, bpo2Time
  *     1791849864)
  *   - fork id 0x33ef7281 after BPO2: the live network's `eth_config` → `current.forkId` (2026-10-17); the two earlier
  *     checksums were computed independently with Python `zlib.crc32` over the genesis hash then each fork timestamp as
  *     a big-endian uint64, which reproduces the live value
  *
  * The genesis hash is the assertion that matters: upstream genesis.json writes `timestamp` as the DECIMAL string
  * "1790276400" and `GenesisDataLoader.prepareHeader` parses it as hex, so a generator that skipped the re-encoding
  * would yield a wrong hash with no error.
  */
class EphemeryGenesisSpec extends AnyFlatSpec with Matchers:

  private val GenesisHash = "39c91f8ef961470061e5f6eb1e2d8a05c79f630a41644cefb17397960c4868f0"
  private val GenesisStateRoot = "ee0a93304bf4208548d10132a40d760072c81e1375847ac1a70b88acdacbd9a1"
  private val ErigonRawGenesis =
    "f90265f9025fa00000000000000000000000000000000000000000000000000000000000000000a01dcc4de8dec75d7aab85" +
      "b567b6ccd41ad312451b948a7413f0a142fd40d49347940000000000000000000000000000000000000000a0ee0a93304bf4" +
      "208548d10132a40d760072c81e1375847ac1a70b88acdacbd9a1a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cad" +
      "c001622fb5e363b421a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421b901000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000" +
      "000180840225510080846ab5733080a000000000000000000000000000000000000000000000000000000000000000008800" +
      "00000000001234843b9aca00a056e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b4218080a00000" +
      "000000000000000000000000000000000000000000000000000000000000a0e3b0c44298fc1c149afbf4c8996fb92427ae41" +
      "e4649b934ca495991b7852b855c0c0c0"
  private val ChainId = 39438164L
  private val GenesisTimestamp = 1790276400L // 2026-09-24 19:00:00 UTC
  private val Bpo1Timestamp = 1791063432L // genesis + 600 s CL delay + epoch 2048
  private val Bpo2Timestamp = 1791849864L // epoch 4096

  private lazy val fixtureDir: String =
    new File(getClass.getResource("/ephemery/ephemery-164").toURI).getAbsolutePath

  /** The `fukuii.blockchains` block as the operator conf produces it: network + custom-chains-dir. */
  private def blockchainsFor(network: String, chainsDir: String): BlockchainsConfig =
    BlockchainsConfig(
      ConfigFactory
        .empty()
        .withValue("network", ConfigValueFactory.fromAnyRef(network))
        .withValue("custom-chains-dir", ConfigValueFactory.fromAnyRef(chainsDir))
    )

  private lazy val ephemery: BlockchainConfig = blockchainsFor("ephemery", fixtureDir).blockchainConfig

  private class Env extends EphemBlockchainTestSetup

  "the shipped conf/ephemery.conf" should "select the ephemery network, engine-api and the script's default dir" taggedAs (
    UnitTest
  ) in {
    val conf = ConfigFactory.load(ConfigFactory.parseResources("conf/ephemery.conf"))
    conf.getString("fukuii.blockchains.network") shouldBe "ephemery"
    conf.getString("fukuii.mining.protocol") shouldBe "engine-api"
    conf.getString("fukuii.blockchains.custom-chains-dir") shouldBe
      s"${System.getProperty("user.home")}/.fukuii/ephemery-network/current/chains"
  }

  it should "resolve the ephemery chain once custom-chains-dir points at a generated iteration" taggedAs (UnitTest) in {
    val conf = ConfigFactory
      .load(ConfigFactory.parseResources("conf/ephemery.conf"))
      .withValue("fukuii.blockchains.custom-chains-dir", ConfigValueFactory.fromAnyRef(fixtureDir))
    BlockchainsConfig(conf.getConfig("fukuii.blockchains")).blockchainConfig.chainId shouldBe
      com.chipprbots.ethereum.domain.ChainId(BigInt(ChainId))
  }

  it should "fail loudly, naming the fix, when no iteration has been generated" taggedAs (UnitTest) in {
    val missing = new File(fixtureDir, "does-not-exist").getAbsolutePath
    val ex = intercept[NoSuchElementException](blockchainsFor("ephemery", missing))
    ex.getMessage should include("'ephemery' is not configured")
    ex.getMessage should include("fetch-genesis.sh")
  }

  "the generated ephemery-164 chain config" should "carry the iteration's chain and network ids" taggedAs (
    UnitTest
  ) in {
    ephemery.chainId shouldBe com.chipprbots.ethereum.domain.ChainId(BigInt(ChainId))
    ephemery.networkId shouldBe ChainId
    ephemery.networkType shouldBe NetworkType.ETH
    ephemery.terminalTotalDifficulty shouldBe Some(BigInt(0))
    ephemery.depositContractAddress shouldBe Some(Address("0x00000000219ab540356cBB839Cbe05303d7705Fa"))
    ephemery.forkBlockNumbers.olympiaBlockNumber shouldBe BigInt(0) // London (EIP-1559) at genesis
  }

  it should "activate Shanghai..Osaka at genesis, BPO1/BPO2 at their release timestamps, and never Amsterdam" taggedAs (
    UnitTest
  ) in {
    val ft = ephemery.forkTimestamps
    Seq(ft.shanghaiTimestamp, ft.cancunTimestamp, ft.pragueTimestamp, ft.osakaTimestamp).foreach(_ shouldBe Some(0L))
    ft.bpo1Timestamp shouldBe Some(Bpo1Timestamp)
    ft.bpo2Timestamp shouldBe Some(Bpo2Timestamp)
    ft.amsterdamTimestamp shouldBe None
    ephemery.isBpo1Timestamp(Timestamp(Bpo1Timestamp - 1)) shouldBe false
    ephemery.isBpo1Timestamp(Timestamp(Bpo1Timestamp)) shouldBe true
    ephemery.isBpo2Timestamp(Timestamp(Bpo2Timestamp)) shouldBe true
    ephemery.isAmsterdamTimestamp(Timestamp(Long.MaxValue)) shouldBe false
  }

  it should "carry the release's blobSchedule, not the mainnet one (#1637)" taggedAs (UnitTest) in {
    // genesis.json config.blobSchedule of ephemery-164, verbatim. Its osaka entry equals Prague's and is accepted as
    // inherited; bpo1/bpo2 are NOT mainnet's 10/15 and 14/21.
    ephemery.blobSchedule shouldBe BlobSchedule(
      cancun = Some(BlobParams(3, 6, BigInt(3338477))),
      prague = Some(BlobParams(6, 9, BigInt(5007716))),
      bpo1 = Some(BlobParams(8, 12, BigInt(6676955))),
      bpo2 = Some(BlobParams(10, 15, BigInt(8346193)))
    )
    ephemery.blobParamsAt(Timestamp(GenesisTimestamp)) shouldBe Some(BlobParams(6, 9, BigInt(5007716)))
    ephemery.blobParamsAt(Timestamp(Bpo1Timestamp - 1)) shouldBe Some(BlobParams(6, 9, BigInt(5007716)))
    ephemery.blobParamsAt(Timestamp(Bpo1Timestamp)) shouldBe Some(BlobParams(8, 12, BigInt(6676955)))
    ephemery.blobParamsAt(Timestamp(Bpo2Timestamp - 1)) shouldBe Some(BlobParams(8, 12, BigInt(6676955)))
    ephemery.blobParamsAt(Timestamp(Bpo2Timestamp)) shouldBe Some(BlobParams(10, 15, BigInt(8346193)))
  }

  "GenesisDataLoader" should "build the published ephemery-164 genesis block from the generated genesis" taggedAs (
    UnitTest
  ) in {
    val env = new Env
    new GenesisDataLoader(
      env.blockchainReader,
      env.blockchainWriter,
      env.storagesInstance.storages.evmCodeStorage,
      env.storagesInstance.storages.stateStorage
    ).loadGenesisData()(using ephemery)

    val header = env.blockchainReader.getBlockHeaderByNumber(0).getOrElse(fail("no genesis header was written"))

    // State first: a state-root mismatch means the alloc was read wrongly; a hash mismatch with a matching state root
    // means a header field (shape, timestamp, nonce, difficulty, base fee, requests hash) is wrong.
    header.stateRoot.value shouldBe ByteString(Hex.decode(GenesisStateRoot))
    header.unixTimestamp shouldBe Timestamp(GenesisTimestamp)
    header.gasLimit shouldBe GasAmount(BigInt("2255100", 16)) // 36,000,000
    header.difficulty shouldBe Difficulty(BigInt(1))
    header.nonce shouldBe ByteString(Hex.decode("0000000000001234"))
    header.baseFee shouldBe Some(BigInt(1000000000)) // 0x3b9aca00
    header.extraFields shouldBe a[BlockHeader.HeaderExtraFields.HefPostPrague]
    header.requestsHash shouldBe Some(
      ByteString(Hex.decode("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
    )
    header.hash.value shouldBe ByteString(Hex.decode(GenesisHash))

    // CQ-020-07: the whole stored block, body included, byte-for-byte as Erigon serves it (debug_getRawBlock 0x0 on the
    // live ephemery-164 network, 616 bytes). The body is [[], [], []]: Shanghai at genesis needs the empty withdrawals
    // list, which the loader used to leave out (615 bytes, c0c0).
    import com.chipprbots.ethereum.domain.Block.BlockEnc
    val block = env.blockchainReader.getBlockByHash(header.hash).getOrElse(fail("no genesis body was written"))
    Hex.toHexString(block.toBytes) shouldBe ErigonRawGenesis
    block.toBytes.length shouldBe 616
  }

  "ForkId for ephemery-164" should "keep both post-genesis BPO forks in the checksum chain" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    ForkId.gatherBlockForks(ephemery) shouldBe empty
    ForkId.gatherTimestampForks(ephemery, GenesisTimestamp) shouldBe List(BigInt(Bpo1Timestamp), BigInt(Bpo2Timestamp))
  }

  it should "match the checksums the live network advertises at each fork" taggedAs (UnitTest, NetworkTest) in {
    val genesisHash = ByteString(Hex.decode(GenesisHash))
    def create(ts: Long): ForkId = ForkId.create(genesisHash, GenesisTimestamp, ephemery)(BigInt(0), ts)
    create(GenesisTimestamp) shouldBe ForkId(0xaa3371beL, Some(BigInt(Bpo1Timestamp)))
    create(Bpo1Timestamp) shouldBe ForkId(0x21e55fe6L, Some(BigInt(Bpo2Timestamp)))
    create(Bpo2Timestamp) shouldBe ForkId(0x33ef7281L, None)
  }
