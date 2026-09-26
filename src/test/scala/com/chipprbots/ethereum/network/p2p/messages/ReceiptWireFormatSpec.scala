package com.chipprbots.ethereum.network.p2p.messages

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.codec.ReceiptCodecs.toEth69Receipt
import com.chipprbots.ethereum.consensus.validators.std.MptListValidator
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPEncodeable
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue

/** Receipts on the wire, pinned to go-ethereum's bytes.
  *
  * eth/66-68: `[postStateOrStatus, cumulativeGasUsed, bloom, logs]`, a typed receipt held as ONE RLP byte string of
  * `type || rlp(receipt)`. fukuii put the bare concatenation in the list instead, which go-ethereum and core-geth
  * reject as a short typed receipt, so neither could fetch the receipts of a block holding a typed tx from fukuii.
  *
  * eth/69 (EIP-7642), which eth/70-72 keep: `[txType, postStateOrStatus, cumulativeGasUsed, logs]` — no bloom, and
  * every receipt a plain four-item list, legacy ones carrying type 0.
  *
  * fukuii used to serve `[postStateOrStatus, cumulativeGasUsed, logs]` for a legacy receipt and the EIP-2718 prefixed
  * form for a typed one. go-ethereum's decoder rejects both, and when it hashes a block's receipts it treats a rejected
  * receipt as absent, so hive's GetLargeReceipts saw the empty-trie root for the first block of its window. fukuii's
  * reader had the mirror problem: a four-item network receipt matched its eth/68 `[status, gas, bloom, logs]` pattern
  * and decoded without error into a receipt whose status, gas and bloom were all wrong.
  *
  * The vectors below come from go-ethereum, built from the same six receipts as `receipts`: `rlp.EncodeToBytes` of the
  * `[]*types.Receipt` for eth/68 (the resource file), `eth.NewReceiptList` + `rlp.EncodeToBytes` for eth/69, and
  * `types.DeriveSha` for the root.
  */
