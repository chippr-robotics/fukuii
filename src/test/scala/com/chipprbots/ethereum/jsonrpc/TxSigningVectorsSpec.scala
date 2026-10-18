package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.native.JsonMethods.compact
import org.json4s.native.JsonMethods.render
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.KzgTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.keystore.Wallet
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.testing.Tags.*

/** Byte-exact go-ethereum vectors for the transactions eth_signTransaction / eth_fillTransaction / eth_sendTransaction
  * build (#1629 S2).
  *
  * Every expected value below was produced by go-ethereum v1.17.6 (module
  * github.com/ethereum/go-ethereum@v1.17.6-0.20260813154252-aa1f2fcf5129): `types.SignTx` with
  * `LatestSignerForChainID(1)` (`NewEIP155Signer(61)` / `HomesteadSigner` for the legacy vectors), then
  * `Transaction.MarshalBinary` (`raw`) and `Transaction.MarshalJSON` (`tx`). Signing is RFC 6979 deterministic, so the
  * signatures match byte for byte. The signing key is geth internal/ethapi api_test.go's test account
  * (`newTestAccountManager`), and the 0x2 vector is exactly geth's `TestSignTransaction` expectation
  * (api_test.go:3048). The blob vectors use the all-zero blob of geth's `TestFillBlobTransaction`; their network form
  * (blob, commitment, proof(s)) is compared by SHA-256 because it is 128 KiB.
  */
class TxSigningVectorsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  override def beforeAll(): Unit = KzgTestSetup.ensureLoaded()

  private val wallet = Wallet(
    Address(Hex.decode("71562b71999873db5b286df957af199ec94617f7")),
    ByteString(Hex.decode("b71c71a67e1177ad4e901695e1b4b9ee17ae16c6668d313eac2f96dbcda3f291"))
  )
  private val to = Address(Hex.decode("703c4b2bd70c169f5717101caee543299fc946c7"))
  private val gwei = BigInt(1000000000)

  private def hex(b: ByteString): String = "0x" + Hex.toHexString(b.toArray[Byte])
  private def json(r: SignTransactionResult): String = compact(
    render(SignTransactionResult.encodeTransaction(r.stx, r.sidecar))
  )
  private def sha256Hex(b: ByteString): String = Hex.toHexString(crypto.sha256(b.toArray[Byte]))

  private def assertVector(r: SignTransactionResult, raw: String, txJson: String) =
    hex(r.raw) shouldBe raw
    json(r) shouldBe txJson
    // the raw form decodes back to the same transaction (the eth_sendRawTransaction path)
    r.raw.toArray.toSignedTransaction shouldBe r.stx

  private val legacy = LegacyTransaction(0, GasPrice(20 * gwei), GasAmount(21000), Some(to), 1, ByteString.empty)

  "a legacy transaction signed with EIP-155 (chain 61)" should "match go-ethereum byte for byte" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val stx = wallet.signTx(legacy, Some(BigInt(61))).tx
    assertVector(
      SignTransactionResult(stx, None),
      """0xf865808504a817c80082520894703c4b2bd70c169f5717101caee543299fc946c70180819ea0263ba19cea1126095446c017a04c3f2e5e1de945e8d115ef5410da687c74cf79a07689c02e245341ed4353c848002a3d3729df1428099266de1e8fea2787385402""",
      """{"type":"0x0","chainId":"0x3d","nonce":"0x0","to":"0x703c4b2bd70c169f5717101caee543299fc946c7","gas":"0x5208","gasPrice":"0x4a817c800","maxPriorityFeePerGas":null,"maxFeePerGas":null,"value":"0x1","input":"0x","v":"0x9e","r":"0x263ba19cea1126095446c017a04c3f2e5e1de945e8d115ef5410da687c74cf79","s":"0x7689c02e245341ed4353c848002a3d3729df1428099266de1e8fea2787385402","hash":"0x6195b21d8e2150aca5fae19384c539cfa572d7cafb9c09b4bb494bf10ffccf5c"}"""
    )
  }

  "an unprotected legacy transaction (pre-EIP-155 ETC)" should "match go-ethereum's HomesteadSigner" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val stx = wallet.signTx(legacy, None).tx
    assertVector(
      SignTransactionResult(stx, None),
      """0xf864808504a817c80082520894703c4b2bd70c169f5717101caee543299fc946c701801ba074e1e9f6beff4af2795e50a832b6812d80b9317d78eed69d1daff9782ffea52ea07561afb48fb1a07e0d9047d011a4902dbea4bc74b84184f816cb09575e8bacc0""",
      """{"type":"0x0","nonce":"0x0","to":"0x703c4b2bd70c169f5717101caee543299fc946c7","gas":"0x5208","gasPrice":"0x4a817c800","maxPriorityFeePerGas":null,"maxFeePerGas":null,"value":"0x1","input":"0x","v":"0x1b","r":"0x74e1e9f6beff4af2795e50a832b6812d80b9317d78eed69d1daff9782ffea52e","s":"0x7561afb48fb1a07e0d9047d011a4902dbea4bc74b84184f816cb09575e8bacc0","hash":"0x791bb8d1a6706c0e9960be6442370d3bcc5731195a18f99403483ab98bc2e090"}"""
    )
  }

  "an unsigned legacy transaction (eth_fillTransaction)" should "match go-ethereum's unsigned marshalling" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val r = SignTransactionResult.unsigned(legacy, None)
    hex(r.raw) shouldBe """0xe4808504a817c80082520894703c4b2bd70c169f5717101caee543299fc946c70180808080"""
    json(
      r
    ) shouldBe """{"type":"0x0","nonce":"0x0","to":"0x703c4b2bd70c169f5717101caee543299fc946c7","gas":"0x5208","gasPrice":"0x4a817c800","maxPriorityFeePerGas":null,"maxFeePerGas":null,"value":"0x1","input":"0x","v":"0x0","r":"0x0","s":"0x0","hash":"0xe0943f62c876491c0ddec4ab2f8d2c3561818bc799dd44dfa8d2b028e0309ed0"}"""
  }

  "an EIP-2930 transaction" should "match go-ethereum byte for byte" taggedAs (UnitTest, RPCTest) in {
    val tx = TransactionWithAccessList(
      1,
      1,
      GasPrice(gwei),
      GasAmount(30000),
      Some(to),
      1,
      ByteString(Hex.decode("1234")),
      List(AccessListItem(to, List(StorageKey(BigInt(1)))))
    )
    assertVector(
      SignTransactionResult(wallet.signTx(tx, None).tx, None),
      """0x01f8a00101843b9aca0082753094703c4b2bd70c169f5717101caee543299fc946c701821234f838f794703c4b2bd70c169f5717101caee543299fc946c7e1a0000000000000000000000000000000000000000000000000000000000000000180a0f586ba1eb2b1d96489563132b2f2d1c51ecde4042e097095a3d8223646eba2e6a061227f63a0afde8403f590973d23f9d4bbdb9682c349c18d1946ba4551be2243""",
      """{"type":"0x1","chainId":"0x1","nonce":"0x1","to":"0x703c4b2bd70c169f5717101caee543299fc946c7","gas":"0x7530","gasPrice":"0x3b9aca00","maxPriorityFeePerGas":null,"maxFeePerGas":null,"value":"0x1","input":"0x1234","accessList":[{"address":"0x703c4b2bd70c169f5717101caee543299fc946c7","storageKeys":["0x0000000000000000000000000000000000000000000000000000000000000001"]}],"v":"0x0","r":"0xf586ba1eb2b1d96489563132b2f2d1c51ecde4042e097095a3d8223646eba2e6","s":"0x61227f63a0afde8403f590973d23f9d4bbdb9682c349c18d1946ba4551be2243","yParity":"0x0","hash":"0x29902d03b6800e4168229fd380a01db87fbd21dbf0681f44428b8d9d5355f479"}"""
    )
  }

  private val dynamicFee =
    TransactionWithDynamicFee(1, 0, 0, BigInt(0x684ee180), GasAmount(21000), Some(to), 1, ByteString.empty, Nil)

  "an EIP-1559 transaction" should "match go-ethereum's TestSignTransaction expectation" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    assertVector(
      SignTransactionResult(wallet.signTx(dynamicFee, None).tx, None),
      """0x02f86601808084684ee18082520894703c4b2bd70c169f5717101caee543299fc946c70180c080a08fabeb142d585dd9247f459f7e6fe77e2520c88d50ba5d220da1533cea8b34e1a0582dd68b21aef36ba23f34e49607329c20d981d30404daf749077f5606785ce7""",
      """{"type":"0x2","chainId":"0x1","nonce":"0x0","to":"0x703c4b2bd70c169f5717101caee543299fc946c7","gas":"0x5208","gasPrice":null,"maxPriorityFeePerGas":"0x0","maxFeePerGas":"0x684ee180","value":"0x1","input":"0x","accessList":[],"v":"0x0","r":"0x8fabeb142d585dd9247f459f7e6fe77e2520c88d50ba5d220da1533cea8b34e1","s":"0x582dd68b21aef36ba23f34e49607329c20d981d30404daf749077f5606785ce7","yParity":"0x0","hash":"0x93927839207cfbec395da84b8a2bc38b7b65d2cb2819e9fef1f091f5b1d4cc8f"}"""
    )
  }

  "an unsigned EIP-1559 transaction (eth_fillTransaction)" should "match go-ethereum's unsigned marshalling" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val r = SignTransactionResult.unsigned(dynamicFee, None)
    hex(r.raw) shouldBe """0x02e601808084684ee18082520894703c4b2bd70c169f5717101caee543299fc946c70180c0808080"""
    json(
      r
    ) shouldBe """{"type":"0x2","chainId":"0x1","nonce":"0x0","to":"0x703c4b2bd70c169f5717101caee543299fc946c7","gas":"0x5208","gasPrice":null,"maxPriorityFeePerGas":"0x0","maxFeePerGas":"0x684ee180","value":"0x1","input":"0x","accessList":[],"v":"0x0","r":"0x0","s":"0x0","yParity":"0x0","hash":"0x39fa12b070a9d99c2076060aa0adfe9a9aaef67ee91c555242d1dee352123af2"}"""
  }

  private val emptyBlobCommitment = ByteString(
    Hex.decode("c00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000")
  )
  private val emptyBlobHash = BlobVersionedHash(
    ByteString(Hex.decode("010657f37554c781402a22917dee2f75def7ab966d7b770905398eba3c444014"))
  )

  private val blobTx = BlobTransaction(
    1,
    2,
    gwei,
    2 * gwei,
    GasAmount(21000),
    Some(to),
    0,
    ByteString.empty,
    Nil,
    2,
    List(emptyBlobHash)
  )

  "an EIP-4844 transaction" should "match go-ethereum byte for byte without a sidecar" taggedAs (UnitTest, RPCTest) in {
    assertVector(
      SignTransactionResult(wallet.signTx(blobTx, None).tx, None),
      """0x03f88d0102843b9aca00847735940082520894703c4b2bd70c169f5717101caee543299fc946c78080c002e1a0010657f37554c781402a22917dee2f75def7ab966d7b770905398eba3c44401480a0e965e1219217d4f11b1c8b64e3e8ffbd75736cf5c14db8e883c4c84ab365a17ea052161902e67d4a1e4219195956ba189bc9c7d2bfde2d63c3e338e11ef2e75182""",
      """{"type":"0x3","chainId":"0x1","nonce":"0x2","to":"0x703c4b2bd70c169f5717101caee543299fc946c7","gas":"0x5208","gasPrice":null,"maxPriorityFeePerGas":"0x3b9aca00","maxFeePerGas":"0x77359400","maxFeePerBlobGas":"0x2","value":"0x0","input":"0x","accessList":[],"blobVersionedHashes":["0x010657f37554c781402a22917dee2f75def7ab966d7b770905398eba3c444014"],"v":"0x0","r":"0xe965e1219217d4f11b1c8b64e3e8ffbd75736cf5c14db8e883c4c84ab365a17e","s":"0x52161902e67d4a1e4219195956ba189bc9c7d2bfde2d63c3e338e11ef2e75182","yParity":"0x0","hash":"0x87a5f331103d76e26ecb11a503f6e0b78710cd4b50db005d4ca2b278b16a56b7"}"""
    )
  }

  "the all-zero blob" should "commit and hash as go-ethereum's kzg4844 does" taggedAs (UnitTest, RPCTest) in {
    val zeroBlob = ByteString(new Array[Byte](TxArgsJson.BlobLength))
    val v0 =
      BlobTxSidecar.complete(TxArgs(to = Some(to), blobs = Some(List(zeroBlob))), BlobTxSidecar.Version0).toOption.get
    v0.commitments shouldBe Some(List(emptyBlobCommitment))
    v0.proofs.map(_.map(hex)) shouldBe Some(
      List("""0xc00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000""")
    )
    v0.blobVersionedHashes.map(_.map(_.value)) shouldBe Some(List(emptyBlobHash.value))

    val v1 =
      BlobTxSidecar.complete(TxArgs(to = Some(to), blobs = Some(List(zeroBlob))), BlobTxSidecar.Version1).toOption.get
    v1.proofs.map(_.size) shouldBe Some(128)
    sha256Hex(
      v1.proofs.get.foldLeft(ByteString.empty)(_ ++ _)
    ) shouldBe "6344e6aa419ed4ef15f7bf2d0cd777bee3bbb83174a612c527f4e956b7c87f95"
  }

  "a signed EIP-4844 transaction with a sidecar" should "encode the version 0 and version 1 network forms as go-ethereum does" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val zeroBlob = ByteString(new Array[Byte](TxArgsJson.BlobLength))
    val stx = wallet.signTx(blobTx, None).tx
    for (version, expected, len) <- List(
        (BlobTxSidecar.Version0, "317995b6fbc5b4b355e5cfb2e58f20e9a04d2adaa358436771835a96bb34a410", 131328),
        (BlobTxSidecar.Version1, "4c5e802b3174ac430e4fdf28910f3e98d3ce6ee9eef80eac6dd75f13013c9645", 137554)
      )
    do
      val args = BlobTxSidecar.complete(TxArgs(to = Some(to), blobs = Some(List(zeroBlob))), version).toOption.get
      val sidecar = BlobTxSidecar.fromArgs(args).get
      sidecar.version shouldBe version
      val r = SignTransactionResult(stx, Some(sidecar))
      r.raw.size shouldBe len
      sha256Hex(r.raw) shouldBe expected
      // the transaction hash never covers the sidecar
      hex(r.stx.hash.value) shouldBe """0x87a5f331103d76e26ecb11a503f6e0b78710cd4b50db005d4ca2b278b16a56b7"""
      // and eth_sendRawTransaction accepts the network form, keeping the sidecar bytes for the pool
      val (decoded, rawOpt) = r.raw.toArray.toSignedTransactionWithSidecar
      decoded shouldBe stx
      rawOpt.map(ByteString(_)) shouldBe Some(r.raw)
  }

  "an EIP-7702 transaction" should "match go-ethereum byte for byte" taggedAs (UnitTest, RPCTest) in {
    // the authorization as go-ethereum's types.SignSetCode produced it with the same key
    val auth = SetCodeAuthorization(
      chainId = 1,
      address = to,
      nonce = 5,
      v = 0,
      r = BigInt("68e00a3180ad08664dd406ad8544f3041e1a89b3d5f972311e8a8443ac93af01", 16),
      s = BigInt("5b3e02045a21bf6c86f8ea93c34e088cf8b9cef20d5b18a9d4c65d7d94a50447", 16)
    )
    val tx = SetCodeTransaction(1, 3, gwei, 2 * gwei, GasAmount(100000), Some(to), 0, ByteString.empty, Nil, List(auth))
    assertVector(
      SignTransactionResult(wallet.signTx(tx, None).tx, None),
      """0x04f8c90103843b9aca008477359400830186a094703c4b2bd70c169f5717101caee543299fc946c78080c0f85cf85a0194703c4b2bd70c169f5717101caee543299fc946c70580a068e00a3180ad08664dd406ad8544f3041e1a89b3d5f972311e8a8443ac93af01a05b3e02045a21bf6c86f8ea93c34e088cf8b9cef20d5b18a9d4c65d7d94a5044701a0412c1cb6eb6131ec3b4b1ce9c0013aa09f5419f18c73fd45a2a5a1b0d3e50102a046fc757e49616f7df212fac273b01eddaf50e39712c61e35494054fab3d5f30a""",
      """{"type":"0x4","chainId":"0x1","nonce":"0x3","to":"0x703c4b2bd70c169f5717101caee543299fc946c7","gas":"0x186a0","gasPrice":null,"maxPriorityFeePerGas":"0x3b9aca00","maxFeePerGas":"0x77359400","value":"0x0","input":"0x","accessList":[],"authorizationList":[{"chainId":"0x1","address":"0x703c4b2bd70c169f5717101caee543299fc946c7","nonce":"0x5","yParity":"0x0","r":"0x68e00a3180ad08664dd406ad8544f3041e1a89b3d5f972311e8a8443ac93af01","s":"0x5b3e02045a21bf6c86f8ea93c34e088cf8b9cef20d5b18a9d4c65d7d94a50447"}],"v":"0x1","r":"0x412c1cb6eb6131ec3b4b1ce9c0013aa09f5419f18c73fd45a2a5a1b0d3e50102","s":"0x46fc757e49616f7df212fac273b01eddaf50e39712c61e35494054fab3d5f30a","yParity":"0x1","hash":"0xc546d8b4bbf054408e99951821cc50c4450d1639fae5b9438a9f8885c093bc63"}"""
    )
  }
