package com.chipprbots.ethereum.blockchain.data

import org.apache.pekko.util.ByteString

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigFactory
import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.NetworkType

/** Hoodi, built from the SHIPPED configuration (spec 028 S1, FR-018 to FR-020).
  *
  * Ground truth, independent of this implementation:
  *   - genesis hash, state root and every header field: block 0 from `eth_getBlockByNumber 0x0` on
  *     https://rpc.hoodi.ethpandaops.io and https://ethereum-hoodi-rpc.publicnode.com (both agree, 2026-11-12); the
  *     hash is also go-ethereum's `params.HoodiGenesisHash` and eth-clients/hoodi
  *   - chain and network id 560048, fork schedule: eth-clients/hoodi metadata/genesis.json and go-ethereum
  *     `params.HoodiChainConfig`
  *
  * `hoodi-genesis.json` is a re-encoding of the upstream file: upstream writes `timestamp` as the DECIMAL string
  * "1742212800", which `GenesisDataLoader.prepareHeader` parses as hex, and `mixhash` in lowercase, which the loader
  * does not bind. Loading it verbatim builds a wrong header and a genesis hash no peer accepts, with no error; the hash
  * assertion is what notices.
  */
class HoodiGenesisSpec extends AnyFlatSpec with Matchers:

  private val GenesisHash = "bbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b"
  // eth_getBlockByNumber("0x0") on rpc.hoodi.ethpandaops.io and ethereum-hoodi-rpc.publicnode.com, 2026-11-12.
  private val GenesisStateRoot = "da87d7f5f91c51508791bbcbd4aa5baf04917830b86985eeb9ad3d5bfb657576"
  private val GenesisTimestamp = 1742212800L // 0x67d80ec0, 2025-03-17 12:00:00 UTC

  private lazy val shippedRoot: TypesafeConfig =
    ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()

  private lazy val hoodi: BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig("fukuii.blockchains.hoodi"))

  private class Env extends EphemBlockchainTestSetup

  "the shipped hoodi chain config" should "carry the published chain and network ids" taggedAs (UnitTest) in {
    hoodi.chainId shouldBe ChainId(BigInt(560048))
    hoodi.networkId shouldBe 560048L
    hoodi.networkType shouldBe NetworkType.ETH
    hoodi.terminalTotalDifficulty shouldBe Some(BigInt(0))
    shippedRoot.getString("fukuii.blockchains.hoodi.genesis-hash") shouldBe s"0x$GenesisHash"
  }

  it should "carry the published fork schedule and no Amsterdam" taggedAs (UnitTest) in {
    val ft = hoodi.forkTimestamps
    ft.shanghaiTimestamp shouldBe Some(0L)
    ft.cancunTimestamp shouldBe Some(0L)
    ft.pragueTimestamp shouldBe Some(1742999832L)
    ft.osakaTimestamp shouldBe Some(1761677592L)
    ft.bpo1Timestamp shouldBe Some(1762365720L)
    ft.bpo2Timestamp shouldBe Some(1762955544L)
    ft.amsterdamTimestamp shouldBe None
    hoodi.forkBlockNumbers.olympiaBlockNumber shouldBe BigInt(0)
    hoodi.depositContractAddress shouldBe Some(Address("0x00000000219ab540356cBB839Cbe05303d7705Fa"))
  }

  "GenesisDataLoader" should "build the published Hoodi genesis block from the shipped genesis" taggedAs (
    UnitTest
  ) in {
    val env = new Env
    // Hoodi's alloc has THREE accounts with storage (deposit contract, 7002, 7251). The load is not refused for that
    // (unifiedLayoutRefusal refuses only a duplicate address, or storage on a non-eth-compatible chain); countsExact is
    // simply false. A refusal would throw here.
    new GenesisDataLoader(
      env.blockchainReader,
      env.blockchainWriter,
      env.storagesInstance.storages.evmCodeStorage,
      env.storagesInstance.storages.stateStorage
    ).loadGenesisData()(using hoodi)

    val header = env.blockchainReader.getBlockHeaderByNumber(0).getOrElse(fail("no genesis header was written"))

    // State first: a state-root mismatch means the alloc was read wrongly; a hash mismatch with a matching state root
    // means a header field (shape, timestamp, nonce, base fee, requests hash) is wrong.
    header.stateRoot.value shouldBe ByteString(Hex.decode(GenesisStateRoot))
    header.unixTimestamp shouldBe Timestamp(GenesisTimestamp)
    header.gasLimit shouldBe GasAmount(BigInt("2255100", 16))
    header.nonce shouldBe ByteString(Hex.decode("0000000000001234"))
    header.difficulty shouldBe Difficulty(1)
    header.baseFee shouldBe Some(BigInt(1000000000))
    header.hash.value shouldBe ByteString(Hex.decode(GenesisHash))
  }
