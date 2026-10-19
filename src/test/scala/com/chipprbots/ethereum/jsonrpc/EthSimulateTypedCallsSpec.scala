package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*
import org.json4s.jvalue2monadic
import org.json4s.native.JsonMethods.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.*
import com.chipprbots.ethereum.ledger.AmsterdamFixtureVectors
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
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

  // ── geth's preCheck order: with several faults, the first geth finds wins ─────────────────────────────────────

  private val ValidBlobHash = "0x01" + "00" * 31

  "eth_simulateV1 preCheck order" should "report the nonce before a tip above the fee cap (-38011)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val call = s"""{"from":"$From","to":"$To","nonce":"0x3","maxFeePerGas":"0x10","maxPriorityFeePerGas":"0x20"}"""
    ethNode.error(validated(call))._1 shouldBe -38011
  }

  it should "report a fee cap below the base fee before insufficient funds (-38012)" taggedAs (UnitTest, RPCTest) in {
    val payload =
      s"""{"blockStateCalls":[{"calls":[{"from":"$From","to":"$To","value":"0x1000"}]}],"validation":true}"""
    ethNode.error(payload)._1 shouldBe -38012
  }

  it should "report insufficient funds before intrinsic gas (-38014), as buyGas precedes the intrinsic check" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // unfunded sender, value 1 wei, gas 1: geth's buyGas fails before execute's intrinsic-gas check
    val payload = s"""{"blockStateCalls":[{"calls":[{"from":"$From","to":"$To","value":"0x1","gas":"0x1"}]}]}"""
    ethNode.error(payload)._1 shouldBe -38014
  }

  it should "report a tip above the fee cap before an empty authorizationList (-32602)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val call =
      s"""{"from":"$From","to":"$To","maxFeePerGas":"0x10","maxPriorityFeePerGas":"0x20","authorizationList":[]}"""
    ethNode.error(oneBlock(call))._1 shouldBe -32602
  }

  it should "report a fee cap below the base fee before empty blob hashes (-38012)" taggedAs (UnitTest, RPCTest) in {
    ethNode.error(validated(s"""{"from":"$From","to":"$To","blobVersionedHashes":[]}"""))._1 shouldBe -38012
  }

  it should "report an empty authorizationList before intrinsic gas (-32603)" taggedAs (UnitTest, RPCTest) in {
    ethNode.error(oneBlock(s"""{"from":"$From","to":"$To","gas":"0x1","authorizationList":[]}"""))._1 shouldBe -32603
  }

  it should "report the nonce before a blob transaction without to (-38011)" taggedAs (UnitTest, RPCTest) in {
    val call =
      s"""{"from":"$From","nonce":"0x3","maxFeePerGas":"0x3b9aca00","blobVersionedHashes":["$ValidBlobHash"]}"""
    ethNode.error(validated(call))._1 shouldBe -38011
  }

  // ── preCheck items ──────────────────────────────────────────────────────────

  "eth_simulateV1 preCheck" should "refuse a blob transaction without to (-32603, ErrBlobTxCreate)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val (code, message) =
      ethNode.error(oneBlock(s"""{"from":"$From","input":"0x00","blobVersionedHashes":["$ValidBlobHash"]}"""))
    code shouldBe -32603
    message should include("blob transaction of type create")
  }

  it should "refuse a set-code transaction without to (-32603, ErrSetCodeTxCreate)" taggedAs (UnitTest, RPCTest) in {
    val auth = s"""{"chainId":"0x0","address":"$To","nonce":"0x0","yParity":"0x0","r":"0x1","s":"0x1"}"""
    val (code, message) = ethNode.error(oneBlock(s"""{"from":"$From","input":"0x00","authorizationList":[$auth]}"""))
    code shouldBe -32603
    message should include("EIP-7702 transaction cannot be used to create contract")
  }

  it should "refuse a blob hash of the wrong version (-32603)" taggedAs (UnitTest, RPCTest) in {
    val bad = "0x02" + "00" * 31
    val (code, message) = ethNode.error(oneBlock(s"""{"from":"$From","to":"$To","blobVersionedHashes":["$bad"]}"""))
    code shouldBe -32603
    message should include("blob 0 has invalid hash version")
  }

  it should "refuse more than six blobs from Osaka (-32603, ErrTooManyBlobs)" taggedAs (UnitTest, RPCTest) in {
    val hashes = List.fill(7)(s""""$ValidBlobHash"""").mkString(",")
    val (code, message) = ethNode.error(oneBlock(s"""{"from":"$From","to":"$To","blobVersionedHashes":[$hashes]}"""))
    code shouldBe -32603
    message should include("blob transaction has too many blobs")
  }

  it should "refuse a blob fee cap below the blob base fee with validation (-32603, ErrBlobFeeCapTooLow)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // excessBlobGas 0: the blob base fee is the 1 wei minimum, and validation checks even a zero cap
    val call = s"""{"from":"$From","to":"$To","maxFeePerGas":"0x3b9aca00","maxFeePerBlobGas":"0x0",""" +
      s""""blobVersionedHashes":["$ValidBlobHash"]}"""
    val (code, message) = ethNode.error(validated(call))
    code shouldBe -32603
    message should include("max fee per blob gas less than block blob gas fee")
  }

  it should "skip the blob fee cap check without validation when the cap is zero" taggedAs (UnitTest, RPCTest) in {
    val (block, _) = ethNode.ok(oneBlock(s"""{"from":"$From","to":"$To","blobVersionedHashes":["$ValidBlobHash"]}"""))
    block.transactions.head.tx shouldBe a[BlobTransaction]
  }

  it should "check a non-zero blob fee cap against an overridden blob base fee without validation" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val payload = s"""{"blockStateCalls":[{"blockOverrides":{"blobBaseFee":"0x10"},$Funded,"calls":[""" +
      s"""{"from":"$From","to":"$To","maxFeePerBlobGas":"0x5","blobVersionedHashes":["$ValidBlobHash"]}]}]}"""
    ethNode.error(payload)._1 shouldBe -32603
  }

  it should "refuse init code above the EIP-3860 limit (-38025)" taggedAs (UnitTest, RPCTest) in {
    // Osaka, maxCodeSize 24,576: the init code limit is 49,152 bytes
    val initCode = "0x" + "00" * 49153
    val (code, message) = ethNode.error(oneBlock(s"""{"from":"$From","input":"$initCode"}"""))
    code shouldBe -38025
    message should include("max initcode size exceeded: code size 49153 limit 49152")
  }

  it should "not apply the EIP-7825 gas cap: the simulate message skips transaction checks (transaction_args.go:506)" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // Osaka, validation on, gas 2^24 + 1: geth's preCheck cap check is behind !SkipTransactionChecks
    val call = s"""{"from":"$From","to":"$To","gas":"0x1000001","maxFeePerGas":"0x3b9aca00"}"""
    val (block, _) = ethNode.ok(validated(call))
    block.calls.head.status shouldBe BigInt(1)
  }

  it should "word the base fee error as geth: checksummed address and the supplied-gas wrapper" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    ethNode.error(validated(s"""{"from":"$From","to":"$To","gas":"0x5208","gasPrice":"0x1"}""")) shouldBe
      (
        -38012,
        "err: max fee per gas less than block base fee: address 0xC000000000000000000000000000000000000000, " +
          "maxFeePerGas: 1, baseFee: 7 (supplied gas 21000)"
      )
  }

  // ── transaction hashes ──────────────────────────────────────────────────────

  private def q(v: BigInt): RLPValue = RLPValue(
    if v == 0 then Array.emptyByteArray else v.toByteArray.dropWhile(_ == 0)
  )
  private def b(hex: String): RLPValue = RLPValue(org.bouncycastle.util.encoders.Hex.decode(hex.stripPrefix("0x")))
  private def hashHex(bytes: Array[Byte]): String = "0x" + org.bouncycastle.util.encoders.Hex.toHexString(bytes)

  /** The unsigned-call transaction's hash, built field by field from the EIP-2718 envelope (`type || rlp(fields, 0, 0,
    * 0)`), independently of SignedTransaction's encoder.
    */
  private def envelopeHash(txType: Int, fields: rlp.RLPEncodeable*): String =
    hashHex(kec256(Array(txType.toByte) ++ rlp.encode(RLPList((fields ++ Seq(q(0), q(0), q(0)))*))))

  private val chainId = amsterdamConfig.chainId.value
  private val rlpAccessList = RLPList(RLPList(b(To), RLPList(b(Slot))))

  "eth_simulateV1 transaction hashes" should "be the EIP-2930 envelope hash for a 0x1 call" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val (block, json) = ethNode.ok(
      oneBlock(
        s"""{"from":"$From","to":"$To","type":"0x1","gasPrice":"0x0","gas":"0x7530","value":"0x1",$accessList}"""
      )
    )
    val expected = envelopeHash(1, q(chainId), q(0), q(0), q(30000), b(To), q(1), b("0x"), rlpAccessList)
    expected shouldBe Type1Hash
    hashHex(block.transactions.head.hash.value.toArray) shouldBe expected
    (json \ "transactions")(0) \ "hash" shouldBe JString(expected)
  }

  it should "be the EIP-7702 envelope hash for a 0x4 call" taggedAs (UnitTest, RPCTest) in {
    val auth = s"""{"chainId":"0x0","address":"$To","nonce":"0x0","yParity":"0x0","r":"0x1","s":"0x1"}"""
    val (block, _) =
      ethNode.ok(oneBlock(s"""{"from":"$From","to":"$To","gas":"0xc350","authorizationList":[$auth]}"""))
    val rlpAuths = RLPList(RLPList(q(0), b(To), q(0), q(0), q(1), q(1)))
    val expected = envelopeHash(4, q(chainId), q(0), q(0), q(0), q(50000), b(To), q(0), b("0x"), RLPList(), rlpAuths)
    expected shouldBe Type4Hash
    hashHex(block.transactions.head.hash.value.toArray) shouldBe expected
  }

  // Pinned: keccak256 of the envelopes above, built from fukuii's RLP primitives (no geth binary on this host). The
  // same values are what SignedTransaction.hash gives, whose typed encoders TxSigningVectorsSpec proves byte-exact
  // against go-ethereum-signed vectors.
  private val Type1Hash = "0xea0d59aaa73afaec438c290d9e7ac43110fa39d05ad87eba9830e431a840d7e8"
  private val Type4Hash = "0x8044adeb8cc716f594ceceb92dafadc4b7bd3a55264eb418ea301a1bc8770412"
