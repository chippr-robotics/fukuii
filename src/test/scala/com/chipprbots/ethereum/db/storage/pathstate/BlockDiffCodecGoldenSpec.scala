package com.chipprbots.ethereum.db.storage.pathstate

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32C

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.mpt.NibblePath
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, T065: the changeset record, byte for byte (data-model 4.2, 4.3). '''The format is a one-way
  * door''': these bytes are what a datadir holds, and a change to them is a new layout and a re-sync.
  *
  * The three vectors are the files frozen in C0 (`baselines/blockdiff-golden-v1.hex` to `-v3.hex`, written by
  * `GoldenVectors.java`). Each is decoded and compared field by field with the construction of data-model 4.3, encoded
  * again to the same bytes, and its checksum derived here with `java.util.zip.CRC32C`. The further values (an empty
  * record, the three widths of a length, a record of 200 entries over both tries) were computed by a third
  * implementation, in Python, that shares no code with the codec or with the vectors' generator.
  */
class BlockDiffCodecGoldenSpec extends AnyFlatSpec with Matchers:

  private val baselines: Path = Path.of("specs/018-storage-engine-consolidation/baselines")

  private def fill(byte: Int, length: Int = 32): ByteString = ByteString(Array.fill[Byte](length)(byte.toByte))

  private def vector(n: Int): Array[Byte] =
    Hex.decode(Files.readString(baselines.resolve(s"blockdiff-golden-v$n.hex")).trim)

  private def crcOf(body: Array[Byte]): String =
    val crc = new CRC32C
    crc.update(body, 0, body.length)
    f"${crc.getValue}%08x"

  private def tail(record: Array[Byte]): String = Hex.toHexString(record.takeRight(4))

  // data-model 4.3
  private val one = BlockDiff(
    blockHash = fill(0x11),
    number = 1,
    parentHash = fill(0x22),
    preStateRoot = fill(0x33),
    postStateRoot = fill(0x44),
    changes = Map(PositionKey.AccountRoot -> Change(prior = None, next = Some(ByteString(0xc2, 0x01, 0x02))))
  )
  private val extension37 = ByteString(0xe4, 0x82, 0x00, 0xab, 0xa0) ++ fill(0xbb)
  private val two = BlockDiff(
    blockHash = fill(0x55),
    number = 2,
    parentHash = fill(0x11),
    preStateRoot = fill(0x44),
    postStateRoot = fill(0x66),
    changes = Map(PositionKey.at(Some(fill(0xaa)), NibblePath(0xa)) -> Change(prior = Some(extension37), next = None))
  )
  private val three = BlockDiff(
    blockHash = fill(0x77),
    number = 3,
    parentHash = fill(0x55),
    preStateRoot = fill(0x66),
    postStateRoot = fill(0x88),
    changes = Map.empty,
    incomplete = true
  )

  private val frozen: Seq[(Int, BlockDiff, Int, String)] =
    Seq((1, one, 153, "8d0afd3e"), (2, two, 219, "707894bf"), (3, three, 144, "ae4e831f"))

  "CRC32C" should "be the Castagnoli polynomial: its check value is e3069283" taggedAs UnitTest in {
    crcOf("123456789".getBytes("US-ASCII")) shouldBe "e3069283"
    f"${BlockDiffCodec.crc32c("123456789".getBytes("US-ASCII"), 0, 9)}%08x" shouldBe "e3069283"
  }

  frozen.foreach { case (n, diff, length, crc) =>
    s"Golden vector $n" should s"be $length bytes ending in $crc, decode to the record of data-model 4.3, and encode back" taggedAs
      UnitTest in {
        val bytes = vector(n)
        bytes.length shouldBe length
        tail(bytes) shouldBe crc
        crcOf(bytes.dropRight(4)) shouldBe crc

        BlockDiffCodec.decode(BlockDiffCodec.key(diff.blockHash), bytes) shouldBe Right(diff)
        Hex.toHexString(BlockDiffCodec.encode(diff)) shouldBe Hex.toHexString(bytes)
      }
  }

  "Golden vector 1" should "hold one entry of the account trie at the root: no prior, a next of three bytes" taggedAs
    UnitTest in {
      // version kind flags | number | hash parent pre post | count | tag klen key | prior | next
      Hex.toHexString(vector(1)) shouldBe
        "010100" + "0000000000000001" + "11" * 32 + "22" * 32 + "33" * 32 + "44" * 32 + "01" + "010100" + "00" +
        "0103c20102" + "8d0afd3e"
    }

  "Golden vector 2" should "hold a deletion in a storage trie: a 33-byte key, a 37-byte prior, no next" taggedAs
    UnitTest in {
      Hex.toHexString(vector(2)) shouldBe
        "010100" + "0000000000000002" + "55" * 32 + "11" * 32 + "44" * 32 + "66" * 32 + "01" + "0221" + "aa" * 32 +
        "1a" + "0125e48200aba0" + "bb" * 32 + "00" + "707894bf"
    }

  "Golden vector 3" should "be a tombstone: the incomplete flag, no entries" taggedAs UnitTest in {
    Hex.toHexString(vector(3)) shouldBe
      "010102" + "0000000000000003" + "77" * 32 + "55" * 32 + "66" * 32 + "88" * 32 + "00" + "ae4e831f"
  }

  "A record with no entries" should "be legal, and 144 bytes" taggedAs UnitTest in {
    val empty = BlockDiff(fill(0x99), 4, fill(0x77), fill(0x88), fill(0x88), Map.empty)
    val bytes = BlockDiffCodec.encode(empty)
    Hex.toHexString(bytes) shouldBe
      "010100" + "0000000000000004" + "99" * 32 + "77" * 32 + "88" * 32 + "88" * 32 + "00" + "fc82e26e"
    BlockDiffCodec.decode(BlockDiffCodec.key(empty.blockHash), bytes) shouldBe Right(empty)
  }

  "A length" should "be LEB128: one byte up to 127, two from 128, three from 16384" taggedAs UnitTest in {
    // (length of the next, the bytes of that length, the record's size, its CRC32C)
    Seq((127, "7f", 277, "81fde0ea"), (128, "8001", 279, "a3adc7b7"), (16384, "808001", 16536, "1642ddc6")).foreach {
      case (length, encoded, size, crc) =>
        val diff = BlockDiff(
          fill(0x12),
          5,
          fill(0x34),
          fill(0x56),
          fill(0x78),
          Map(PositionKey.AccountRoot -> Change(None, Some(fill(0xc5, length))))
        )
        val bytes = BlockDiffCodec.encode(diff)
        withClue(s"a next of $length bytes: ") {
          bytes.length shouldBe size
          tail(bytes) shouldBe crc
          // header 139, count 1, tag 1, key length 1, key 1, prior absent 1, next present 1: the length starts at 145
          Hex.toHexString(bytes.slice(145, 145 + encoded.length / 2)) shouldBe encoded
          BlockDiffCodec.decode(BlockDiffCodec.key(diff.blockHash), bytes) shouldBe Right(diff)
        }
    }
  }

  "A record of 200 entries over both tries" should "sort them by trie and then by key, bytes compared unsigned" taggedAs
    UnitTest in {
      val changes = (0 until 100).flatMap { i =>
        val account = PositionKey(None, ByteString(0x00, i)) ->
          Change(Option.when(i % 2 != 0)(fill(i)), Some(fill(i + 1, 33)))
        val storage = PositionKey(Some(fill(i % 7 + 1)), ByteString(0x10 | (i % 16))) ->
          Change(Some(fill(i, 40)), Option.when(i % 3 != 0)(fill(i + 2, 35)))
        Seq(account, storage)
      }.toMap
      changes.size shouldBe 200
      val diff = BlockDiff(fill(0xa1), 6, fill(0xa2), fill(0xa3), fill(0xa4), changes)
      val bytes = BlockDiffCodec.encode(diff)
      bytes.length shouldBe 15971
      tail(bytes) shouldBe "26157638"
      BlockDiffCodec.decode(BlockDiffCodec.key(diff.blockHash), bytes) shouldBe Right(diff)
    }

  "The key of a position's row" should "be the hex-prefix path, after the owner in a storage trie" taggedAs UnitTest in {
    def hp(nibbles: Int*): String = Hex.toHexString(PositionKey.at(None, NibblePath(nibbles*)).rowKey)
    hp() shouldBe "00"
    hp(0) shouldBe "10"
    hp(0xa) shouldBe "1a"
    hp(0xa, 0xb) shouldBe "00ab"
    hp(0xa, 0xb, 0xc) shouldBe "1abc"
    PositionKey.at(None, NibblePath(Seq.fill(63)(0xf)*)).rowKey.length shouldBe 32
    PositionKey.at(None, NibblePath(Seq.fill(64)(0xf)*)).rowKey.length shouldBe 33
    Hex.toHexString(PositionKey.at(Some(fill(0xaa)), NibblePath(0xa)).rowKey) shouldBe "aa" * 32 + "1a"
  }
