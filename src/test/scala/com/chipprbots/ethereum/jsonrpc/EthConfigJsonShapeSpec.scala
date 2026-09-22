package com.chipprbots.ethereum.jsonrpc

import org.json4s.JsonAST.JValue
import org.json4s.native.JsonMethods.compact
import org.json4s.native.JsonMethods.render
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.EthInfoService.*
import com.chipprbots.ethereum.ledger.BlockExecution
import com.chipprbots.ethereum.vm.PrecompiledContracts

/** Pins the EIP-7910 `eth_config` response shape.
  *
  * `eth_config/get-config` in execution-apis is an EXACT comparison, so this spec compares the rendered JSON verbatim
  * against that test's expected result rather than spot-checking fields. The reference chain is hive's rpc-compat
  * fixture (chainId 0xc72dd9d5e883e) whose head sits at the BPO2 activation time of 540.
  *
  * What previously shipped and what each assertion guards:
  *   - `activationBlock` as a hex quantity, where the spec wants `activationTime` as a plain JSON number
  *   - no `blobSchedule`, no `forkId`
  *   - go-ethereum's INTERNAL precompile identifiers (`ecrecover`, `bn256Add`, `bls12381G1MultiExp`) instead of the
  *     canonical UPPER_SNAKE names (`ECREC`, `BN254_ADD`, `BLS12_G1MSM`) — the mapping is not mechanical
  *   - `KZG_POINT_EVALUATION` missing entirely, despite the precompile being implemented and wired into the EVM's
  *     Cancun set
  *   - one `systemContracts` entry (`historyStorage`) instead of five
  *   - fabricated `next`/`last` objects carrying a 1e18 "never" sentinel instead of `null`
  */
class EthConfigJsonShapeSpec extends AnyFlatSpec with Matchers:

  private def renderJson(v: JValue): String = compact(render(v))

  /** Derived exactly as the service derives it: names come from the canonical table, addresses from the precompile set
    * the EVM actually runs at Osaka. A precompile that is advertised but not implemented cannot survive this.
    */
  private val osakaPrecompiles: Map[String, Address] =
    PrecompiledContracts.osakaContracts.keys
      .flatMap(addr => Eip7910PrecompileNames.get(addr).map(_ -> addr))
      .toMap

  private val bpo2Fork = ForkConfig(
    activationBlock = None,
    activationTime = Some(540L),
    chainId = BigInt("c72dd9d5e883e", 16),
    forkId = Some(BigInt("e272ecbe", 16)),
    blobSchedule = Some(BlobScheduleConfig(target = 14, max = 21, baseFeeUpdateFraction = 11684671)),
    precompiles = osakaPrecompiles,
    systemContracts = Map(
      "BEACON_ROOTS_ADDRESS" -> BlockExecution.BeaconRootContractAddress,
      "CONSOLIDATION_REQUEST_PREDEPLOY_ADDRESS" -> BlockExecution.ConsolidationQueueAddress,
      "DEPOSIT_CONTRACT_ADDRESS" -> Address(0),
      "HISTORY_STORAGE_ADDRESS" -> BlockExecution.HistoryStorageAddress,
      "WITHDRAWAL_REQUEST_PREDEPLOY_ADDRESS" -> BlockExecution.WithdrawalQueueAddress
    )
  )

  "eth_config" should "render byte-identically to the execution-apis get-config expectation" in {
    val response = ConfigResponse(current = Some(bpo2Fork), next = None, last = None)
    renderJson(EthJsonMethodsImplicits.eth_config.encodeJson(response)) shouldBe
      EthConfigJsonShapeSpec.ExpectedResult
  }

  it should "render an absent next/last fork as null rather than a sentinel object" in {
    val rendered = renderJson(
      EthJsonMethodsImplicits.eth_config.encodeJson(ConfigResponse(Some(bpo2Fork), None, None))
    )
    rendered should include("\"next\":null")
    rendered should include("\"last\":null")
    (rendered should not).include("de0b6b3a7640000") // 1e18 "never" sentinel
  }

  "the canonical precompile table" should "name every precompile the EVM runs at Osaka, and no others" in {
    // Anti-drift ratchet: advertising a precompile we do not implement is a lie, and failing to
    // advertise one we do implement is how KZG_POINT_EVALUATION went missing.
    Eip7910PrecompileNames.keySet shouldBe PrecompiledContracts.osakaContracts.keySet
    osakaPrecompiles should have size 18
  }

  it should "use the canonical EIP-7910 names, not go-ethereum's internal identifiers" in {
    (osakaPrecompiles.keySet should contain).allOf(
      "ECREC",
      "ID",
      "BN254_ADD",
      "BN254_MUL",
      "BN254_PAIRING",
      "BLS12_G1MSM",
      "BLS12_G2MSM",
      "BLS12_PAIRING_CHECK",
      "BLS12_MAP_FP_TO_G1",
      "BLS12_MAP_FP2_TO_G2",
      "KZG_POINT_EVALUATION",
      "P256VERIFY"
    )
    osakaPrecompiles.keySet.filter(_.exists(_.isLower)) shouldBe empty
  }

  "a block-gated (ETC) fork entry" should "keep reporting activationBlock, with no forkId or blobSchedule" in {
    // ETC/Mordor is block-gated; EIP-7910's timestamp fields do not apply and must not appear.
    val etcFork = ForkConfig(
      activationBlock = Some(BigInt(27)),
      activationTime = None,
      chainId = BigInt(61),
      forkId = None,
      blobSchedule = None,
      precompiles = Map("ecrecover" -> PrecompiledContracts.EcDsaRecAddr),
      systemContracts = Map.empty
    )
    val rendered = renderJson(EthJsonMethodsImplicits.eth_config.encodeJson(ConfigResponse(Some(etcFork), None, None)))
    rendered should include("\"activationBlock\":\"0x1b\"")
    (rendered should not).include("activationTime")
    (rendered should not).include("forkId")
    (rendered should not).include("blobSchedule")
  }

