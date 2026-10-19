package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*
import org.json4s.jvalue2monadic
import org.json4s.native.JsonMethods.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** #1629 S4: eth_simulateV1 calls are execution-apis `GenericTransaction`s, decoded strictly by [[TxArgsJson]] and
  * built as go-ethereum builds them (`CallDefaults` + `ToTransaction(types.DynamicFeeTxType)`,
  * internal/ethapi/simulate.go).
  *
  *   - an accessList is honoured (execution and intrinsic gas), and an explicit `"type": "0x1"` gives an EIP-2930
  *     transaction with a type-1 receipt; an authorizationList gives an EIP-7702 transaction with a type-4 receipt
  *   - malformed fields are -32602, never silently absent
  *   - `validation: true` follows geth's preCheck: nonce (-38010 / -38011), tip above fee cap (-32602), fee cap below
  *     the base fee (-38012, gasPrice included)
  *   - per-chain gating through TxTypeGate: ETC before Olympia defaults to legacy and refuses 1559 / 7702 calls
  *
  * Chains: the Amsterdam fixture schedule (ETH; Prague 120, Osaka 180, Amsterdam 360) with the simulated block in
  * Osaka, and an ETC chain at Magneto, before Olympia.
  */
// scalastyle:off magic.number
class EthSimulateTypedCallsSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val emptyRoot: ByteString = ByteString(MerklePatriciaTrie.EmptyRootHash)
  private val zero32: ByteString = ByteString(Array.fill[Byte](32)(0))

  private val From = "0xc000000000000000000000000000000000000000"
  private val To = "0xc100000000000000000000000000000000000000"
  private val Slot = "0x0000000000000000000000000000000000000000000000000000000000000001"
  private val Funded = s""""stateOverrides":{"$From":{"balance":"0x3635c9adc5dea00000"}}"""

  /** ETC at Magneto (EIP-2930 active), Olympia and every timestamp fork far away: no base fee, no 0x2 / 0x3 / 0x4. */
  private val etcConfig: BlockchainConfig = Config.blockchains.blockchainConfig.withUpdatedForkBlocks(
    _.copy(
      homesteadBlockNumber = 0,
      eip150BlockNumber = 0,
      eip155BlockNumber = 0,
      eip160BlockNumber = 0,
      eip161BlockNumber = 0,
      atlantisBlockNumber = 0,
      aghartaBlockNumber = 0,
      phoenixBlockNumber = 0,
      magnetoBlockNumber = 0
    )
  )

  private class Node(config: BlockchainConfig, baseTimestamp: Long) extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = config

    private val baseExtraFields: HeaderExtraFields =
      if config.isPragueTimestamp(Timestamp(baseTimestamp)) then HefPostPrague(7, emptyRoot, 0, 0, zero32, zero32)
      else if config.isCancunTimestamp(Timestamp(baseTimestamp)) then HefPostCancun(7, emptyRoot, 0, 0, zero32)
      else HefEmpty

    blockchainWriter.save(
      Block(
        Fixtures.Blocks.ValidBlock.header.copy(
          number = BlockNumber(29),
          difficulty = if baseExtraFields == HefEmpty then Difficulty(1000) else Difficulty(0),
          stateRoot = TrieRoot(emptyRoot),
          gasLimit = GasAmount(30_000_000),
          gasUsed = GasAmount.Zero,
          unixTimestamp = Timestamp(baseTimestamp),
          extraFields = baseExtraFields
        ),
        BlockBody.empty
      ),
      Nil,
      ChainWeight.zero,
      saveAsBestBlock = true
    )

    lazy val service = new EthSimulateService(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      mining,
      config
    )

    /** Decode `payload` as eth_simulateV1's first param, run it, and return the response with its JSON rendering. */
    def run(payload: String): Either[JsonRpcError, (EthSimulateResponse, JValue)] =
      for
        req <- EthSimulateJsonMethodsImplicits.eth_simulateV1.decodeJson(
          Some(JArray(List(parse(payload), JString("latest"))))
        )
        res <- service.ethSimulate(req).unsafeRunSync()
      yield (res, EthSimulateJsonMethodsImplicits.eth_simulateV1.encodeJson(res))

    def ok(payload: String): (SimulateBlockResult, JValue) =
      run(payload) match
        case Right((res, JArray(block :: Nil))) => (res.blocks.head, block)
        case other                              => fail(s"expected one simulated block, got $other")

    def error(payload: String): (Int, String) =
      run(payload) match
        case Left(err)  => (err.code, err.message)
        case Right(res) => fail(s"expected an error, got $res")

  /** One block, funded sender, the given calls. */
  private def oneBlock(calls: String*): String =
    s"""{"blockStateCalls":[{$Funded,"calls":[${calls.mkString(",")}]}],"returnFullTransactions":true}"""

  private def validated(calls: String*): String =
    s"""{"blockStateCalls":[{$Funded,"calls":[${calls.mkString(",")}]}],"validation":true}"""

  private val accessList = s""""accessList":[{"address":"$To","storageKeys":["$Slot"]}]"""

  // Osaka base at 300: the simulated block lands on 312, before Amsterdam (360).
  private def ethNode = new Node(amsterdamConfig, baseTimestamp = 300)

  // ── ETH: types, receipts, rendering ─────────────────────────────────────────

  "eth_simulateV1" should "carry an untyped accessList in a 0x2 transaction and charge it, as geth's ToTransaction does" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val (block, json) = ethNode.ok(oneBlock(s"""{"from":"$From","to":"$To",$accessList}"""))

    block.transactions.head.tx shouldBe a[TransactionWithDynamicFee]
    block.transactions.head.tx.asInstanceOf[TransactionWithDynamicFee].accessList shouldBe
      List(AccessListItem(Address(To), List(StorageKey(BigInt(1)))))
    block.receipts.head shouldBe a[Type02Receipt]
    // EIP-2930 intrinsic gas: 21,000 + 2,400 per address + 1,900 per storage key
    block.calls.head.gasUsed shouldBe BigInt(25300)
    val tx = (json \ "transactions")(0)
    tx \ "type" shouldBe JString("0x2")
    tx \ "accessList" \\ "storageKeys" shouldBe JArray(List(JString(Slot)))
  }

  it should "build an EIP-2930 transaction with a type-1 receipt for an explicit type 0x1" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val (block, json) =
      ethNode.ok(oneBlock(s"""{"from":"$From","to":"$To","type":"0x1","gasPrice":"0x0",$accessList}"""))

    block.transactions.head.tx shouldBe a[TransactionWithAccessList]
    block.receipts.head shouldBe a[Type01Receipt]
    block.calls.head.gasUsed shouldBe BigInt(25300)
    val tx = (json \ "transactions")(0)
    tx \ "type" shouldBe JString("0x1")
    tx \ "gasPrice" shouldBe JString("0x0")
    tx \ "maxFeePerGas" shouldBe JNothing
    (tx \ "accessList" \ "address")(0) shouldBe JString(To)
  }

  it should "default the gas price of an explicit type 0x1 call to zero" taggedAs (UnitTest, RPCTest) in {
    val (block, _) = ethNode.ok(oneBlock(s"""{"from":"$From","to":"$To","type":"0x1",$accessList}"""))
    block.transactions.head.tx match
      case t: TransactionWithAccessList => t.gasPrice shouldBe GasPrice(0)
      case other                        => fail(s"expected an EIP-2930 transaction, got $other")
  }

  it should "build an EIP-7702 transaction with a type-4 receipt for an authorizationList" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val auth = s"""{"chainId":"0x0","address":"$To","nonce":"0x0","yParity":"0x0","r":"0x1","s":"0x1"}"""
    val (block, json) = ethNode.ok(oneBlock(s"""{"from":"$From","to":"$To","authorizationList":[$auth]}"""))

    block.transactions.head.tx match
      case t: SetCodeTransaction =>
        t.authorizationList.map(a => (a.chainId, a.address, a.nonce)) shouldBe List((BigInt(0), Address(To), BigInt(0)))
      case other => fail(s"expected an EIP-7702 transaction, got $other")
    block.receipts.head shouldBe a[Type04Receipt]
    // 21,000 + EIP-7702 PER_EMPTY_ACCOUNT_COST 25,000; no authority account exists, so nothing is refunded
    block.calls.head.gasUsed shouldBe BigInt(46000)
    val tx = (json \ "transactions")(0)
    tx \ "type" shouldBe JString("0x4")
    tx \ "maxFeePerGas" shouldBe JString("0x0")
    (tx \ "authorizationList" \ "address")(0) shouldBe JString(To)
  }

  it should "count the access list in the intrinsic gas check (-38013)" taggedAs (UnitTest, RPCTest) in {
    // 21,000 covers a plain transfer but not the 4,300 the access list adds
    ethNode.error(oneBlock(s"""{"from":"$From","to":"$To","gas":"0x5208",$accessList}"""))._1 shouldBe -38013
  }

  it should "keep the existing defaults for an untyped call: a 0x2 transaction and receipt" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val (block, _) = ethNode.ok(oneBlock(s"""{"from":"$From","to":"$To"}"""))
    block.transactions.head.tx shouldBe a[TransactionWithDynamicFee]
    block.receipts.head shouldBe a[Type02Receipt]
  }

  it should "build a legacy transaction for gasPrice alone" taggedAs (UnitTest, RPCTest) in {
    val (block, _) = ethNode.ok(oneBlock(s"""{"from":"$From","to":"$To","gasPrice":"0x0"}"""))
    block.transactions.head.tx shouldBe a[LegacyTransaction]
    block.receipts.head shouldBe a[LegacyReceipt]
  }

  // ── strict decoding ─────────────────────────────────────────────────────────

  "eth_simulateV1 call decoding" should "reject a malformed from address with -32602 instead of dropping it" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val (code, message) = ethNode.error(oneBlock(s"""{"from":"0x1234","to":"$To"}"""))
    code shouldBe -32602
    message should include("hex string has length 4, want 40 for common.Address")
  }

  it should "reject a malformed to address with -32602" taggedAs (UnitTest, RPCTest) in {
    ethNode.error(oneBlock(s"""{"from":"$From","to":"not-hex"}"""))._1 shouldBe -32602
  }

  it should "reject a malformed blob hash with -32602 instead of dropping it" taggedAs (UnitTest, RPCTest) in {
    ethNode.error(oneBlock(s"""{"from":"$From","to":"$To","blobVersionedHashes":["0x012"]}"""))._1 shouldBe -32602
  }

  it should "reject a JSON number quantity with -32602" taggedAs (UnitTest, RPCTest) in {
    ethNode.error(oneBlock(s"""{"from":"$From","to":"$To","gas":21000}"""))._1 shouldBe -32602
  }

  it should "reject a malformed access list entry with -32602" taggedAs (UnitTest, RPCTest) in {
    ethNode.error(oneBlock(s"""{"from":"$From","to":"$To","accessList":[{"address":"$To"}]}"""))._1 shouldBe -32602
  }

  it should "reject gasPrice together with maxFeePerGas with geth's CallDefaults error (-32000)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    ethNode.error(oneBlock(s"""{"from":"$From","to":"$To","gasPrice":"0x1","maxFeePerGas":"0x1"}""")) shouldBe
      (-32000, "both gasPrice and (maxFeePerGas or maxPriorityFeePerGas) specified")
  }

  it should "reject a chainId other than the node's (-32000)" taggedAs (UnitTest, RPCTest) in {
    val (code, message) = ethNode.error(oneBlock(s"""{"from":"$From","to":"$To","chainId":"0x7a69"}"""))
    code shouldBe -32000
    message should startWith("chainId does not match node's")
  }

  it should "reject an empty authorizationList with geth's preCheck error" taggedAs (UnitTest, RPCTest) in {
    val (code, message) = ethNode.error(oneBlock(s"""{"from":"$From","to":"$To","authorizationList":[]}"""))
    code shouldBe -32603
    message should include("EIP-7702 transaction with empty auth list")
  }

  it should "reject an empty blobVersionedHashes with geth's preCheck error" taggedAs (UnitTest, RPCTest) in {
    val (code, message) = ethNode.error(oneBlock(s"""{"from":"$From","to":"$To","blobVersionedHashes":[]}"""))
    code shouldBe -32603
    message should include("blob transaction missing blob hashes")
  }

  it should "reject a calls member that is not an array with -32602" taggedAs (UnitTest, RPCTest) in {
    ethNode.error(s"""{"blockStateCalls":[{"calls":"0x"}]}""")._1 shouldBe -32602
  }

  it should "reject a malformed block tag with -32602 instead of simulating on latest" taggedAs (UnitTest, RPCTest) in {
    val decoded = EthSimulateJsonMethodsImplicits.eth_simulateV1.decodeJson(
      Some(JArray(List(parse(oneBlock(s"""{"from":"$From","to":"$To"}""")), JString("not-a-block"))))
    )
    decoded.left.map(_.code) shouldBe Left(-32602)
  }

  // ── validation mode ─────────────────────────────────────────────────────────

  "eth_simulateV1 with validation" should "report a nonce above the account's with -38011" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val call = s"""{"from":"$From","to":"$To","nonce":"0x3","maxFeePerGas":"0x3b9aca00"}"""
    ethNode.error(validated(call))._1 shouldBe -38011
  }

  it should "report a nonce below the account's with -38010" taggedAs (UnitTest, RPCTest) in {
    val payload =
      s"""{"blockStateCalls":[{"stateOverrides":{"$From":{"balance":"0x3635c9adc5dea00000","nonce":"0x5"}},""" +
        s""""calls":[{"from":"$From","to":"$To","nonce":"0x3","maxFeePerGas":"0x3b9aca00"}]}],"validation":true}"""
    ethNode.error(payload)._1 shouldBe -38010
  }

  it should "report a gasPrice below the base fee with -38012, as geth's fee cap check does" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // the base block's fee is 7 and it used no gas, so the simulated block's is 7 too
    ethNode.error(validated(s"""{"from":"$From","to":"$To","gasPrice":"0x1"}"""))._1 shouldBe -38012
  }

  it should "report a tip above the fee cap with -32602" taggedAs (UnitTest, RPCTest) in {
    val call = s"""{"from":"$From","to":"$To","maxFeePerGas":"0x10","maxPriorityFeePerGas":"0x20"}"""
    val (code, message) = ethNode.error(validated(call))
    code shouldBe -32602
    message should include("max priority fee per gas higher than max fee per gas")
  }

  it should "run a typed 0x1 call priced at the base fee" taggedAs (UnitTest, RPCTest) in {
    val (block, _) = ethNode.ok(validated(s"""{"from":"$From","to":"$To","type":"0x1","gasPrice":"0x7",$accessList}"""))
    block.receipts.head shouldBe a[Type01Receipt]
    block.calls.head.status shouldBe BigInt(1)
  }

  "eth_simulateV1 without validation" should "still refuse a tip above a non-zero fee cap (-32602)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val call = s"""{"from":"$From","to":"$To","maxFeePerGas":"0x10","maxPriorityFeePerGas":"0x20"}"""
    val (code, message) = ethNode.error(oneBlock(call))
    code shouldBe -32602
    message should include("max priority fee per gas higher than max fee per gas")
  }

  // ── per-chain gating ────────────────────────────────────────────────────────

  "eth_simulateV1 on ETH before Prague" should "refuse an authorizationList (-32602)" taggedAs (UnitTest, RPCTest) in {
    // Cancun base at 100: the simulated block lands on 112, before Prague (120)
    val node = new Node(amsterdamConfig, baseTimestamp = 100)
    val auth = s"""{"chainId":"0x0","address":"$To","nonce":"0x0","yParity":"0x0","r":"0x1","s":"0x1"}"""
    val (code, message) = node.error(oneBlock(s"""{"from":"$From","to":"$To","authorizationList":[$auth]}"""))
    code shouldBe -32602
    message should include("transaction type not supported")
  }

  "eth_simulateV1 on ETC before Olympia" should "default to a legacy transaction, as geth's CallDefaults does without a base fee" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val (block, json) = new Node(etcConfig, baseTimestamp = 1_000_000L).ok(oneBlock(s"""{"from":"$From","to":"$To"}"""))
    block.transactions.head.tx shouldBe a[LegacyTransaction]
    block.receipts.head shouldBe a[LegacyReceipt]
    (json \ "transactions")(0) \ "type" shouldBe JString("0x0")
  }

  it should "build an EIP-2930 transaction for an accessList (Magneto)" taggedAs (UnitTest, RPCTest) in {
    val (block, _) =
      new Node(etcConfig, baseTimestamp = 1_000_000L).ok(oneBlock(s"""{"from":"$From","to":"$To",$accessList}"""))
    block.transactions.head.tx shouldBe a[TransactionWithAccessList]
    block.receipts.head shouldBe a[Type01Receipt]
  }

  it should "refuse maxFeePerGas with geth's pre-London error (-32000)" taggedAs (UnitTest, RPCTest) in {
    new Node(etcConfig, baseTimestamp = 1_000_000L).error(
      oneBlock(s"""{"from":"$From","to":"$To","maxFeePerGas":"0x1"}""")
    ) shouldBe (-32000, "maxFeePerGas and maxPriorityFeePerGas are not valid before London is active")
  }

  it should "refuse an explicit type 0x2 (-32602)" taggedAs (UnitTest, RPCTest) in {
    new Node(etcConfig, baseTimestamp = 1_000_000L)
      .error(oneBlock(s"""{"from":"$From","to":"$To","type":"0x2"}""")) shouldBe
      (-32602, "transaction type not supported: type 2 not active at block 30")
  }

  it should "refuse an authorizationList (-32602)" taggedAs (UnitTest, RPCTest) in {
    val auth = s"""{"chainId":"0x0","address":"$To","nonce":"0x0","yParity":"0x0","r":"0x1","s":"0x1"}"""
    new Node(etcConfig, baseTimestamp = 1_000_000L)
      .error(oneBlock(s"""{"from":"$From","to":"$To","authorizationList":[$auth]}""")) shouldBe
      (-32602, "transaction type not supported: type 4 not active at block 30")
  }