class ReceiptWireFormatSpec extends AnyFlatSpec with Matchers:

  private def bloomOf(logs: Seq[TxLogEntry]): BloomFilter = BloomFilter(ledger.BloomFilter.create(logs))

  private def receipt(outcome: TransactionOutcome, cumulativeGasUsed: BigInt, logs: Seq[TxLogEntry] = Nil) =
    LegacyReceipt(outcome, cumulativeGasUsed, bloomOf(logs), logs)

  private val log = TxLogEntry(
    Address(ByteString(Hex.decode("11" * 20))),
    Seq(ByteString(Hex.decode("aa" * 32))),
    ByteString(Array[Byte](1, 2))
  )

  private val receipts: Seq[Receipt] = Seq(
    receipt(SuccessOutcome, 21000),
    Type02Receipt(receipt(FailureOutcome, 63000, Seq(log))),
    Type01Receipt(receipt(SuccessOutcome, 100000)),
    receipt(HashOutcome(ByteString(Hex.decode("22" * 32))), 142000),
    Type03Receipt(receipt(SuccessOutcome, 163000)),
    Type04Receipt(receipt(SuccessOutcome, 190000))
  )

  /** go-ethereum's eth/68 encoding of `receipts` as one block's receipt list. */
  private val gethEth68BlockList: Array[Byte] =
    val is = getClass.getResourceAsStream("/eth68-receipt-list-go-ethereum.rlp")
    try is.readAllBytes()
    finally is.close()

  /** go-ethereum's eth/69 encoding of `receipts` as one block's receipt list. */
  private val gethBlockList = Hex.decode(
    "f88cc68001825208c0f843028082f618f83cf83a941111111111111111111111111111111111111111e1a0aaaaaaaaaaaaaaaaaaaaaaaa" +
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa820102c70101830186a0c0e780a022222222222222222222222222222222222222222222" +
      "2222222222222222222283022ab0c0c7030183027cb8c0c704018302e630c0"
  )

  /** go-ethereum's eth/69 encoding of each receipt alone, as a one-receipt block list. */
  private val gethSingles = Seq(
    "c7c68001825208c0",
    "f845f843028082f618f83cf83a941111111111111111111111111111111111111111e1a0aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
      "aaaaaaaaaaaaaaaaaaaaaaaa820102",
    "c8c70101830186a0c0",
    "e8e780a0222222222222222222222222222222222222222222222222222222222222222283022ab0c0",
    "c8c7030183027cb8c0",
    "c8c704018302e630c0"
  )

  /** go-ethereum's `types.DeriveSha` over `receipts`, which it also derives from `gethBlockList`. */
  private val gethReceiptsRoot = "41dece3b55a3dc635cae82a36884aa29aa401ced1423803347ea54b7a2700940"

  private def encodeList(rs: Seq[Receipt]): Array[Byte] =
    rlp.encode(RLPList(rs.map(r => ETHPackets.ReceiptBloomFreeEnc(r).toRLPEncodable)*))

  private def decodeList(bytes: Array[Byte]): Seq[Receipt] = rlp.rawDecode(bytes) match
    case RLPList(items*) => items.map(_.toEth69Receipt)
    case other           => fail(s"not a list: $other")

  /** The receipts-trie root, built as `MptListValidator.isValid` builds it for block validation. */
  private def receiptsRootOf(rs: Seq[Receipt]): String =
    val trie = MerklePatriciaTrie[Int, Receipt](StateStorage.getReadOnlyStorage(EphemDataSource()))(
      MptListValidator.intByteArraySerializable,
      Receipt.byteArraySerializable
    )
    Hex.toHexString(rs.zipWithIndex.foldLeft(trie)((t, r) => t.put(r._2, r._1)).getRootHash)

  "ReceiptBloomEnc" should "encode a block's eth/68 receipt list byte-for-byte as go-ethereum does" in {
    val encoded = rlp.encode(RLPList(receipts.map(r => ETHPackets.ReceiptBloomEnc(r).toRLPEncodable)*))
    Hex.toHexString(encoded) shouldBe Hex.toHexString(gethEth68BlockList)
  }

  it should "hold a typed receipt as one byte string and leave a legacy receipt as a list" in {
    ETHPackets.ReceiptBloomEnc(receipts.head).toRLPEncodable shouldBe a[RLPList]
    ETHPackets.ReceiptBloomEnc(receipts(1)).toRLPEncodable match
      case RLPValue(bytes) =>
        bytes.head shouldBe Transaction.Type02
        rlp.rawDecode(bytes.tail) shouldBe a[RLPList]
      case other => fail(s"not a byte string: $other")
  }

  "ReceiptBloomFreeEnc" should "encode each receipt type byte-for-byte as go-ethereum does" in {
    receipts.zip(gethSingles).foreach { case (r, expected) =>
      withClue(s"${r.getClass.getSimpleName}: ") {
        Hex.toHexString(encodeList(Seq(r))) shouldBe expected
      }
    }
  }

  it should "encode a block's receipt list byte-for-byte as go-ethereum does" in {
    Hex.toHexString(encodeList(receipts)) shouldBe Hex.toHexString(gethBlockList)
  }

  it should "put the tx type first, even for a legacy receipt, and send no bloom" in {
    ETHPackets.ReceiptBloomFreeEnc(receipts.head).toRLPEncodable match
      case RLPList(RLPValue(txType), RLPValue(status), _, _: RLPList) =>
        txType shouldBe empty // type 0
        status.toSeq shouldBe Seq[Byte](1)
      case other => fail(s"not a four-item receipt: $other")
  }

  "toEth69Receipt" should "decode go-ethereum's block list back to the same receipts, bloom recomputed from the logs" in {
    decodeList(gethBlockList) shouldBe receipts
  }

  it should "yield receipts that hash to the root go-ethereum derives from the same bytes" in {
    receiptsRootOf(decodeList(gethBlockList)) shouldBe gethReceiptsRoot
    receiptsRootOf(receipts) shouldBe gethReceiptsRoot
  }

  it should "reject the three-item shape fukuii used to serve" in {
    val threeItem: RLPEncodeable = RLPList(RLPValue(Array[Byte](1)), RLPValue(Hex.decode("5208")), RLPList())
    a[RuntimeException] should be thrownBy threeItem.toEth69Receipt
  }

  it should "reject an EIP-2718 prefixed receipt" in {
    val prefixed: RLPEncodeable = RLPValue(Array[Byte](2) ++ rlp.encode(RLPList(RLPValue(Array[Byte](1)))))
    a[RuntimeException] should be thrownBy prefixed.toEth69Receipt
  }

  it should "reject an eth/68 receipt, whose second item is gas rather than a status" in {
    val eth68: RLPEncodeable =
      RLPList(RLPValue(Array[Byte](1)), RLPValue(Hex.decode("5208")), RLPValue(new Array[Byte](256)), RLPList())
    a[RuntimeException] should be thrownBy eth68.toEth69Receipt
  }

  it should "reject an unknown tx type" in {
    val unknownType: RLPEncodeable =
      RLPList(RLPValue(Array[Byte](5)), RLPValue(Array[Byte](1)), RLPValue(Hex.decode("5208")), RLPList())
    a[RuntimeException] should be thrownBy unknownType.toEth69Receipt
  }