object EthConfigJsonShapeSpec:
  /** Verbatim `result` object from execution-apis tests/eth_config/get-config.io. */
  val ExpectedResult: String =
    "{\"current\":{\"activationTime\":540,\"blobSchedule\":{\"target\":14,\"max\":21,\"baseFeeUpdateFraction\":11684671},\"chainId\":\"0xc72dd9d5e883e\",\"forkId\":\"0xe272ecbe\",\"precompiles\":{\"BLAKE2F\":\"0x0000000000000000000000000000000000000009\",\"BLS12_G1ADD\":\"0x000000000000000000000000000000000000000b\",\"BLS12_G1MSM\":\"0x000000000000000000000000000000000000000c\",\"BLS12_G2ADD\":\"0x000000000000000000000000000000000000000d\",\"BLS12_G2MSM\":\"0x000000000000000000000000000000000000000e\",\"BLS12_MAP_FP2_TO_G2\":\"0x0000000000000000000000000000000000000011\",\"BLS12_MAP_FP_TO_G1\":\"0x0000000000000000000000000000000000000010\",\"BLS12_PAIRING_CHECK\":\"0x000000000000000000000000000000000000000f\",\"BN254_ADD\":\"0x0000000000000000000000000000000000000006\",\"BN254_MUL\":\"0x0000000000000000000000000000000000000007\",\"BN254_PAIRING\":\"0x0000000000000000000000000000000000000008\",\"ECREC\":\"0x0000000000000000000000000000000000000001\",\"ID\":\"0x0000000000000000000000000000000000000004\",\"KZG_POINT_EVALUATION\":\"0x000000000000000000000000000000000000000a\",\"MODEXP\":\"0x0000000000000000000000000000000000000005\",\"P256VERIFY\":\"0x0000000000000000000000000000000000000100\",\"RIPEMD160\":\"0x0000000000000000000000000000000000000003\",\"SHA256\":\"0x0000000000000000000000000000000000000002\"},\"systemContracts\":{\"BEACON_ROOTS_ADDRESS\":\"0x000f3df6d732807ef1319fb7b8bb8522d0beac02\",\"CONSOLIDATION_REQUEST_PREDEPLOY_ADDRESS\":\"0x0000bbddc7ce488642fb579f8b00f3a590007251\",\"DEPOSIT_CONTRACT_ADDRESS\":\"0x0000000000000000000000000000000000000000\",\"HISTORY_STORAGE_ADDRESS\":\"0x0000f90827f1c53a10cb7a02335b175320002935\",\"WITHDRAWAL_REQUEST_PREDEPLOY_ADDRESS\":\"0x00000961ef480eb55e80d19ad83579a64c007002\"}},\"next\":null,\"last\":null}"
