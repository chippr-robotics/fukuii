package com.chipprbots.ethereum.transactions

import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.NetworkType

/** B-324: ECIP-1121 brings EIP-7702 ("Set EOA account code", transaction type 0x04) to ETC at Olympia, a block-number
  * fork. The pool and RPC gate ([[TxTypeGate]]) therefore refuses type 4 below `olympiaBlockNumber` and admits it from
  * that block, on the shipped ETC mainnet and Mordor configs, reading no timestamp (ETH's Prague timestamp is not
  * consulted on ETC). The consensus validator's gate is pinned by `OlympiaTxTypeAdmissionSpec`.
  *
  * Olympia is not scheduled on any ETC network, so the activation block here is synthetic.
  */
class TxTypeGateOlympiaSpec extends AnyFlatSpec with Matchers:

  private lazy val shippedRoot = ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()
  private def shipped(name: String): BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig(s"fukuii.blockchains.$name"))

  private val OlympiaAt: BigInt = 30_000_000

  private def withOlympia(cfg: BlockchainConfig): BlockchainConfig =
    cfg.withUpdatedForkBlocks(_.copy(olympiaBlockNumber = OlympiaAt))

  private def head(number: BigInt): BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(
      number = BlockNumber(number),
      extraFields = if number >= OlympiaAt then HefPostOlympia(BigInt(1_000_000_000)) else HefEmpty
    )

  private def setCodeTx(chainId: BigInt): SetCodeTransaction =
    SetCodeTransaction(
      chainId,
      nonce = 0,
      maxPriorityFeePerGas = 1_000_000_000,
      maxFeePerGas = 2_000_000_000,
      gasLimit = GasAmount(100_000),
      receivingAddress = Some(Address(0x42)),
      value = 0,
      payload = ByteString.empty,
      accessList = Nil,
      authorizationList = List(SetCodeAuthorization(chainId, Address(0x44), 0, 0, 1, 1))
    )

  for name <- Seq("etc", "mordor") do
    s"TxTypeGate on $name" should "refuse type 4 the block before Olympia and admit it from the Olympia block" taggedAs (
      UnitTest,
      OlympiaTest
    ) in {
      given cfg: BlockchainConfig = withOlympia(shipped(name))
      cfg.networkType shouldBe NetworkType.ETC
      // Prague's timestamp gate is ETH's: on ETC it is never set, so only the block number can admit type 4.
      cfg.isPragueTimestamp(head(OlympiaAt).unixTimestamp) shouldBe false
      val tx = setCodeTx(cfg.chainId.value)

      TxTypeGate.inactiveFork(tx, head(OlympiaAt - 1)) shouldBe Some("Olympia")
      TxTypeGate.activeTypes(head(OlympiaAt - 1)) should not contain Transaction.Type04
      TxAdmission.typeRejection(tx, head(OlympiaAt - 1)) shouldBe
        Some(TxAdmission.Rejection.TypeNotSupported(Transaction.Type04.toInt, "Olympia"))

      TxTypeGate.inactiveFork(tx, head(OlympiaAt)) shouldBe None
      TxTypeGate.activeTypes(head(OlympiaAt)) should contain(Transaction.Type04)
      TxAdmission.typeRejection(tx, head(OlympiaAt)) shouldBe None
      TxTypeGate.inactiveFork(tx, head(OlympiaAt + 1)) shouldBe None
    }

    it should "refuse type 4 on the shipped config, where Olympia is unscheduled" taggedAs (UnitTest, OlympiaTest) in {
      given cfg: BlockchainConfig = shipped(name)
      TxTypeGate.inactiveFork(setCodeTx(cfg.chainId.value), head(OlympiaAt)) shouldBe Some("Olympia")
    }
