package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

/** #1629 S1: the shared TxArgs model (execution-apis GenericTransaction / geth TransactionArgs). */
class TxArgsSpec extends AnyFlatSpec with Matchers with EitherValues with TableDrivenPropertyChecks:

  private def dec(json: String): Either[JsonRpcError, TxArgs] = TxArgsJson.decode(parse(json))
  private def ok(json: String): TxArgs = dec(json).value
  private def decErr(json: String): String =
    val e = dec(json).left.value
    e.code shouldBe -32602
    e.message

  private val A1 = "0x" + "11" * 20
  private val A2 = "0x" + "22" * 20
  private val H1 = "0x01" + "ab" * 31
  private val H2 = "0x01" + "cd" * 31
  private val Auth =
    s"""{"chainId":"0x1","address":"$A2","nonce":"0x7","yParity":"0x1","r":"0x${"aa" * 32}","s":"0x${"bb" * 32}"}"""

  // ----------------------------------------------------------------------------------------------
  // Decode: every field
  // ----------------------------------------------------------------------------------------------

  "TxArgsJson.decode" should "decode every GenericTransaction field" taggedAs (UnitTest) in {
    val blob = "0x" + "00" * TxArgsJson.BlobLength
    val c48 = "0x" + "c0" * 48
    val p48 = "0x" + "d0" * 48
    val a = ok(s"""{
      "type":"0x3","from":"$A1","to":"$A2","gas":"0x5208","gasPrice":"0x1",
      "maxFeePerGas":"0x3b9aca00","maxPriorityFeePerGas":"0x2","maxFeePerBlobGas":"0x10",
      "value":"0xde0b6b3a7640000","nonce":"0x9","data":"0x5544","input":"0x5544",
      "accessList":[{"address":"$A2","storageKeys":["0x${"00" * 31}01"]}],
      "chainId":"0xaa36a7","blobVersionedHashes":["$H1","$H2"],
      "blobs":["$blob"],"commitments":["$c48"],"proofs":["$p48"],
      "authorizationList":[$Auth]
    }""")
    a.txType shouldBe Some(3.toByte)
    a.from shouldBe Some(Address(A1))
    a.to shouldBe Some(Address(A2))
    a.gas shouldBe Some(GasAmount(21000))
    a.gasPrice shouldBe Some(GasPrice(1))
    a.maxFeePerGas shouldBe Some(BigInt(1000000000))
    a.maxPriorityFeePerGas shouldBe Some(BigInt(2))
    a.maxFeePerBlobGas shouldBe Some(BigInt(16))
    a.value shouldBe Some(BigInt(10).pow(18))
    a.nonce shouldBe Some(BigInt(9))
    a.data shouldBe Some(ByteString(Hex.decode("5544")))
    a.input shouldBe a.data
    a.accessList shouldBe Some(List(AccessListItem(Address(A2), List(StorageKey(1)))))
    a.chainId.map(_.value) shouldBe Some(BigInt(11155111))
    a.blobVersionedHashes.map(_.map(h => "0x" + Hex.toHexString(h.toArray))) shouldBe Some(List(H1, H2))
    a.blobs.map(_.map(_.length)) shouldBe Some(List(TxArgsJson.BlobLength))
    a.commitments.map(_.map(_.length)) shouldBe Some(List(48))
    a.proofs.map(_.map(_.length)) shouldBe Some(List(48))
    a.authorizationList shouldBe Some(
      List(SetCodeAuthorization(1, Address(A2), 7, 1, BigInt("aa" * 32, 16), BigInt("bb" * 32, 16)))
    )
  }

  it should "treat absent and null alike, and keep empty lists as present" taggedAs (UnitTest) in {
    ok("{}") shouldBe TxArgs()
    ok("""{"to":null,"gas":null,"accessList":null,"authorizationList":null}""") shouldBe TxArgs()
    ok("""{"accessList":[]}""").accessList shouldBe Some(Nil)
    ok("""{"blobVersionedHashes":[]}""").blobVersionedHashes shouldBe Some(Nil)
    ok("""{"authorizationList":[]}""").authorizationList shouldBe Some(Nil)
  }

  it should "match keys case-insensitively, exact case winning (Go encoding/json)" taggedAs (UnitTest) in {
    ok(s"""{"GAS":"0x5","MaxFeePerGas":"0x2","To":"$A1"}""") shouldBe
      TxArgs(gas = Some(GasAmount(5)), maxFeePerGas = Some(2), to = Some(Address(A1)))
    ok("""{"Gas":"0x1","gas":"0x2"}""").gas shouldBe Some(GasAmount(2))
    ok("""{"gas":"0x2","Gas":"0x1"}""").gas shouldBe Some(GasAmount(2))
    ok(s"""{"accessList":[{"Address":"$A1","STORAGEKEYS":[]}]}""").accessList shouldBe
      Some(List(AccessListItem(Address(A1), Nil)))
  }

  it should "ignore unknown fields (geth)" taggedAs (UnitTest) in {
    ok("""{"foo":"bar","nonce":"0x1"}""") shouldBe TxArgs(nonce = Some(1))
  }

  it should "reject a non-object argument" taggedAs (UnitTest) in {
    TxArgsJson.decode(JString("0x1"), 1).left.value.message should startWith("invalid argument 1:")
  }

  // ----------------------------------------------------------------------------------------------
  // Decode: hexutil edge cases
  // ----------------------------------------------------------------------------------------------

  private def gethTypeErr(reason: String, field: String, goType: String) =
    s"invalid argument 0: json: cannot unmarshal $reason into Go struct field TransactionArgs.$field of type $goType"

  it should "decode quantities exactly like geth hexutil" taggedAs (UnitTest) in {
    val accepted = Table(
      ("json", "value"),
      ("\"0x0\"", BigInt(0)),
      ("\"\"", BigInt(0)), // checkNumberText: empty strings are allowed
      ("\"0x1\"", BigInt(1)), // odd digit count is fine for numbers
      ("\"0X1f\"", BigInt(31)),
      ("\"0xAbC\"", BigInt(0xabc)),
      ("\"0x" + "f" * 64 + "\"", BigInt(2).pow(256) - 1)
    )
    forAll(accepted)((j, v) => ok(s"""{"value":$j}""").value shouldBe Some(v))

    val rejected = Table(
      ("json", "reason"),
      ("\"0x\"", "hex string \"0x\""),
      ("\"0x01\"", "hex number with leading zero digits"),
      ("\"0x00\"", "hex number with leading zero digits"),
      ("\"1\"", "hex string without 0x prefix"),
      ("\"0xg\"", "invalid hex string"),
      ("\"0x1" + "0" * 64 + "\"", "hex number > 256 bits"),
      ("1", "non-string")
    )
    forAll(rejected)((j, reason) => decErr(s"""{"value":$j}""") shouldBe gethTypeErr(reason, "value", "hexutil.Big"))
  }

  it should "bound gas and nonce to 64 bits" taggedAs (UnitTest) in {
    ok("""{"gas":"0xffffffffffffffff"}""").gas shouldBe Some(GasAmount(BigInt("ffffffffffffffff", 16)))
    decErr("""{"gas":"0x10000000000000000"}""") shouldBe gethTypeErr("hex number > 64 bits", "gas", "hexutil.Uint64")
    decErr("""{"nonce":"0x10000000000000000"}""") shouldBe
      gethTypeErr("hex number > 64 bits", "nonce", "hexutil.Uint64")
    decErr("""{"nonce":7}""") shouldBe gethTypeErr("non-string", "nonce", "hexutil.Uint64")
  }

  it should "decode bytes exactly like geth hexutil" taggedAs (UnitTest) in {
    ok("""{"input":"0x"}""").input shouldBe Some(ByteString.empty)
    ok("""{"input":""}""").input shouldBe Some(ByteString.empty)
    ok("""{"input":"0XaB"}""").input shouldBe Some(ByteString(0xab.toByte))
    decErr("""{"input":"0x1"}""") shouldBe gethTypeErr("hex string of odd length", "input", "hexutil.Bytes")
    decErr("""{"data":"ab"}""") shouldBe gethTypeErr("hex string without 0x prefix", "data", "hexutil.Bytes")
    decErr("""{"data":"0xzz"}""") shouldBe gethTypeErr("invalid hex string", "data", "hexutil.Bytes")
  }

  it should "require exact-length addresses; \"to\": \"\" is an error, not contract creation" taggedAs (UnitTest) in {
    decErr("""{"to":""}""") shouldBe "invalid argument 0: hex string has length 0, want 40 for common.Address"
    decErr("""{"to":"0x1234"}""") shouldBe "invalid argument 0: hex string has length 4, want 40 for common.Address"
    decErr(s"""{"from":"${A1}00"}""") shouldBe
      "invalid argument 0: hex string has length 42, want 40 for common.Address"
    decErr(s"""{"to":"${A1.drop(2)}"}""") shouldBe
      gethTypeErr("hex string without 0x prefix", "to", "common.Address")
    ok("""{"to":null}""").to shouldBe None
  }

  it should "require 32-byte storage keys and blob hashes, and exact blob/commitment/proof sizes" taggedAs (UnitTest) in {
    decErr(s"""{"accessList":[{"address":"$A1","storageKeys":["0x01"]}]}""") shouldBe
      "invalid argument 0: hex string has length 2, want 64 for common.Hash"
    decErr("""{"blobVersionedHashes":["0x01"]}""") shouldBe
      "invalid argument 0: hex string has length 2, want 64 for common.Hash"
    decErr("""{"blobs":["0x00"]}""") shouldBe
      s"invalid argument 0: hex string has length 2, want ${TxArgsJson.BlobLength * 2} for kzg4844.Blob"
    decErr("""{"commitments":["0x00"]}""") shouldBe
      "invalid argument 0: hex string has length 2, want 96 for kzg4844.Commitment"
    decErr("""{"proofs":["0x00"]}""") shouldBe "invalid argument 0: hex string has length 2, want 96 for kzg4844.Proof"
  }

  it should "enforce required access-list and authorization fields (geth gencodec)" taggedAs (UnitTest) in {
    decErr(s"""{"accessList":[{"address":"$A1"}]}""") shouldBe
      "invalid argument 0: missing required field 'storageKeys' for AccessTuple"
    decErr("""{"accessList":[{"storageKeys":[]}]}""") shouldBe
      "invalid argument 0: missing required field 'address' for AccessTuple"
    for field <- List("chainId", "address", "nonce", "yParity", "r", "s") do
      val obj = parse(Auth).asInstanceOf[JObject]
      val without = JObject(obj.obj.filterNot(_._1 == field))
      TxArgsJson.decode(JObject("authorizationList" -> JArray(List(without)))).left.value.message shouldBe
        s"invalid argument 0: missing required field '$field' for SetCodeAuthorization"
    decErr("""{"accessList":{}}""") should include("non-array")
  }

  it should "decode the type field as an execution-apis byte" taggedAs (UnitTest) in {
    ok("""{"type":"0x2"}""").txType shouldBe Some(2.toByte)
    ok("""{"type":"0x02"}""").txType shouldBe Some(2.toByte)
    ok("""{"type":"0x0"}""").txType shouldBe Some(0.toByte)
    decErr("""{"type":"0x"}""") should include("invalid transaction type")
    decErr("""{"type":"0x102"}""") should include("invalid transaction type")
    decErr("""{"type":"2"}""") should include("invalid transaction type")
  }

  // ----------------------------------------------------------------------------------------------
  // data / input and fee-family conflicts (geth messages verbatim)
  // ----------------------------------------------------------------------------------------------

  "TxArgs data/input" should "prefer input, and reject a mismatch only on the send path (geth)" taggedAs (UnitTest) in {
    val both = ok(s"""{"to":"$A1","data":"0x01","input":"0x02"}""")
    both.callData shouldBe ByteString(2)
    both.validateForSend.left.value shouldBe TxArgsError.DataInputMismatch
    both.validateForSend.left.value.message shouldBe
      """both "data" and "input" are set and not equal. Please use "input" to pass transaction call data"""
    both.validateForCall.isRight shouldBe true

    ok(s"""{"to":"$A1","data":"0x01","input":"0x01"}""").validateForSend.isRight shouldBe true
    ok(s"""{"to":"$A1","data":"0x01"}""").callData shouldBe ByteString(1)
  }

  "TxArgs fee families" should "reject gasPrice with any EIP-1559 fee field" taggedAs (UnitTest) in {
    val msg = "both gasPrice and (maxFeePerGas or maxPriorityFeePerGas) specified"
    for extra <- List(""""maxFeePerGas":"0x2"""", """"maxPriorityFeePerGas":"0x1"""") do
      val a = ok(s"""{"to":"$A1","gasPrice":"0x1",$extra}""")
      a.validateForSend.left.value.message shouldBe msg
      a.validateForCall.left.value.message shouldBe msg
      a.resolveType().left.value.message shouldBe msg
    // the conflict wins even over an explicit type
    ok(s"""{"type":"0x2","to":"$A1","gasPrice":"0x1","maxFeePerGas":"0x2"}""")
      .resolveType()
      .left
      .value
      .message shouldBe msg
  }

  it should "reject gasPrice with an authorization list" taggedAs (UnitTest) in {
    ok(s"""{"to":"$A1","gasPrice":"0x1","authorizationList":[$Auth]}""").resolveType().left.value.message shouldBe
      "both gasPrice and authorizationList specified"
  }

  // ----------------------------------------------------------------------------------------------
  // Type inference matrix (geth ToTransaction)
  // ----------------------------------------------------------------------------------------------

  "TxArgs.resolveType" should "infer the type exactly like geth ToTransaction" taggedAs (UnitTest) in {
    val al = s""""accessList":[]"""
    val bh = s""""blobVersionedHashes":["$H1"]"""
    val au = s""""authorizationList":[$Auth]"""
    val matrix = Table(
      ("fields", "defaultType", "expected"),
      ("", 0, 0),
      (al, 0, 1),
      (""""maxFeePerGas":"0x1"""", 0, 2),
      (""""maxPriorityFeePerGas":"0x1"""", 0, 2),
      (s""""maxFeePerGas":"0x1",$al""", 0, 2),
      (bh, 0, 3),
      (s""""blobs":["0x${"00" * TxArgsJson.BlobLength}"]""", 0, 3),
      (s"""$bh,"maxFeePerGas":"0x1",$al""", 0, 3),
      (au, 0, 4),
      (s"""$au,$bh""", 0, 4),
      (""""gasPrice":"0x1"""", 0, 0),
      // ToTransaction(defaultType)
      ("", 1, 1),
      ("", 2, 2),
      ("", 3, 3),
      ("", 4, 4),
      (al, 2, 2),
      (""""gasPrice":"0x1"""", 2, 0),
      (""""maxFeePerGas":"0x1"""", 4, 4)
    )
    forAll(matrix) { (fields, default, expected) =>
      val a = ok(s"""{"to":"$A1"${if fields.isEmpty then "" else "," + fields}}""")
      a.resolveType(default.toByte) shouldBe Right(expected.toByte)
    }
  }

  it should "reject gasPrice with accessList or blob fields when no type is given (deliberate deviation from geth)" taggedAs (
    UnitTest
  ) in {
    val al = s""""accessList":[]"""
    val bh = s""""blobVersionedHashes":["$H1"]"""
    val blobs = s""""blobs":["0x${"00" * TxArgsJson.BlobLength}"]"""
    val cases = Table(
      ("fields", "defaultType", "error"),
      (al, 0, TxArgsError.GasPriceWithAccessListUntyped),
      (al, 2, TxArgsError.GasPriceWithAccessListUntyped),
      (bh, 0, TxArgsError.GasPriceWithBlobFields),
      (blobs, 0, TxArgsError.GasPriceWithBlobFields),
      (s"$al,$bh", 0, TxArgsError.GasPriceWithBlobFields)
    )
    forAll(cases) { (fields, default, error) =>
      val args = ok(s"""{"to":"$A1","gasPrice":"0x1",$fields}""")
      args.resolveType(default.toByte) shouldBe Left(error)
      args.validateForSend shouldBe Left(error)
      args.toTransaction(Mainnet).left.value shouldBe error
      error.toJsonRpcError.code shouldBe -32602
    }
    TxArgsError.GasPriceWithAccessListUntyped.message shouldBe
      """gasPrice with accessList requires "type": "0x1" (or use maxFeePerGas/maxPriorityFeePerGas)"""
    TxArgsError.GasPriceWithBlobFields.message shouldBe
      "blob fields require a blob transaction; gasPrice is not allowed"
    // the call path keeps geth ToMessage semantics (lists are kept, not dropped), so it does not apply this rule
    ok(s"""{"to":"$A1","gasPrice":"0x1",$al}""").validateForCall.isRight shouldBe true
  }

  it should "reject an unsupported default type" taggedAs (UnitTest) in {
    TxArgs().resolveType(5.toByte) shouldBe Left(TxArgsError.TxTypeNotSupported)
  }

  it should "let an explicit type win and validate it against the fields present" taggedAs (UnitTest) in {
    val bh = s""""blobVersionedHashes":["$H1"]"""
    val au = s""""authorizationList":[$Auth]"""
    val accepted = Table(
      ("type", "fields"),
      ("0x0", """"gasPrice":"0x1","chainId":"0x1""""),
      ("0x1", """"gasPrice":"0x1","accessList":[]"""), // builds 2930; without the type this is an error
      ("0x1", ""),
      ("0x2", """"maxFeePerGas":"0x2","maxPriorityFeePerGas":"0x1","accessList":[]"""),
      ("0x2", ""),
      ("0x3", s""""maxFeePerGas":"0x2","maxFeePerBlobGas":"0x1",$bh"""),
      ("0x4", s""""maxFeePerGas":"0x2",$au""")
    )
    forAll(accepted) { (t, fields) =>
      val a = ok(s"""{"type":"$t","to":"$A1"${if fields.isEmpty then "" else "," + fields}}""")
      a.resolveType() shouldBe Right(Integer.decode(t).toByte)
      a.validateForCall.isRight shouldBe true
    }

    val rejected = Table(
      ("type", "fields", "badField"),
      ("0x0", """"maxFeePerGas":"0x1"""", "maxFeePerGas"),
      ("0x0", """"accessList":[]""", "accessList"),
      ("0x0", bh, "blobVersionedHashes"),
      ("0x1", """"maxPriorityFeePerGas":"0x1"""", "maxPriorityFeePerGas"),
      ("0x1", au, "authorizationList"),
      ("0x2", """"gasPrice":"0x1"""", "gasPrice"),
      ("0x2", """"maxFeePerBlobGas":"0x1"""", "maxFeePerBlobGas"),
      ("0x2", au, "authorizationList"),
      ("0x3", au, "authorizationList"),
      ("0x3", """"gasPrice":"0x1"""", "gasPrice"),
      ("0x4", bh, "blobVersionedHashes"),
      ("0x4", s""""proofs":["0x${"00" * 48}"]""", "proofs")
    )
    forAll(rejected) { (t, fields, bad) =>
      val a = ok(s"""{"type":"$t","to":"$A1",$fields}""")
      a.resolveType() shouldBe Left(TxArgsError.FieldNotValidForType(bad, Integer.decode(t).toByte))
    }
    TxArgsError.FieldNotValidForType("gasPrice", 2).message shouldBe
      """field "gasPrice" is not valid for transaction type 0x2"""
  }

  it should "reject unsupported explicit types and creates for types 0x3 and 0x4" taggedAs (UnitTest) in {
    ok("""{"type":"0x5"}""").resolveType() shouldBe Left(TxArgsError.TxTypeNotSupported)
    ok("""{"type":"0x7f"}""").resolveType() shouldBe Left(TxArgsError.TxTypeNotSupported)
    ok("""{"type":"0x3"}""").resolveType() shouldBe Left(TxArgsError.BlobTxCreate)
    ok("""{"type":"0x4"}""").resolveType() shouldBe Left(TxArgsError.SetCodeTxCreate)
  }

  // ----------------------------------------------------------------------------------------------
  // validateForSend / validateForCall (geth setDefaults / CallDefaults, state-free subset)
  // ----------------------------------------------------------------------------------------------

  "TxArgs.validateForSend" should "apply geth's state-free setDefaults checks" taggedAs (UnitTest) in {
    val cases = Table(
      ("json", "message"),
      (s"""{"to":"$A1","maxFeePerBlobGas":"0x0"}""", "maxFeePerBlobGas, if specified, must be non-zero"),
      (s"""{"to":"$A1","maxFeePerGas":"0x0","maxPriorityFeePerGas":"0x0"}""", "maxFeePerGas must be non-zero"),
      (
        s"""{"to":"$A1","maxFeePerGas":"0x1","maxPriorityFeePerGas":"0x2"}""",
        "maxFeePerGas (0x1) < maxPriorityFeePerGas (0x2)"
      ),
      (s"""{"to":"$A1","blobVersionedHashes":[]}""", "need at least 1 blob for a blob transaction"),
      (s"""{"blobVersionedHashes":["$H1"],"input":"0x01"}""", """missing "to" in blob transaction"""),
      ("""{"value":"0x1"}""", "contract creation without any data provided"),
      (
        s"""{"input":"0x01","authorizationList":[$Auth]}""",
        """authorizationList provided for contract creation, but "to" field is missing"""
      )
    )
    forAll(cases)((j, m) => ok(j).validateForSend.left.value.message shouldBe m)
  }

  it should "accept well-formed sends of every type" taggedAs (UnitTest) in {
    for j <- List(
        s"""{"to":"$A1"}""",
        """{"input":"0x6000"}""",
        s"""{"to":"$A1","gasPrice":"0x0"}""",
        s"""{"to":"$A1","accessList":[]}""",
        s"""{"to":"$A1","maxFeePerGas":"0x2","maxPriorityFeePerGas":"0x2"}""",
        s"""{"to":"$A1","blobVersionedHashes":["$H1"],"maxFeePerBlobGas":"0x1"}""",
        s"""{"to":"$A1","authorizationList":[$Auth]}"""
      )
    do ok(j).validateForSend.isRight shouldBe true
  }

  "TxArgs.validateForCall" should "apply geth's CallDefaults checks only" taggedAs (UnitTest) in {
    ok(s"""{"blobVersionedHashes":["$H1"]}""").validateForCall shouldBe Left(TxArgsError.BlobTxCreate)
    ok(s"""{"authorizationList":[$Auth]}""").validateForCall shouldBe Left(TxArgsError.SetCodeTxCreate)
    TxArgsError.BlobTxCreate.message shouldBe "blob transaction of type create"
    TxArgsError.SetCodeTxCreate.message shouldBe "EIP-7702 transaction cannot be used to create contract"
    // a create without data and a zero maxFeePerGas are fine for a call
    ok("""{}""").validateForCall.isRight shouldBe true
    ok("""{"maxFeePerGas":"0x0","maxPriorityFeePerGas":"0x0"}""").validateForCall.isRight shouldBe true
  }

  "TxArgsError" should "map to -32000 or -32602 at the caller's choice" taggedAs (UnitTest) in {
    TxArgsError.DataInputMismatch.toLogicError.code shouldBe -32000
    TxArgsError.DataInputMismatch.toInvalidParams.code shouldBe -32602
    TxArgsError.DataInputMismatch.toJsonRpcError.code shouldBe -32000
  }

  // ----------------------------------------------------------------------------------------------
  // toTransaction
  // ----------------------------------------------------------------------------------------------

  private val Key46 = crypto.keyPairFromPrvKey(Hex.decode("46" * 32))
  private val Mainnet = ChainId(1)

  "TxArgs.toTransaction" should "build the EIP-155 example legacy transaction byte-for-byte" taggedAs (UnitTest) in {
    // https://eips.ethereum.org/EIPS/eip-155 example; same vector as SignedLegacyTransactionSpec
    val args = ok("""{"nonce":"0x9","gasPrice":"0x4a817c800","gas":"0x5208",
      "to":"0x3535353535353535353535353535353535353535","value":"0xde0b6b3a7640000","input":"0x"}""")
    val tx = args.toTransaction(Mainnet).value
    tx shouldBe a[LegacyTransaction]
    val signed = SignedTransaction.sign(tx, Key46, Some(1))
    Hex.toHexString(signed.toBytes) shouldBe
      "f86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a067cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83"
  }

  it should "build geth's EIP-2930 test transaction byte-for-byte" taggedAs (UnitTest) in {
    // go-ethereum core/types/transaction_test.go emptyEip2718Tx (same vector as TransactionSpec)
    val args = ok("""{"type":"0x1","chainId":"0x1","nonce":"0x3","gasPrice":"0x1","gas":"0x61a8",
      "to":"0xb94f5374fce5edbc8e2a8697c15331677e6ebf0b","value":"0xa","input":"0x5544","accessList":[]}""")
    // without an explicit type this shape is rejected (geth would silently build a legacy tx and drop the list)
    args.copy(txType = None).toTransaction(Mainnet) shouldBe Left(TxArgsError.GasPriceWithAccessListUntyped)
    val tx = args.toTransaction(Mainnet).value
    tx shouldBe TransactionWithAccessList(
      1,
      3,
      GasPrice(1),
      GasAmount(25000),
      Address("b94f5374fce5edbc8e2a8697c15331677e6ebf0b"),
      10,
      ByteString(Hex.decode("5544")),
      Nil
    )
    val sig = ECDSASignature
      .fromBytes(
        ByteString(
          Hex.decode(
            "c9519f4f2b30335884581971573fadf60c6204f59a911df35ee8a540456b266032f1e8e2c5dd761f9e4f88f41c8310aeaba26a8bfcdacfedfa12ec3862d3752101"
          )
        )
      )
      .get
    Hex.toHexString(SignedTransaction(tx, sig).toBytes) shouldBe
      "01f8630103018261a894b94f5374fce5edbc8e2a8697c15331677e6ebf0b0a825544c001a0c9519f4f2b30335884581971573fadf60c6204f59a911df35ee8a540456b2660a032f1e8e2c5dd761f9e4f88f41c8310aeaba26a8bfcdacfedfa12ec3862d37521"
  }

  private val Sepolia = ChainId(11155111)
  private given BlockchainConfig = Config.blockchains.blockchainConfig.copy(chainId = Sepolia)

  private val typedVectors = Table(
    ("name", "json", "expectedClass"),
    (
      "0x2",
      s"""{"nonce":"0x1","gas":"0x5208","maxFeePerGas":"0x77359400","maxPriorityFeePerGas":"0x3b9aca00",
          "to":"$A1","value":"0x1","input":"0xdeadbeef",
          "accessList":[{"address":"$A2","storageKeys":["$H1"]}]}""",
      classOf[TransactionWithDynamicFee]
    ),
    (
      "0x2 create",
      """{"nonce":"0x0","gas":"0x10000","maxFeePerGas":"0x2","maxPriorityFeePerGas":"0x1","input":"0x6000"}""",
      classOf[TransactionWithDynamicFee]
    ),
    (
      "0x3",
      s"""{"nonce":"0x2","gas":"0x5208","maxFeePerGas":"0x2","maxPriorityFeePerGas":"0x1","maxFeePerBlobGas":"0x3",
          "to":"$A1","blobVersionedHashes":["$H1","$H2"]}""",
      classOf[BlobTransaction]
    ),
    (
      "0x4",
      s"""{"nonce":"0x3","gas":"0x186a0","maxFeePerGas":"0x2","maxPriorityFeePerGas":"0x1",
          "to":"$A1","authorizationList":[$Auth]}""",
      classOf[SetCodeTransaction]
    )
  )

  it should "build EIP-1559, EIP-4844 and EIP-7702 transactions that survive sign -> RLP -> decode" taggedAs (
    UnitTest
  ) in {
    forAll(typedVectors) { (name, json, cls) =>
      val tx = ok(json).toTransaction(Sepolia).value
      withClue(name)(tx.getClass shouldBe cls)
      val signed = SignedTransaction.sign(tx, Key46, Some(Sepolia.value))
      val decoded = signed.toBytes.toSignedTransaction
      withClue(name)(decoded.tx shouldBe tx)
      withClue(name)(SignedTransaction.getSender(decoded) shouldBe Some(Address(Key46)))
    }
  }

  it should "default a typed transaction's chainId to the node's and reject a mismatch" taggedAs (UnitTest) in {
    val tx = ok(s"""{"nonce":"0x0","gas":"0x5208","maxFeePerGas":"0x2","maxPriorityFeePerGas":"0x1","to":"$A1"}""")
      .toTransaction(Sepolia)
      .value
    tx.asInstanceOf[TransactionWithDynamicFee].chainId shouldBe Sepolia.value

    val wrong = ok(s"""{"chainId":"0x5","nonce":"0x0","gas":"0x5208","gasPrice":"0x1","to":"$A1"}""")
    wrong.toTransaction(Mainnet).left.value.message shouldBe "chainId does not match node's (have=5, want=1)"
  }

  it should "fill value and data with zero values but require nonce, gas and the type's fee fields" taggedAs (
    UnitTest
  ) in {
    val legacy = ok(s"""{"nonce":"0x0","gas":"0x5208","gasPrice":"0x1","to":"$A1"}""").toTransaction(Mainnet).value
    legacy.value shouldBe 0
    legacy.payload shouldBe ByteString.empty

    val missing = Table(
      ("json", "field", "type"),
      (s"""{"gas":"0x1","gasPrice":"0x1","to":"$A1"}""", "nonce", 0),
      (s"""{"nonce":"0x1","gasPrice":"0x1","to":"$A1"}""", "gas", 0),
      (s"""{"nonce":"0x1","gas":"0x1","to":"$A1"}""", "gasPrice", 0),
      (s"""{"nonce":"0x1","gas":"0x1","accessList":[],"to":"$A1"}""", "gasPrice", 1),
      (s"""{"nonce":"0x1","gas":"0x1","maxFeePerGas":"0x1","to":"$A1"}""", "maxPriorityFeePerGas", 2),
      (s"""{"nonce":"0x1","gas":"0x1","maxPriorityFeePerGas":"0x1","to":"$A1"}""", "maxFeePerGas", 2),
      (
        s"""{"nonce":"0x1","gas":"0x1","maxFeePerGas":"0x1","maxPriorityFeePerGas":"0x1","blobVersionedHashes":["$H1"],"to":"$A1"}""",
        "maxFeePerBlobGas",
        3
      ),
      (
        s"""{"type":"0x3","nonce":"0x1","gas":"0x1","maxFeePerGas":"0x1","maxPriorityFeePerGas":"0x1","maxFeePerBlobGas":"0x1","to":"$A1"}""",
        "blobVersionedHashes",
        3
      )
    )
    forAll(missing) { (j, field, t) =>
      ok(j).toTransaction(Mainnet) shouldBe Left(TxArgsError.MissingField(field, t.toByte))
    }
    TxArgsError.MissingField("nonce", 0).message shouldBe """missing "nonce" for transaction type 0x0"""
  }

  it should "build a set-code transaction with an empty authorization list when only the default type asks for 0x4" taggedAs (
    UnitTest
  ) in {
    val tx = ok(s"""{"nonce":"0x0","gas":"0x1","maxFeePerGas":"0x1","maxPriorityFeePerGas":"0x1","to":"$A1"}""")
      .toTransaction(Sepolia, TxArgs.SetCodeType)
      .value
    tx shouldBe a[SetCodeTransaction]
    tx.asInstanceOf[SetCodeTransaction].authorizationList shouldBe Nil
  }

  // ----------------------------------------------------------------------------------------------
  // S2 hooks: withDefaults, fromTransaction, encode
  // ----------------------------------------------------------------------------------------------

  "TxArgs.withDefaults" should "fill only absent fields" taggedAs (UnitTest) in {
    val partial = TxArgs(to = Some(Address(A1)), nonce = Some(5), maxFeePerGas = Some(9))
    val filled = partial.withDefaults(
      TxArgs.Defaults(
        nonce = Some(1),
        gas = Some(GasAmount(21000)),
        maxFeePerGas = Some(100),
        maxPriorityFeePerGas = Some(2),
        chainId = Some(Sepolia)
      )
    )
    filled.nonce shouldBe Some(BigInt(5))
    filled.maxFeePerGas shouldBe Some(BigInt(9))
    filled.gas shouldBe Some(GasAmount(21000))
    filled.maxPriorityFeePerGas shouldBe Some(BigInt(2))
    filled.chainId shouldBe Some(Sepolia)
    filled.toTransaction(Sepolia).value shouldBe a[TransactionWithDynamicFee]
  }

  "TxArgs.fromTransaction" should "invert toTransaction for all five types, also through JSON" taggedAs (UnitTest) in {
    val legacy = ok(s"""{"nonce":"0x9","gas":"0x5208","gasPrice":"0x1","to":"$A1","value":"0x1"}""")
      .toTransaction(Sepolia)
      .value
    val twal = ok(s"""{"type":"0x1","nonce":"0x9","gas":"0x5208","gasPrice":"0x1","to":"$A1",
      "accessList":[{"address":"$A2","storageKeys":["$H1"]}]}""").toTransaction(Sepolia).value
    val typed = typedVectors.map(v => ok(v._2).toTransaction(Sepolia).value)
    for tx <- legacy :: twal :: typed.toList do
      val args = TxArgs.fromTransaction(tx, Some(Address(A2)))
      args.toTransaction(Sepolia).value shouldBe tx
      val json = TxArgsJson.encode(args)
      json.obj.toMap.get("data") shouldBe None
      json.obj.toMap.get("from") shouldBe Some(JString(A2))
      TxArgsJson.decode(json).value shouldBe args
      TxArgsJson.decode(json).value.toTransaction(Sepolia).value shouldBe tx
  }

  "TxArgsJson.encode" should "emit minimal quantities, padded storage keys and omit absent fields" taggedAs (UnitTest) in {
    val json = TxArgsJson.encode(
      TxArgs(
        txType = Some(2.toByte),
        nonce = Some(0),
        value = Some(255),
        data = Some(ByteString(1)),
        accessList = Some(List(AccessListItem(Address(A1), List(StorageKey(1)))))
      )
    )
    json shouldBe JObject(
      "type" -> JString("0x2"),
      "nonce" -> JString("0x0"),
      "value" -> JString("0xff"),
      "input" -> JString("0x01"),
      "accessList" -> JArray(
        List(JObject("address" -> JString(A1), "storageKeys" -> JArray(List(JString("0x" + "00" * 31 + "01")))))
      )
    )
  }
