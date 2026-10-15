package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.testing.Tags.*

// scalastyle:off magic.number
/** The block object `eth_getBlockByNumber` / `eth_getBlockByHash` return for an Amsterdam header.
  *
  * Vectors: execution-apis `tests/eth_getBlockByNumber/get-latest.io` (block 0x3c of the Amsterdam rpc-compat chain,
  * ba884e7d77 #867) and `get-block-prague-fork.io` (block 0x2d, Prague). Each expected object is turned back into a
  * header, and the header's hash must equal the vector's `hash` — so the header under test is exactly the one hive's
  * client holds — before the encoder's output is compared key for key.
  *
  * Excluded from the comparison: `transactions` and `size`, which depend on the body (the header is all this spec
  * rebuilds). Everything else must match, and no key may be added or missing: hive's rpc-compat diff counts both.
  */
class AmsterdamBlockResponseSpec extends AnyWordSpec with Matchers:

  private val AmsterdamLatest =
    """{"baseFeePerGas":"0xbc78d4","blobGasUsed":"0x20000","blockAccessListHash":"0x54306fbc4e818446e59cbd69acd4075fe1be6ca64d968b11da6d1b410ed2a86b","difficulty":"0x0","excessBlobGas":"0x0","extraData":"0x","gasLimit":"0xbebc200","gasUsed":"0x37f84","hash":"0x52bccfc9c4d4874b1898c0fbd0ae3b598a0edc7b94d9b796857dbbe1093c32e0","logsBloom":"0x""" +
      "00" * 256 +
      """","miner":"0x0000000000000000000000000000000000000000","mixHash":"0x0000000000000000000000000000000000000000000000000000000000000000","nonce":"0x0000000000000000","number":"0x3c","parentBeaconBlockRoot":"0xefc50f4fc1430b6d5d043065201692a4a02252fef0699394631f5213a5667547","parentHash":"0x6e3bd94343c61839a0044d28115f28a7dd4f6a2b2add2335991e9520adcd3d18","receiptsRoot":"0x2154241b99bb7e50bc6fb658399056c79170d5a9930b3903f138480c169b7905","requestsHash":"0xe3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","sha3Uncles":"0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347","size":"0x4b1","slotNumber":"0x3","stateRoot":"0xa6bd32516beae9780a8c90563bb28c18f4299855f3f9d7c1de2db9a609ce63ec","timestamp":"0x258","transactionsRoot":"0x48adddf21f81471c34a0d11acaeadb44e6f7dbddd9b26a0edcdfd802cf32ea3c","uncles":[],"withdrawals":[{"index":"0x6","validatorIndex":"0x5","address":"0xe7d13f7aa2a838d24c59b40186a0aca1e21cffcc","amount":"0x64"}],"withdrawalsRoot":"0xfa56969d3f9ca7445b2c645d7861e17ecb08fc3ea3dee2a60c4281bde9bafd4e"}"""

  private val PragueFork =
    """{"baseFeePerGas":"0x56b7165","blobGasUsed":"0x0","difficulty":"0x0","excessBlobGas":"0x0","extraData":"0x","gasLimit":"0xbebc200","gasUsed":"0x6c789","hash":"0xd7e04da85273b53ff4e0328de7e993f66d1cc4691899149f44c6f4440b2c4a8b","logsBloom":"0x00000040008080000000000000000040000000000000008200000000000000000000000100000000000000000000002008000000001000000000000000000100000000000000140000000000000000020000000000000000000000000200020000000000000020000000040000000000000000000000000001000000002000000000000000000000000000040000000000000000000000000000000000000200000000000000080000000100000000000000000800000000000000008000000010010000000000000000000020000000000000000100000020000000000000000000000000000000000000004000000000000000000000000000100000200010","miner":"0x0000000000000000000000000000000000000000","mixHash":"0x0000000000000000000000000000000000000000000000000000000000000000","nonce":"0x0000000000000000","number":"0x2d","parentBeaconBlockRoot":"0x4b118bd31ed2c4eeb81dc9e3919e9989994333fe36f147c2930f12c53f0d3c78","parentHash":"0x2a6089a3474e6f45bdb78c0f6c51c505ada9004cf1da338a14cc879736edcbd9","receiptsRoot":"0x63e56940e8efe2b52848f647cbeb8203649c8b147b73a661d48887d7957bb457","requestsHash":"0x57cac3e52cdcd73e52bd9e54956e0eae370f00ed41f68edc78266bdb46c3a543","sha3Uncles":"0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347","size":"0x5ed","stateRoot":"0x073f08cbeaa91d2410e70354c71fe35a3464f8ef6e559dc155e0ad558bdd805b","timestamp":"0x1c2","transactionsRoot":"0xd0984411f10966c3379453dda4d524cd69e32e7e9f73bf11f45feaeb98aa8b07","uncles":[],"withdrawals":[],"withdrawalsRoot":"0x56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"}"""

  private val BodyDependent = Set("transactions", "size")

  private def fields(json: String): Map[String, JValue] = parse(json) match
    case JObject(fs) => fs.toMap
    case other       => fail(s"not an object: $other")

  private def str(f: Map[String, JValue], key: String): String = f.get(key) match
    case Some(JString(s)) => s
    case other            => fail(s"$key: not a string ($other)")
  private def bytes(f: Map[String, JValue], key: String): ByteString =
    ByteString(Hex.decode(str(f, key).stripPrefix("0x")))
  private def qty(f: Map[String, JValue], key: String): BigInt = BigInt(str(f, key).stripPrefix("0x"), 16)
  private def qtyOf(v: JValue): BigInt = v match
    case JString(s) => BigInt(s.stripPrefix("0x"), 16)
    case other      => fail(s"not a quantity: $other")

  /** The header and body (no transactions) the expected block object describes. */
  private def blockFrom(f: Map[String, JValue]): Block =
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
      else
        HefPostPrague(
          qty(f, "baseFeePerGas"),
          bytes(f, "withdrawalsRoot"),
          qty(f, "blobGasUsed"),
          qty(f, "excessBlobGas"),
          bytes(f, "parentBeaconBlockRoot"),
          bytes(f, "requestsHash")
        )
    val header = BlockHeader(
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
    val withdrawals = f("withdrawals") match
      case JArray(items) =>
        items.map {
          case JObject(w) =>
            val m = w.toMap
            val address = m("address") match
              case JString(a) => Address(a)
              case other      => fail(s"address: $other")
            Withdrawal(qtyOf(m("index")), qtyOf(m("validatorIndex")), address, qtyOf(m("amount")))
          case other => fail(s"withdrawal: $other")
        }
      case other => fail(s"withdrawals: $other")
    Block(header, BlockBody(Nil, Nil, withdrawals = Some(withdrawals)))

  /** The object the eth_getBlockBy* encoder produces for `block`, on the ETH-family read path. */
  private def encoded(block: Block): Map[String, JValue] =
    EthBlocksJsonMethodsImplicits.blockResponseEncoder.encodeJson(
      BlockResponse(block, weight = None, fullTxs = false, pendingBlock = false, emitTotalDifficulty = false)
    ) match
      case JObject(fs) => fs.toMap
      case other       => fail(s"not an object: $other")

  private def assertMatchesVector(vector: String): Unit =
    val expected = fields(vector)
    val block = blockFrom(expected)
    // The rebuilt header IS the vector's block: same hash.
    ("0x" + Hex.toHexString(block.header.hash.value.toArray)) shouldBe str(expected, "hash")

    val actual = encoded(block)
    // The literals above drop the vectors' `transactions` (the body is not rebuilt); the encoder always emits it.
    (actual.keySet -- BodyDependent) shouldBe (expected.keySet -- BodyDependent)
    (expected.keySet -- BodyDependent).foreach { key =>
      withClue(s"$key: ")(actual(key) shouldBe expected(key))
    }

  "the eth_getBlockBy* block object" should {

    "carry blockAccessListHash and slotNumber for an Amsterdam header (get-latest.io)" taggedAs UnitTest in {
      assertMatchesVector(AmsterdamLatest)
      val actual = encoded(blockFrom(fields(AmsterdamLatest)))
      actual("blockAccessListHash") shouldBe
        JString("0x54306fbc4e818446e59cbd69acd4075fe1be6ca64d968b11da6d1b410ed2a86b")
      actual("slotNumber") shouldBe JString("0x3")
    }

    "add neither key to a Prague header (get-block-prague-fork.io)" taggedAs UnitTest in {
      assertMatchesVector(PragueFork)
      val actual = encoded(blockFrom(fields(PragueFork)))
      actual.keySet should not contain "blockAccessListHash"
      actual.keySet should not contain "slotNumber"
    }

    "add neither key to a pre-merge (ETC-shaped) header" taggedAs UnitTest in {
      val header = blockFrom(fields(PragueFork)).header.copy(extraFields = HeaderExtraFields.HefEmpty)
      val actual = EthBlocksJsonMethodsImplicits.blockResponseEncoder.encodeJson(
        BlockResponse(Block(header, BlockBody(Nil, Nil)))
      ) match
        case JObject(fs) => fs.map(_._1).toSet
        case other       => fail(s"not an object: $other")
      actual should not contain "blockAccessListHash"
      actual should not contain "slotNumber"
    }
  }
