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

/** Platåberget, the public Glamsterdam testnet, built from the SHIPPED configuration.
  *
  * Ground truth, all independent of this implementation:
  *   - genesis hash / state root: https://plataberget.dev/ and the live network's block 0 (`eth_getBlockByNumber 0x0`
  *     on https://rpc.plataberget.ethpandaops.io, 2026-09-24), which also gives every header field asserted below
  *   - chain id 0x1a6a8cc6e (`eth_chainId`), network id 7091047534 (`net_version`)
  *   - fork schedule: ethpandaops/glamsterdam-devnets network-configs/devnet-8/metadata/genesis.json
  *
  * The genesis is the case this spec exists for. `plataberget-genesis.json` is a re-encoding of the upstream file —
  * upstream writes `timestamp` as the DECIMAL string "1786622400" and `GenesisDataLoader.prepareHeader` parses that
  * field as hex, so loading the upstream file verbatim builds a header with timestamp 0x1786622400 (101,038,826,496 —
  * the year 5171) and a genesis hash no peer will ever accept, with no error. The hash assertion is the only thing that
  * notices.
  */
class PlatabergetGenesisSpec extends AnyFlatSpec with Matchers:

  private val GenesisHash = "ee33ef92bbabcf07bcf44fea1d18a7925c5f7f9da8f81334ea19b0f3cb892b31"
  private val GenesisStateRoot = "9a527aee5d0f6b0c73b240e77c134cae1fd47ceaa90db223edd598866e269c47"
  private val GenesisTimestamp = 1786622400L // 2026-08-13 12:00:00 UTC
  private val AmsterdamTimestamp = 1787212224L // 2026-08-20 07:50:24 UTC, Gloas epoch 1536

  /** Loaded the way the application loads it (see ChainConfigMatrixSpec): `blockchains.conf` mounted at `fukuii`, so
    * the chain file's `include required(...)` of the genesis JSON resolves exactly as at runtime.
    */
  private lazy val shippedRoot: TypesafeConfig =
    ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()

  private lazy val plataberget: BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig("fukuii.blockchains.plataberget"))

  private class Env extends EphemBlockchainTestSetup

  "the shipped plataberget chain config" should "carry the published chain and network ids" taggedAs (UnitTest) in {
    // 7091047534 does not fit in an Int; both ids must survive parsing at full width.
    plataberget.chainId shouldBe ChainId(BigInt(7091047534L))
    plataberget.networkId shouldBe 7091047534L
    plataberget.networkType shouldBe NetworkType.ETH
    plataberget.terminalTotalDifficulty shouldBe Some(BigInt(0))
  }

  it should "activate every fork through BPO2 at genesis and Amsterdam at its published timestamp" taggedAs (
    UnitTest
  ) in {
    val ft = plataberget.forkTimestamps
    Seq(
      ft.shanghaiTimestamp,
      ft.cancunTimestamp,
      ft.pragueTimestamp,
      ft.osakaTimestamp,
      ft.bpo1Timestamp,
      ft.bpo2Timestamp
    )
      .foreach(_ shouldBe Some(0L))
    ft.amsterdamTimestamp shouldBe Some(AmsterdamTimestamp)
    plataberget.isAmsterdamTimestamp(Timestamp(GenesisTimestamp)) shouldBe false
    plataberget.isAmsterdamTimestamp(Timestamp(AmsterdamTimestamp - 1)) shouldBe false
    plataberget.isAmsterdamTimestamp(Timestamp(AmsterdamTimestamp)) shouldBe true
    plataberget.forkBlockNumbers.olympiaBlockNumber shouldBe BigInt(0)
    plataberget.depositContractAddress shouldBe Some(Address("0x00000000219ab540356cBB839Cbe05303d7705Fa"))
  }

  "GenesisDataLoader" should "build the published Platåberget genesis block from the shipped genesis" taggedAs (
    UnitTest
  ) in {
    val env = new Env
    new GenesisDataLoader(
      env.blockchainReader,
      env.blockchainWriter,
      env.storagesInstance.storages.evmCodeStorage,
      env.storagesInstance.storages.stateStorage
    ).loadGenesisData()(using plataberget)

    val header = env.blockchainReader.getBlockHeaderByNumber(0).getOrElse(fail("no genesis header was written"))

    // State first: a state-root mismatch means the alloc was read wrongly; a hash mismatch with a matching state root
    // means a header field (shape, timestamp, nonce, base fee, requests hash) is wrong.
    header.stateRoot.value shouldBe ByteString(Hex.decode(GenesisStateRoot))
    header.unixTimestamp shouldBe Timestamp(GenesisTimestamp)
    header.gasLimit shouldBe GasAmount(BigInt("3938700", 16)) // 60,000,000
    header.nonce shouldBe ByteString(Hex.decode("0000000000001234"))
    header.baseFee shouldBe Some(BigInt(1000000000)) // 0x3b9aca00
    header.hash.value shouldBe ByteString(Hex.decode(GenesisHash))
  }
