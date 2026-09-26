package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import java.util.zip.GZIPInputStream

import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.BlockAccessList.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostAmsterdam
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostPrague
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.ByteUtils

/** EIP-7928 block access list codec, against live Platåberget blocks and the strict-decoding rules.
  *
  * Live vectors (`src/test/resources/eip7928/plataberget-bal-<number>.json.gz`, gzipped because each carries a 64 KiB
  * contract deployment): Platåberget blocks 275,629 / 275,635 / 275,647 / 275,654, fetched from
  * `rpc.plataberget.ethpandaops.io` (reth). Each holds the header fields verbatim from `eth_getBlockByNumber` and the
  * access list verbatim from `eth_getBlockAccessList`. The rebuilt 23-field header must hash to the canonical block
  * hash, which authenticates its `blockAccessListHash`; the access list must then encode to bytes whose keccak is that
  * hash. So a pass means fukuii's encoding is the one the network committed to, byte for byte.
  */
class BlockAccessListSpec extends AnyFlatSpec with Matchers:

  // ── Live vectors ────────────────────────────────────────────────────────────────────────────────────────────────

  private val LiveBlocks: Seq[Int] = Seq(275629, 275635, 275647, 275654)

  /** `accounts + distinct slots per account (reads ∪ writes)`, computed from the JSON independently of the codec. */
  private val ExpectedItemCount: Map[Int, Long] = Map(275629 -> 36L, 275635 -> 46L, 275647 -> 35L, 275654 -> 29L)

  private def resource(block: Int): JValue =
    val in = new GZIPInputStream(getClass.getResourceAsStream(s"/eip7928/plataberget-bal-$block.json.gz"))
    try parse(new String(in.readAllBytes(), "UTF-8"))
    finally in.close()

  private def str(v: JValue): String = v.values.toString
  private def bytes(v: JValue): ByteString = ByteString(Hex.decode(str(v).stripPrefix("0x")))
  private def quantity(v: JValue): BigInt = BigInt(str(v).stripPrefix("0x"), 16)
  private def arr(v: JValue): List[JValue] = v match
    case JArray(items) => items
    case other         => fail(s"expected a JSON array, got $other")
  private def hex(b: ByteString): String = "0x" + Hex.toHexString(b.toArray)

  private def header(h: JValue): BlockHeader =
    BlockHeader(
      parentHash = BlockHash(bytes(h \ "parentHash")),
      ommersHash = BlockHash(bytes(h \ "sha3Uncles")),
      beneficiary = bytes(h \ "miner"),
      stateRoot = TrieRoot(bytes(h \ "stateRoot")),
      transactionsRoot = TrieRoot(bytes(h \ "transactionsRoot")),
      receiptsRoot = TrieRoot(bytes(h \ "receiptsRoot")),
      logsBloom = BloomFilter(bytes(h \ "logsBloom")),
      difficulty = Difficulty(quantity(h \ "difficulty")),
      number = BlockNumber(quantity(h \ "number")),
      gasLimit = GasAmount(quantity(h \ "gasLimit")),
      gasUsed = GasAmount(quantity(h \ "gasUsed")),
      unixTimestamp = Timestamp(quantity(h \ "timestamp").toLong),
      extraData = bytes(h \ "extraData"),
      mixHash = BlockHash(bytes(h \ "mixHash")),
      nonce = bytes(h \ "nonce"),
      extraFields = HefPostAmsterdam(
        baseFee = quantity(h \ "baseFeePerGas"),
        withdrawalsRoot = bytes(h \ "withdrawalsRoot"),
        blobGasUsed = quantity(h \ "blobGasUsed"),
        excessBlobGas = quantity(h \ "excessBlobGas"),
        parentBeaconBlockRoot = bytes(h \ "parentBeaconBlockRoot"),
        requestsHash = bytes(h \ "requestsHash"),
        blockAccessListHash = bytes(h \ "blockAccessListHash"),
        slotNumber = quantity(h \ "slotNumber")
      )
    )

  /** reth's `eth_getBlockAccessList` JSON: slots and storage values as 32-byte words, the rest as quantities. */
  private def accessList(json: JValue): BlockAccessList =
    BlockAccessList(arr(json).map { a =>
      AccountChanges(
        address = Address(bytes(a \ "address")),
        storageChanges = arr(a \ "storageChanges").map { s =>
          SlotChanges(
            UInt256(quantity(s \ "key")),
            arr(s \ "changes").map(c => StorageChange(quantity(c \ "index").toLong, UInt256(quantity(c \ "value"))))
          )
        },
        storageReads = arr(a \ "storageReads").map(r => UInt256(quantity(r))),
        balanceChanges = arr(a \ "balanceChanges").map(c =>
          BalanceChange(quantity(c \ "index").toLong, UInt256(quantity(c \ "value")))
        ),
        nonceChanges =
          arr(a \ "nonceChanges").map(c => NonceChange(quantity(c \ "index").toLong, quantity(c \ "value"))),
        codeChanges = arr(a \ "codeChanges").map(c => CodeChange(quantity(c \ "index").toLong, bytes(c \ "code")))
      )
    })

  LiveBlocks.foreach { block =>
    s"Platåberget block $block" should "rebuild to its canonical hash, with the Amsterdam header accessors reading it" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val json = resource(block)
      val h = header(json \ "header")
      hex(h.hash.value) shouldBe str(json \ "header" \ "hash")
      h.number shouldBe BlockNumber(block)
      h.blockAccessListHash shouldBe Some(bytes(json \ "header" \ "blockAccessListHash"))
      h.slotNumber shouldBe Some(quantity(json \ "header" \ "slotNumber"))
    }

    it should "encode its access list to the bytes the header commits to" taggedAs (UnitTest, ConsensusTest) in {
      val json = resource(block)
      val bal = accessList(json \ "blockAccessList")
      bal.hash shouldBe header(json \ "header").blockAccessListHash.get
      ByteString(com.chipprbots.ethereum.crypto.kec256(bal.toBytes.toArray)) shouldBe bal.hash
    }

    it should "strictly decode those bytes back to the same list and the same bytes" taggedAs (
      UnitTest,
      ConsensusTest
    ) in {
      val bal = accessList(resource(block) \ "blockAccessList")
      val encoded = bal.toBytes
      BlockAccessList.decode(encoded) shouldBe Right(bal)
      BlockAccessList.decode(encoded).map(_.toBytes) shouldBe Right(encoded)
    }

    it should "count its size-limit items as execution-specs does" taggedAs (UnitTest, ConsensusTest) in {
      val json = resource(block)
      val bal = accessList(json \ "blockAccessList")
      bal.itemCount shouldBe ExpectedItemCount(block)
      bal.itemCount should be <= (quantity(json \ "header" \ "gasLimit") / 2000).toLong
    }
  }

  // ── Constants and accessors ─────────────────────────────────────────────────────────────────────────────────────

  "the empty block access list" should "encode as 0xc0 and hash to keccak256(0xc0) = 0x1dcc4de8…9347" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    BlockAccessList.Empty.toBytes shouldBe ByteString(0xc0.toByte)
    hex(BlockAccessList.EmptyHash) shouldBe "0x1dcc4de8dec75d7aab85b567b6ccd41ad312451b948a7413f0a142fd40d49347"
    BlockAccessList.decode(ByteString(0xc0.toByte)) shouldBe Right(BlockAccessList.Empty)
    BlockAccessList.Empty.itemCount shouldBe 0L
  }

  "BlockHeader.blockAccessListHash and slotNumber" should "be empty on every pre-Amsterdam header shape" taggedAs (
    UnitTest
  ) in {
    val legacy = Fixtures.Blocks.ValidBlock.header
    val prague = legacy.copy(extraFields =
      HefPostPrague(
        baseFee = 7,
        withdrawalsRoot = ByteString(Array.fill[Byte](32)(1)),
        blobGasUsed = 0,
        excessBlobGas = 0,
        parentBeaconBlockRoot = ByteString(Array.fill[Byte](32)(2)),
        requestsHash = ByteString(Array.fill[Byte](32)(3))
      )
    )
    Seq(legacy, prague).foreach { h =>
      h.blockAccessListHash shouldBe None
      h.slotNumber shouldBe None
    }
  }

  // ── Hand-built lists ────────────────────────────────────────────────────────────────────────────────────────────

  private def address(last: Int): Address = Address(ByteString(Array.fill[Byte](19)(0) :+ last.toByte))

  /** Every change kind, with each field at its type's bounds (0, u32 max, u64 max, u256 max, empty code). */
  private val fullList: BlockAccessList = BlockAccessList(
    Seq(
      AccountChanges(
        address(1),
        storageChanges = Seq(
          SlotChanges(UInt256.Zero, Seq(StorageChange(0, UInt256.Zero), StorageChange(3, UInt256.MaxValue))),
          SlotChanges(UInt256(0xff), Seq(StorageChange(1, UInt256(1)))),
          SlotChanges(UInt256(0x100), Seq(StorageChange(MaxBlockAccessIndex, UInt256(2))))
        ),
        storageReads = Seq(UInt256(5), UInt256.MaxValue),
        balanceChanges = Seq(BalanceChange(0, UInt256.Zero), BalanceChange(2, UInt256.MaxValue)),
        nonceChanges = Seq(NonceChange(1, MaxNonce)),
        codeChanges = Seq(CodeChange(1, ByteString.empty), CodeChange(2, ByteString(0x60, 0x00)))
      ),
      AccountChanges(address(2), Nil, Nil, Nil, Nil, Nil)
    )
  )

  "a hand-built access list" should "round-trip through the strict decoder with every field at its bounds" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    BlockAccessList.decode(fullList.toBytes) shouldBe Right(fullList)
    // Slots 0xff < 0x100 numerically although 0x0100 sorts first as minimal RLP bytes: numeric order is the rule.
    fullList.itemCount shouldBe (1 + 5) + 1
  }

  it should "count a slot that is both read and written once, as execution-specs does" taggedAs (UnitTest) in {
    // Not a decodable list (reads and writes must be disjoint); itemCount is defined over the union anyway.
    val overlapping = BlockAccessList(
      Seq(
        AccountChanges(
          address(1),
          storageChanges = Seq(SlotChanges(UInt256(1), Seq(StorageChange(1, UInt256(9))))),
          storageReads = Seq(UInt256(1), UInt256(2)),
          Nil,
          Nil,
          Nil
        )
      )
    )
    overlapping.itemCount shouldBe 3L
  }

  // ── Strict decoding: rejections ────────────────────────────────────────────────────────────────────────────────

  private def u(v: BigInt): RLPValue = RLPValue(ByteUtils.bigIntToUnsignedByteArray(v))
  private def raw(b: Int*): RLPValue = RLPValue(b.map(_.toByte).toArray)
  private def addr(last: Int): RLPValue = RLPValue(address(last).toArray)
  private def change(index: RLPEncodeable, value: RLPEncodeable): RLPList = RLPList(index, value)
  private def account(
      address: RLPEncodeable,
      storageChanges: RLPList = RLPList(),
      storageReads: RLPList = RLPList(),
      balanceChanges: RLPList = RLPList(),
      nonceChanges: RLPList = RLPList(),
      codeChanges: RLPList = RLPList()
  ): RLPList = RLPList(address, storageChanges, storageReads, balanceChanges, nonceChanges, codeChanges)
  private def decodeTree(tree: RLPEncodeable): Either[String, BlockAccessList] =
    BlockAccessList.decode(ByteString(rlp.encode(tree)))

  private def rejects(result: Either[String, BlockAccessList], reason: String): Unit =
    result match
      case Left(error) => error should include(reason)
      case Right(bal)  => fail(s"accepted $bal, expected a rejection containing '$reason'")

  "BlockAccessList.decode" should "reject an empty byte string (the empty list is 0xc0)" taggedAs (UnitTest) in {
    rejects(BlockAccessList.decode(ByteString.empty), "empty input")
  }

  it should "reject a byte string where the list belongs" taggedAs (UnitTest) in {
    rejects(BlockAccessList.decode(ByteString(0x80.toByte)), "block access list: expected a list")
  }

  it should "reject a leading zero byte in any integer" taggedAs (UnitTest) in {
    rejects(
      decodeTree(RLPList(account(addr(1), balanceChanges = RLPList(change(u(1), raw(0x00, 0x05)))))),
      "accounts[0].balance_changes[0].post_balance: leading zero byte"
    )
    // Zero itself must be the empty string, not the single byte 0x00.
    rejects(
      decodeTree(RLPList(account(addr(1), nonceChanges = RLPList(change(raw(0x00), u(1)))))),
      "accounts[0].nonce_changes[0].block_access_index: leading zero byte"
    )
    rejects(
      decodeTree(RLPList(account(addr(1), storageReads = RLPList(raw(0x00, 0x01))))),
      "accounts[0].storage_reads[0]: leading zero byte"
    )
  }

  it should "reject accounts out of address order, and a repeated address" taggedAs (UnitTest) in {
    rejects(decodeTree(RLPList(account(addr(2)), account(addr(1)))), "accounts[1]: address not strictly above")
    rejects(decodeTree(RLPList(account(addr(1)), account(addr(1)))), "accounts[1]: address not strictly above")
    // Unsigned comparison: 0x80.. sorts after 0x01.., although the byte 0x80 is negative as a JVM byte.
    decodeTree(RLPList(account(addr(0x01)), account(addr(0x80)))).isRight shouldBe true
  }

  it should "order slots numerically, not by their minimal RLP bytes" taggedAs (UnitTest) in {
    def slot(s: Int): RLPList = RLPList(u(s), RLPList(change(u(1), u(1))))
    rejects(
      decodeTree(RLPList(account(addr(1), storageChanges = RLPList(slot(2), slot(1))))),
      "accounts[0].storage_changes[1]: slot not strictly above"
    )
    rejects(
      decodeTree(RLPList(account(addr(1), storageChanges = RLPList(slot(1), slot(1))))),
      "accounts[0].storage_changes[1]: slot not strictly above"
    )
    // 0xff < 0x0100 as numbers, although the byte string 0x0100 sorts before 0xff.
    decodeTree(RLPList(account(addr(1), storageChanges = RLPList(slot(0xff), slot(0x100))))).isRight shouldBe true
    rejects(
      decodeTree(RLPList(account(addr(1), storageChanges = RLPList(slot(0x100), slot(0xff))))),
      "accounts[0].storage_changes[1]: slot not strictly above"
    )
    decodeTree(RLPList(account(addr(1), storageReads = RLPList(u(0xff), u(0x100))))).isRight shouldBe true
    rejects(
      decodeTree(RLPList(account(addr(1), storageReads = RLPList(u(0x100), u(0xff))))),
      "accounts[0].storage_reads[1]: slot not strictly above"
    )
  }

  it should "reject a repeated or descending block access index in every change list" taggedAs (UnitTest) in {
    rejects(
      decodeTree(RLPList(account(addr(1), balanceChanges = RLPList(change(u(1), u(5)), change(u(1), u(6)))))),
      "accounts[0].balance_changes[1]: block_access_index not strictly above"
    )
    rejects(
      decodeTree(RLPList(account(addr(1), nonceChanges = RLPList(change(u(2), u(5)), change(u(1), u(6)))))),
      "accounts[0].nonce_changes[1]: block_access_index not strictly above"
    )
    rejects(
      decodeTree(RLPList(account(addr(1), codeChanges = RLPList(change(u(3), raw()), change(u(3), raw()))))),
      "accounts[0].code_changes[1]: block_access_index not strictly above"
    )
    rejects(
      decodeTree(
        RLPList(
          account(addr(1), storageChanges = RLPList(RLPList(u(1), RLPList(change(u(4), u(1)), change(u(4), u(2))))))
        )
      ),
      "accounts[0].storage_changes[0].changes[1]: block_access_index not strictly above"
    )
  }

  it should "reject a field wider than its type" taggedAs (UnitTest) in {
    rejects(
      decodeTree(RLPList(account(addr(1), balanceChanges = RLPList(change(u(BigInt(1) << 32), u(1)))))),
      "accounts[0].balance_changes[0].block_access_index: 5 bytes, more than the 4"
    )
    rejects(
      decodeTree(RLPList(account(addr(1), nonceChanges = RLPList(change(u(1), u(BigInt(1) << 64)))))),
      "accounts[0].nonce_changes[0].new_nonce: 9 bytes, more than the 8"
    )
    rejects(
      decodeTree(RLPList(account(addr(1), balanceChanges = RLPList(change(u(1), u(BigInt(1) << 256)))))),
      "accounts[0].balance_changes[0].post_balance: 33 bytes, more than the 32"
    )
    rejects(
      decodeTree(RLPList(account(addr(1), storageReads = RLPList(u(BigInt(1) << 256))))),
      "accounts[0].storage_reads[0]: 33 bytes, more than the 32"
    )
    rejects(decodeTree(RLPList(account(RLPValue(Array.fill[Byte](21)(1))))), "accounts[0].address: 21 bytes")
    rejects(decodeTree(RLPList(account(RLPValue(Array.fill[Byte](19)(1))))), "accounts[0].address: 19 bytes")
  }

  it should "reject a slot that is both read and written, and a SlotChanges with no change" taggedAs (UnitTest) in {
    rejects(
      decodeTree(
        RLPList(
          account(addr(1), storageChanges = RLPList(RLPList(u(7), RLPList(change(u(1), u(1))))), RLPList(u(7)))
        )
      ),
      "accounts[0].storage_reads[0]: slot is also written"
    )
    rejects(
      decodeTree(RLPList(account(addr(1), storageChanges = RLPList(RLPList(u(7), RLPList()))))),
      "accounts[0].storage_changes[0].changes: empty"
    )
  }

  it should "reject a struct with the wrong number of fields, or a list where a scalar belongs" taggedAs (UnitTest) in {
    rejects(decodeTree(RLPList(RLPList(addr(1), RLPList(), RLPList(), RLPList(), RLPList()))), "5 fields, expected 6")
    rejects(
      decodeTree(RLPList(account(addr(1), balanceChanges = RLPList(RLPList(u(1), u(2), u(3)))))),
      "accounts[0].balance_changes[0]: 3 fields, expected 2"
    )
    rejects(
      decodeTree(RLPList(account(addr(1), storageReads = RLPList(RLPList())))),
      "accounts[0].storage_reads[0]: expected a byte string, found a list"
    )
  }

  it should "reject trailing bytes, a non-minimal prefix and a truncated encoding" taggedAs (UnitTest) in {
    val canonical = fullList.toBytes
    rejects(BlockAccessList.decode(canonical ++ ByteString(0x00)), "non-canonical RLP framing")

    // [[addr, [], [], [[5, 7]], [], []]] with the index 5 written as 0x81 0x05 instead of the single byte 0x05.
    val accountPayload = rlp.encode(addr(1)) ++ Array(0xc0, 0xc0).map(_.toByte) ++
      Array(0xc4, 0xc3, 0x81, 0x05, 0x07, 0xc0, 0xc0).map(_.toByte)
    val nonMinimalIndex =
      Array((0xc0 + accountPayload.length + 1).toByte, (0xc0 + accountPayload.length).toByte) ++ accountPayload
    rejects(BlockAccessList.decode(ByteString(nonMinimalIndex)), "non-canonical RLP framing")

    // The same one-account list behind a long-form list header, though its payload is under 56 bytes.
    val minimalAccount = rlp.encode(account(addr(1)))
    val longHeader = Array(0xf8.toByte, minimalAccount.length.toByte) ++ minimalAccount
    rejects(BlockAccessList.decode(ByteString(longHeader)), "non-canonical RLP framing")

    // Every truncation, down to the empty string, is a Left and never a thrown exception.
    (0 until canonical.length).foreach { n =>
      withClue(s"first $n of ${canonical.length} bytes: ")(
        BlockAccessList.decode(canonical.take(n)).isLeft shouldBe true
      )
    }
  }

  it should "return a Left, not throw, for lists nested far deeper than an access list" taggedAs (UnitTest) in {
    // A block access list nests five lists deep; fukuii's RLP reader recurses once per level. Build [[[…[]…]]] a
    // million levels deep, inside out: each level's header encodes the length of everything inside it.
    def listHeader(payloadLength: Int): Array[Byte] =
      if payloadLength < 56 then Array((0xc0 + payloadLength).toByte)
      else
        val length = BigInt(payloadLength).toByteArray.dropWhile(_ == 0)
        (0xf7 + length.length).toByte +: length
    val depth = 1_000_000
    val headers = new Array[Array[Byte]](depth)
    var inner = 0
    for level <- 0 until depth do
      headers(level) = listHeader(inner)
      inner += headers(level).length
    val out = new java.io.ByteArrayOutputStream(inner)
    for level <- (depth - 1) to 0 by -1 do out.write(headers(level))

    BlockAccessList.decode(ByteString(out.toByteArray)).isLeft shouldBe true
  }
