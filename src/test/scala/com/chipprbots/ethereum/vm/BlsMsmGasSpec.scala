package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EtcForks
import com.chipprbots.ethereum.vm.BlockchainConfigForEvm.EthForks
import com.chipprbots.ethereum.vm.PrecompiledContracts.*

/** EIP-2537 G1/G2 MSM gas, k = 1..129, against numbers taken from execution-spec-tests v5.4.0 rather than from the
  * discount tables themselves (a test that re-typed the table would share any typo with the code).
  *
  * Oracle: `tests/prague/eip2537_bls_12_381_precompiles/test_bls12_variable_length_input_contracts.py`, fixtures
  * `test_valid_gas_g1msm` and `test_valid_gas_g2msm` (`full_discount_table`). Each deploys a contract that CALLs 0x0c /
  * 0x0e once per k = 1..129 with input length k x 160 / k x 288 and gas EXACTLY equal to the EIP cost; the lists below
  * are those CALL gas immediates, read out of the fixture bytecode in order. Their `test_invalid_gas_*` twins pass the
  * same values minus one.
  *
  * WHY THIS SPEC EXISTS. On 33b730e75 hive consume-rlp failed `test_invalid_gas_g1msm` ("block number mismatch in last
  * block: got 0x00, expected 0x01"): fukuii REJECTED a valid block. The G1 discount table diverged from the EIP at 116
  * of 128 entries (from k = 6), so for 47 values of k the (cost - 1) call succeeded, wrote its success flag, and moved
  * the state root off the fixture's. The G2 table diverged at 117 entries (from k = 12), all overcharging.
  *
  * Gas does not depend on the input bytes, only on their length, so zero-filled input is exact here.
  */
class BlsMsmGasSpec extends AnyFlatSpec with Matchers:

  private val etc = EtcForks.Olympia
  private val eth = EthForks.Berlin

  /** test_valid_gas_g1msm: exact gas for k = 1..129 pairs of 160 bytes. */
  private val g1MsmFixtureGas: Seq[Long] = Seq(
    12000L, 22776L, 30528L, 38256L, 45840L, 54000L, 61992L, 69888L, 77652L, 85440L, 93060L, 100512L, 107952L, 115416L,
    122760L, 129984L, 137292L, 144504L, 151620L, 158640L, 165816L, 172656L, 179676L, 186624L, 193500L, 200304L, 207360L,
    214032L, 220980L, 227520L, 234360L, 240768L, 247500L, 254184L, 260820L, 267408L, 273948L, 280440L, 286884L, 293280L,
    299628L, 306432L, 312696L, 318912L, 325620L, 331752L, 337836L, 344448L, 350448L, 357000L, 362916L, 369408L, 375876L,
    381672L, 388080L, 393792L, 400140L, 406464L, 412056L, 418320L, 424560L, 430776L, 436212L, 442368L, 448500L, 454608L,
    460692L, 466752L, 471960L, 477960L, 483936L, 489888L, 495816L, 501720L, 507600L, 513456L, 519288L, 525096L, 530880L,
    536640L, 542376L, 548088L, 553776L, 559440L, 565080L, 570696L, 576288L, 581856L, 587400L, 592920L, 598416L, 603888L,
    610452L, 615888L, 621300L, 626688L, 632052L, 637392L, 642708L, 648000L, 654480L, 659736L, 664968L, 670176L, 675360L,
    681792L, 686940L, 692064L, 697164L, 702240L, 708624L, 713664L, 718680L, 723672L, 728640L, 734976L, 739908L, 744816L,
    749700L, 756000L, 760848L, 765672L, 770472L, 776736L, 781500L, 786240L, 792480L, 797184L, 803412L
  )

  /** test_valid_gas_g2msm: exact gas for k = 1..129 pairs of 288 bytes. */
  private val g2MsmFixtureGas: Seq[Long] = Seq(
    22500L, 45000L, 62302L, 79560L, 96187L, 112320L, 127890L, 143280L, 158355L, 173250L, 187852L, 202230L, 216450L,
    230580L, 244350L, 258120L, 271957L, 285120L, 298822L, 311850L, 325080L, 338085L, 351382L, 363960L, 376875L, 389610L,
    402772L, 415170L, 427387L, 440100L, 452677L, 465120L, 477427L, 489600L, 501637L, 513540L, 526140L, 537795L, 550192L,
    561600L, 573795L, 585900L, 597915L, 608850L, 620662L, 632385L, 644017L, 655560L, 668115L, 679500L, 690795L, 702000L,
    713115L, 725355L, 736312L, 747180L, 759240L, 769950L, 781897L, 792450L, 804285L, 814680L, 826402L, 838080L, 848250L,
    859815L, 871335L, 881280L, 892687L, 904050L, 915367L, 925020L, 936225L, 947385L, 958500L, 969570L, 980595L, 991575L,
    1000732L, 1011600L, 1022422L, 1033200L, 1043932L, 1054620L, 1065262L, 1075860L, 1086412L, 1096920L, 1107382L,
    1117800L, 1130220L, 1140570L, 1150875L, 1161135L, 1171350L, 1181520L, 1191645L, 1201725L, 1213987L, 1224000L,
    1233967L, 1243890L, 1253767L, 1265940L, 1275750L, 1285515L, 1295235L, 1304910L, 1316992L, 1326600L, 1336162L,
    1348200L, 1357695L, 1367145L, 1376550L, 1388520L, 1397857L, 1407150L, 1419075L, 1428300L, 1437480L, 1449360L,
    1458472L, 1467540L, 1479375L, 1488375L, 1497330L, 1509120L, 1520910L
  )

  "BlsG1MultiExp.gas" should "equal the execution-spec-tests cost for every k = 1..129" taggedAs (
    VMTest,
    OlympiaTest
  ) in {
    g1MsmFixtureGas should have size 129
    val mismatches = g1MsmFixtureGas.zipWithIndex.flatMap { case (expected, i) =>
      val k = i + 1
      val actual = BlsG1MultiExp.gas(ByteString(new Array[Byte](k * 160)), etc, eth)
      if actual == BigInt(expected) then None else Some((k, expected, actual))
    }
    mismatches shouldBe empty
  }

  "BlsG2MultiExp.gas" should "equal the execution-spec-tests cost for every k = 1..129" taggedAs (
    VMTest,
    OlympiaTest
  ) in {
    g2MsmFixtureGas should have size 129
    val mismatches = g2MsmFixtureGas.zipWithIndex.flatMap { case (expected, i) =>
      val k = i + 1
      val actual = BlsG2MultiExp.gas(ByteString(new Array[Byte](k * 288)), etc, eth)
      if actual == BigInt(expected) then None else Some((k, expected, actual))
    }
    mismatches shouldBe empty
  }
