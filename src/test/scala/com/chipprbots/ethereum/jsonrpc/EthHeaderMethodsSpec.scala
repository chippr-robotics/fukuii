package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefEmpty
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.jsonrpc.EthBlocksService.*
import com.chipprbots.ethereum.jsonrpc.JsonRpcError.InvalidParams
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config

// scalastyle:off magic.number
/** `eth_getHeaderByNumber` / `eth_getHeaderByHash`.
  *
  * Vectors: execution-apis `tests/eth_getHeaderByNumber/{get-genesis,get-latest,get-header-prague-fork}.io` and the
  * three `get-header-by-*-hash.io` files. Each expected object is rebuilt into a header whose hash must equal the
  * vector's `hash`, then the encoder's output must match key for key (hive's rpc-compat counts missing and extra keys
  * alike).
  */
class EthHeaderMethodsSpec extends AnyWordSpec with Matchers:

  // BlockResponse/TransactionResponse take the serving node's chain config; here, the test config's.
  private given BlockchainConfig = Config.blockchains.blockchainConfig

  private val Zero256Bloom = "0x" + "00" * 256

  private val Genesis =
    s"""{"difficulty":"0x20000","extraData":"0x68697665636861696e","gasLimit":"0x5f5e100","gasUsed":"0x0","hash":"0x202f4eff9f2f9447e497280d37eca304ec075091bc3c694f887cfe067f9cd43f","logsBloom":"$Zero256Bloom","miner":"0x0000000000000000000000000000000000000000","mixHash":"0x0000000000000000000000000000000000000000000000000000000000000000","nonce":"0x0000000000000000","number":"0x0","parentHash":"0x0000000000000000000000000000000000000000000000000000000000000000","receiptsRoot":"0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421","sha3Uncles":"0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347","stateRoot":"0x633b0d275a5fbc4a1fd106d1f90a0bfec5c97481689be3dcb742c23ce45ca1da","timestamp":"0x0","transactionsRoot":"0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"}"""

  private val AmsterdamLatest =
    s"""{"baseFeePerGas":"0xbc78d4","blobGasUsed":"0x20000","blockAccessListHash":"0x54306fbc4e818446e59cbd69acd4075fe1be6ca64d968b11da6d1b410ed2a86b","difficulty":"0x0","excessBlobGas":"0x0","extraData":"0x","gasLimit":"0xbebc200","gasUsed":"0x37f84","hash":"0x52bccfc9c4d4874b1898c0fbd0ae3b598a0edc7b94d9b796857dbbe1093c32e0","logsBloom":"$Zero256Bloom","miner":"0x0000000000000000000000000000000000000000","mixHash":"0x0000000000000000000000000000000000000000000000000000000000000000","nonce":"0x0000000000000000","number":"0x3c","parentBeaconBlockRoot":"0xefc50f4fc1430b6d5d043065201692a4a02252fef0699394631f5213a5667547","parentHash":"0x6e3bd94343c61839a0044d28115f28a7dd4f6a2b2add2335991e9520adcd3d18","receiptsRoot":"0x2154241b99bb7e50bc6fb658399056c79170d5a9930b3903f138480c169b7905","requestsHash":"0xe3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","sha3Uncles":"0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347","slotNumber":"0x3","stateRoot":"0xa6bd32516beae9780a8c90563bb28c18f4299855f3f9d7c1de2db9a609ce63ec","timestamp":"0x258","transactionsRoot":"0x48adddf21f81471c34a0d11acaeadb44e6f7dbddd9b26a0edcdfd802cf32ea3c","withdrawalsRoot":"0xfa56969d3f9ca7445b2c645d7861e17ecb08fc3ea3dee2a60c4281bde9bafd4e"}"""

  private def fields(json: String): Map[String, JValue] = parse(json) match
    case JObject(fs) => fs.toMap
    case other       => fail(s"not an object: $other")

  private def str(f: Map[String, JValue], key: String): String = f.get(key) match
    case Some(JString(s)) => s
    case other            => fail(s"$key: not a string ($other)")
  private def bytes(f: Map[String, JValue], key: String): ByteString =
    ByteString(Hex.decode(str(f, key).stripPrefix("0x")))
  private def qty(f: Map[String, JValue], key: String): BigInt = BigInt(str(f, key).stripPrefix("0x"), 16)

  private def headerFrom(f: Map[String, JValue]): BlockHeader =
    val extra: HeaderExtraFields =
      if f.contains("slotNumber") then
        HefPostAmsterdam(
          baseFee = qty(f, "baseFeePerGas"),
          withdrawalsRoot = bytes(f, "withdrawalsRoot"),
          blobGasUsed = qty(f, "blobGasUsed"),
          excessBlobGas = qty(f, "excessBlobGas"),
          parentBeaconBlockRoot = bytes(f, "parentBeaconBlockRoot"),
          requestsHash = bytes(f, "requestsHash"),
          blockAccessListHash = bytes(f, "blockAccessListHash"),
          slotNumber = qty(f, "slotNumber")
        )
      else if f.contains("requestsHash") then
        HefPostPrague(
          qty(f, "baseFeePerGas"),
          bytes(f, "withdrawalsRoot"),
          qty(f, "blobGasUsed"),
          qty(f, "excessBlobGas"),
          bytes(f, "parentBeaconBlockRoot"),
          bytes(f, "requestsHash")
        )
      else HefEmpty
    BlockHeader(
      parentHash = BlockHash(bytes(f, "parentHash")),
      ommersHash = BlockHash(bytes(f, "sha3Uncles")),
      beneficiary = bytes(f, "miner"),
      stateRoot = TrieRoot(bytes(f, "stateRoot")),
      transactionsRoot = TrieRoot(bytes(f, "transactionsRoot")),
      receiptsRoot = TrieRoot(bytes(f, "receiptsRoot")),
      logsBloom = BloomFilter(bytes(f, "logsBloom")),
      difficulty = Difficulty(qty(f, "difficulty")),
      number = BlockNumber(qty(f, "number")),
      gasLimit = GasAmount(qty(f, "gasLimit")),
      gasUsed = GasAmount(qty(f, "gasUsed")),
      unixTimestamp = Timestamp(qty(f, "timestamp").toLong),
      extraData = bytes(f, "extraData"),
      mixHash = BlockHash(bytes(f, "mixHash")),
      nonce = bytes(f, "nonce"),
      extraFields = extra
    )

  private def headerResponse(h: BlockHeader): BaseBlockResponse =
    BlockResponse(Block(h, BlockBody(Nil, Nil)), emitTotalDifficulty = false)

  private def assertMatchesVector(vector: String): Unit =
    val expected = fields(vector)
    val header = headerFrom(expected)
    ("0x" + Hex.toHexString(header.hash.value.toArray)) shouldBe str(expected, "hash")

    val json = EthBlocksJsonMethodsImplicits.eth_getHeaderByNumber.encodeJson(
      HeaderByNumberResponse(Some(headerResponse(header)))
    )
    json shouldBe a[JObject]
    val actual = fields(org.json4s.native.JsonMethods.compact(org.json4s.native.JsonMethods.render(json)))
    actual.keySet shouldBe expected.keySet
    expected.keys.foreach(k => withClue(s"$k: ")(actual(k) shouldBe expected(k)))

  "eth_getHeaderByNumber / eth_getHeaderByHash encoding" should {
    "match get-genesis.io" taggedAs UnitTest in assertMatchesVector(Genesis)
    "match get-latest.io / get-safe.io / get-finalized.io (Amsterdam, incl. blockAccessListHash and slotNumber)" taggedAs UnitTest in
      assertMatchesVector(AmsterdamLatest)

    "never carry body-derived keys" taggedAs UnitTest in {
      val json = EthBlocksJsonMethodsImplicits.eth_getHeaderByHash.encodeJson(
        HeaderByHashResponse(Some(headerResponse(headerFrom(fields(AmsterdamLatest)))))
      )
      val keys = fields(org.json4s.native.JsonMethods.compact(org.json4s.native.JsonMethods.render(json))).keySet
      keys.intersect(Set("size", "transactions", "uncles", "withdrawals", "totalDifficulty")) shouldBe empty
    }

    "encode an unknown number as null (get-header-notfound.io)" taggedAs UnitTest in {
      EthBlocksJsonMethodsImplicits.eth_getHeaderByNumber.encodeJson(HeaderByNumberResponse(None)) shouldBe JNull
    }

    "encode an unknown or zero hash as null (get-header-by-empty-hash.io, get-header-by-notfound-hash.io)" taggedAs UnitTest in {
      EthBlocksJsonMethodsImplicits.eth_getHeaderByHash.encodeJson(HeaderByHashResponse(None)) shouldBe JNull
    }
  }

  "eth_getHeaderByNumber / eth_getHeaderByHash decoding" should {
    "accept number and tag params" taggedAs UnitTest in {
      val d = EthBlocksJsonMethodsImplicits.eth_getHeaderByNumber
      List("latest", "safe", "finalized", "earliest", "0x0", "0x3e8").foreach { p =>
        d.decodeJson(Some(JArray(List(JString(p))))) should matchPattern { case Right(HeaderByNumberRequest(_)) => }
      }
    }
    "accept the zero hash" taggedAs UnitTest in {
      EthBlocksJsonMethodsImplicits.eth_getHeaderByHash.decodeJson(
        Some(JArray(List(JString("0x" + "00" * 32))))
      ) should matchPattern { case Right(HeaderByHashRequest(_)) => }
    }
    "reject wrong arity with -32602" taggedAs UnitTest in {
      EthBlocksJsonMethodsImplicits.eth_getHeaderByNumber.decodeJson(Some(JArray(Nil))).left.map(_.code) shouldBe Left(
        InvalidParams().code
      )
      EthBlocksJsonMethodsImplicits.eth_getHeaderByHash.decodeJson(None).left.map(_.code) shouldBe Left(
        InvalidParams().code
      )
    }
  }
