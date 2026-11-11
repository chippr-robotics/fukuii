package com.chipprbots.ethereum.db.storage.pathstate

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32C

import org.apache.pekko.util.ByteString

import scala.util.Random

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.storage.pathstate.BlockDiffCodec.Rejection
import com.chipprbots.ethereum.mpt.NibblePath
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, T066: what the changeset record's reader refuses, and what it does not look at.
  *
  * The reader checks structure only (data-model 4.2): a record it accepts is well formed, and re-encodes to the bytes
  * it was read from. Whether a node is large enough to have a row, and whether a prior differs from its next, are the
  * writer's rules, held by [[BlockDiff.validate]] and not by the codec.
  *
  * The records here are put together byte by byte, with their checksum made right, so that each case shows the one
  * defect it is about and not a checksum that no longer matches.
  */
class BlockDiffCodecSpec extends AnyFlatSpec with Matchers:

  private def fill(byte: Int, length: Int = 32): Array[Byte] = Array.fill[Byte](length)(byte.toByte)
  private val hash = fill(0x11)
  private val key: Array[Byte] = hash :+ 0x01.toByte

  private def bytes(values: Int*): Array[Byte] = values.map(_.toByte).toArray

  /** `body` with its CRC32C behind it. */
  private def withCrc(body: Array[Byte]): Array[Byte] =
    val crc = new CRC32C
    crc.update(body, 0, body.length)
    val value = crc.getValue
    body ++ bytes((value >>> 24).toInt, (value >>> 16).toInt, (value >>> 8).toInt, value.toInt)

  /** A record made by hand: its header, then `afterHeader` (the entry count and the entries), then the checksum. */
  private def record(
      afterHeader: Array[Byte],
      version: Int = 1,
      kind: Int = 1,
      flags: Int = 0,
      blockHash: Array[Byte] = hash
  ): Array[Byte] =
    val out = new ByteArrayOutputStream
    out.write(bytes(version, kind, flags))
    out.write(bytes(0, 0, 0, 0, 0, 0, 0, 9))
    out.write(blockHash)
    out.write(fill(0x22))
    out.write(fill(0x33))
    out.write(fill(0x44))
    out.write(afterHeader)
    withCrc(out.toByteArray)

  /** One entry of the account trie at path `[a]`: no prior, a three-byte next. */
  private val entryA = bytes(0x01, 0x01, 0x1a, 0x00, 0x01, 0x03, 0xc2, 0x01, 0x02)
  private val entryB = bytes(0x01, 0x01, 0x1b, 0x00, 0x01, 0x03, 0xc2, 0x01, 0x02)

  private def rejected(record: Array[Byte], recordKey: Array[Byte] = key): Rejection =
    BlockDiffCodec.decode(recordKey, record) match
      case Left(refused) => refused.reason
      case Right(diff)   => fail(s"accepted: $diff")

  "The record's reader" should "accept a well-formed record made by hand" taggedAs UnitTest in {
    val diff = BlockDiffCodec.decode(key, record(bytes(0x02) ++ entryA ++ entryB)).toOption.get
    diff.number shouldBe 9
    diff.changes.keySet shouldBe Set(PositionKey.at(None, NibblePath(0xa)), PositionKey.at(None, NibblePath(0xb)))
    diff.incomplete shouldBe false
  }

  it should "refuse a version it does not know, before anything else" taggedAs UnitTest in {
    rejected(record(bytes(0x00), version = 2)) shouldBe Rejection.UnknownVersion
    rejected(record(bytes(0x00), version = 0)) shouldBe Rejection.UnknownVersion
    // also when nothing after the version would make sense in this version's layout
    rejected(bytes(0x02, 0xff, 0xff)) shouldBe Rejection.UnknownVersion
  }

  it should "refuse a kind it does not know, and a kind or a block hash that is not its key's" taggedAs UnitTest in {
    rejected(record(bytes(0x00), kind = 2), hash :+ 0x02.toByte) shouldBe Rejection.UnknownKind
    rejected(record(bytes(0x00)), hash :+ 0x02.toByte) shouldBe Rejection.KeyMismatch
    rejected(record(bytes(0x00), blockHash = fill(0x12))) shouldBe Rejection.KeyMismatch
    rejected(record(bytes(0x00)), hash) shouldBe Rejection.KeyMismatch
  }

  it should "refuse a flag it does not know, the reserved one included" taggedAs UnitTest in {
    Seq(0x01, 0x03, 0x04, 0x80, 0xff).foreach { flags =>
      withClue(f"flags $flags%02x: ")(rejected(record(bytes(0x00), flags = flags)) shouldBe Rejection.UnknownFlags)
    }
    BlockDiffCodec.decode(key, record(bytes(0x00), flags = 0x02)).map(_.incomplete) shouldBe Right(true)
  }

  it should "refuse a tombstone that has entries" taggedAs UnitTest in {
    rejected(record(bytes(0x01) ++ entryA, flags = 0x02)) shouldBe Rejection.TombstoneWithEntries
  }

  it should "refuse a record whose checksum does not match, wherever the damage is" taggedAs UnitTest in {
    val good = record(bytes(0x01) ++ entryA)
    Seq(3, 20, 60, 139, 141, good.length - 5, good.length - 1).foreach { at =>
      val damaged = good.clone()
      damaged(at) = (damaged(at) ^ 0x01).toByte
      withClue(s"byte $at: ")(rejected(damaged) shouldBe Rejection.BadCrc)
    }
  }

  it should "refuse a record that ends early, and one with bytes after its last entry" taggedAs UnitTest in {
    rejected(Array.emptyByteArray) shouldBe Rejection.Truncated
    rejected(bytes(0x01, 0x01, 0x00)) shouldBe Rejection.Truncated
    // the count says two entries and there is one
    rejected(record(bytes(0x02) ++ entryA)) shouldBe Rejection.Truncated
    // an entry cut inside its next
    rejected(record(bytes(0x01) ++ entryA.dropRight(2))) shouldBe Rejection.Truncated
    // a length that reaches past the end
    rejected(record(bytes(0x01, 0x01, 0x01, 0x1a, 0x00, 0x01, 0x7f, 0xc2))) shouldBe Rejection.Truncated
    rejected(record(bytes(0x01) ++ entryA ++ bytes(0x00))) shouldBe Rejection.TrailingBytes
    rejected(record(bytes(0x00) ++ entryA)) shouldBe Rejection.TrailingBytes
  }

  it should "refuse a length written in more bytes than it needs, or in more than nine" taggedAs UnitTest in {
    // the count 1 as two bytes
    rejected(record(bytes(0x81, 0x00) ++ entryA)) shouldBe Rejection.BadVarint
    // the count 0 as two bytes
    rejected(record(bytes(0x80, 0x00))) shouldBe Rejection.BadVarint
    // a key length of 1 as two bytes
    rejected(
      record(bytes(0x01, 0x01, 0x81, 0x00, 0x1a, 0x00, 0x01, 0x03, 0xc2, 0x01, 0x02))
    ) shouldBe Rejection.BadVarint
    rejected(record(Array.fill[Byte](10)(0x80.toByte) ++ bytes(0x01))) shouldBe Rejection.BadVarint
  }

  it should "refuse a tag that names no trie, and a key that is no row key of its trie" taggedAs UnitTest in {
    rejected(record(bytes(0x01, 0x00, 0x01, 0x1a, 0x00, 0x01, 0x01, 0xc0))) shouldBe Rejection.UnknownTag
    rejected(record(bytes(0x01, 0x03, 0x01, 0x1a, 0x00, 0x01, 0x01, 0xc0))) shouldBe Rejection.UnknownTag
    // an account-trie key with no path at all
    rejected(record(bytes(0x01, 0x01, 0x00, 0x00, 0x01, 0x01, 0xc0))) shouldBe Rejection.BadKey
    // a storage-trie key that is only an owner
    rejected(record(bytes(0x01, 0x02, 0x20) ++ fill(0xaa) ++ bytes(0x00, 0x01, 0x01, 0xc0))) shouldBe Rejection.BadKey
    // paths that are not hex-prefix paths of a branch or an extension: a leaf's flag, and a padded odd nibble
    Seq(0x20, 0x3a, 0x05, 0xff).foreach { first =>
      withClue(f"a path beginning $first%02x: ") {
        rejected(record(bytes(0x01, 0x01, 0x01, first, 0x00, 0x01, 0x01, 0xc0))) shouldBe Rejection.BadKey
      }
    }
  }

  it should "refuse an entry with a presence byte that is neither 0 nor 1, and one with neither side" taggedAs
    UnitTest in {
      rejected(record(bytes(0x01, 0x01, 0x01, 0x1a, 0x02, 0x01, 0x01, 0xc0))) shouldBe Rejection.BadPresence
      rejected(record(bytes(0x01, 0x01, 0x01, 0x1a, 0x00, 0x02, 0x01, 0xc0))) shouldBe Rejection.BadPresence
      rejected(record(bytes(0x01, 0x01, 0x01, 0x1a, 0x00, 0x00))) shouldBe Rejection.BothAbsent
    }

  it should "refuse entries out of order, and a key that appears twice" taggedAs UnitTest in {
    rejected(record(bytes(0x02) ++ entryB ++ entryA)) shouldBe Rejection.Unsorted
    rejected(record(bytes(0x02) ++ entryA ++ entryA)) shouldBe Rejection.Duplicate
    // a storage-trie entry before an account-trie entry
    val inStorage = bytes(0x02, 0x21) ++ fill(0x01) ++ bytes(0x1a, 0x00, 0x01, 0x01, 0xc0)
    rejected(record(bytes(0x02) ++ inStorage ++ entryA)) shouldBe Rejection.Unsorted
    BlockDiffCodec.decode(key, record(bytes(0x02) ++ entryA ++ inStorage)).map(_.changes.size) shouldBe Right(2)
    // keys compare unsigned: 0x80.. sorts after 0x7f.., and a key sorts after its own prefix
    def at(path: Int*): Array[Byte] = bytes(0x01, path.length) ++ bytes(path*) ++ bytes(0x00, 0x01, 0x01, 0xc0)
    BlockDiffCodec.decode(key, record(bytes(0x02) ++ at(0x00, 0x7f) ++ at(0x00, 0x80))).isRight shouldBe true
    rejected(record(bytes(0x02) ++ at(0x00, 0x80) ++ at(0x00, 0x7f))) shouldBe Rejection.Unsorted
    BlockDiffCodec.decode(key, record(bytes(0x02) ++ at(0x00) ++ at(0x00, 0x01))).isRight shouldBe true
    rejected(record(bytes(0x02) ++ at(0x00, 0x01) ++ at(0x00))) shouldBe Rejection.Unsorted
  }

  it should "not look at the writer's rules: a node too small to be stored, a prior equal to its next" taggedAs
    UnitTest in {
      // a two-byte prior at a path that is not a root: no writer may produce it, and the reader takes it
      val small = BlockDiffCodec.decode(key, record(bytes(0x01, 0x01, 0x01, 0x1a, 0x01, 0x02, 0xc1, 0x80, 0x00)))
      small.isRight shouldBe true
      small.toOption.get.validate should contain("the account trie at [a]: the prior is not a stored node")

      val same = ByteString(fill(0xc5, 40))
      val noop = BlockDiff(
        ByteString(hash),
        9,
        ByteString(fill(0x22)),
        ByteString(fill(0x33)),
        ByteString(fill(0x44)),
        Map(PositionKey.at(None, NibblePath(0xa)) -> Change(Some(same), Some(same)))
      )
      BlockDiffCodec.decode(key, BlockDiffCodec.encode(noop)) shouldBe Right(noop)
      noop.validate should contain("the account trie at [a]: prior equals next")
    }

  "The record's writer" should "refuse a diff that has no structure to write" taggedAs UnitTest in {
    val good = BlockDiff(
      ByteString(hash),
      9,
      ByteString(fill(0x22)),
      ByteString(fill(0x33)),
      ByteString(fill(0x44)),
      Map(PositionKey.AccountRoot -> Change(None, Some(ByteString(0xc0))))
    )
    BlockDiffCodec.encode(good).length should be > 144
    an[IllegalArgumentException] should be thrownBy BlockDiffCodec.encode(good.copy(blockHash = ByteString(1, 2, 3)))
    an[IllegalArgumentException] should be thrownBy BlockDiffCodec.encode(good.copy(postStateRoot = ByteString.empty))
    an[IllegalArgumentException] should be thrownBy BlockDiffCodec.encode(good.copy(number = -1))
    an[IllegalArgumentException] should be thrownBy BlockDiffCodec.encode(good.copy(number = BigInt(1) << 64))
    an[IllegalArgumentException] should be thrownBy BlockDiffCodec.encode(good.copy(incomplete = true))
    an[IllegalArgumentException] should be thrownBy BlockDiffCodec.encode(
      good.copy(changes = Map(PositionKey.AccountRoot -> Change(None, None)))
    )
    // the largest number the record holds
    val last = good.copy(number = BlockDiff.MaxNumber)
    BlockDiffCodec.decode(key, BlockDiffCodec.encode(last)) shouldBe Right(last)
  }

  "Any diff a writer may produce" should "be read back as it was written, and written again to the same bytes" taggedAs
    UnitTest in {
      val random = new Random(18)
      def node(): ByteString = ByteString(Array.fill[Byte](32 + random.nextInt(500))(random.nextInt(256).toByte))
      def position(): PositionKey =
        val path = NibblePath.fromNibbles(Array.fill[Byte](random.nextInt(65))(random.nextInt(16).toByte))
        PositionKey.at(Option.when(random.nextBoolean())(ByteString(fill(random.nextInt(4)))), path)
      (1 to 200).foreach { round =>
        // The account trie's root changes with the state root: its two nodes are what the two roots are the hashes of.
        val (rootBefore, rootAfter) = (node(), node())
        val changes = Seq
          .fill(random.nextInt(60))(position())
          .distinct
          .filterNot(_ == PositionKey.AccountRoot)
          .map { p =>
            p -> (random.nextInt(3) match
              case 0 => Change(None, Some(node()))
              case 1 => Change(Some(node()), None)
              case _ => Change(Some(node()), Some(node()))
            )
          }
          .toMap + (PositionKey.AccountRoot -> Change(Some(rootBefore), Some(rootAfter)))
        val diff = BlockDiff(
          ByteString(fill(round)),
          BigInt(random.nextLong() & Long.MaxValue),
          ByteString(fill(round + 1)),
          ByteString(kec256(rootBefore.toArray)),
          ByteString(kec256(rootAfter.toArray)),
          changes
        )
        withClue(s"round $round: ") {
          diff.validate shouldBe empty
          val encoded = BlockDiffCodec.encode(diff)
          val decoded = BlockDiffCodec.decode(BlockDiffCodec.key(diff.blockHash), encoded)
          decoded shouldBe Right(diff)
          ByteString(BlockDiffCodec.encode(decoded.toOption.get)) shouldBe ByteString(encoded)
          diff.byHash.size shouldBe diff.changes.values.flatMap(_.next).toSet.size
        }
      }
    }

  private val big = ByteString(fill(0xc5, 32))
  private val small = ByteString(fill(0xc5, 31))
  private val below = PositionKey.at(None, NibblePath(1))
  private def rootHash(node: Option[ByteString]): ByteString =
    node.fold(BlockDiff.EmptyRoot)(n => ByteString(kec256(n.toArray)))

  /** A diff whose account-trie root goes from `before` to `after`, with the state roots that go with them. */
  private def moving(before: Option[ByteString], after: Option[ByteString])(others: (PositionKey, Change)*): BlockDiff =
    BlockDiff(
      ByteString(hash),
      9,
      ByteString(fill(0x22)),
      rootHash(before),
      rootHash(after),
      others.toMap + (PositionKey.AccountRoot -> Change(before, after))
    )

  private val oldRoot = Some(ByteString(fill(0xa0, 80)))
  private val newRoot = Some(ByteString(fill(0xb0, 80)))

  "A diff's own check" should "pass what a writer produces" taggedAs UnitTest in {
    moving(oldRoot, newRoot)().validate shouldBe empty
    moving(oldRoot, newRoot)(below -> Change(None, Some(big))).validate shouldBe empty
    // a root is stored at any size, in the account trie and in a storage trie
    moving(Some(small), Some(ByteString(0xc0)))().validate shouldBe empty
    moving(oldRoot, newRoot)(
      PositionKey.at(Some(ByteString(fill(7))), NibblePath.Empty) -> Change(None, Some(small))
    ).validate shouldBe empty
    // a state that was empty, and one that becomes empty: an empty trie has no root row
    moving(None, newRoot)().validate shouldBe empty
    moving(oldRoot, None)().validate shouldBe empty
    // a block that changes no stored node
    moving(oldRoot, newRoot)().copy(postStateRoot = rootHash(oldRoot), changes = Map.empty).validate shouldBe empty
    // a tombstone says nothing of the root
    moving(oldRoot, newRoot)().copy(changes = Map.empty, incomplete = true).validate shouldBe empty
  }

  it should "name a node too small to be stored, an entry that says nothing, and a tombstone with entries" taggedAs
    UnitTest in {
      moving(oldRoot, newRoot)(below -> Change(None, Some(small))).validate shouldBe
        Seq("the account trie at [1]: the next is not a stored node")
      moving(oldRoot, newRoot)(below -> Change(Some(small), None)).validate shouldBe
        Seq("the account trie at [1]: the prior is not a stored node")
      moving(oldRoot, newRoot)(below -> Change(Some(big), Some(big))).validate shouldBe
        Seq("the account trie at [1]: prior equals next")
      moving(oldRoot, newRoot)(below -> Change(None, None)).validate shouldBe
        Seq("the account trie at [1]: neither a prior nor a next")
      moving(oldRoot, newRoot)().copy(incomplete = true).validate shouldBe Seq("a tombstone has entries")
      moving(oldRoot, newRoot)().copy(parentHash = ByteString(1)).validate shouldBe
        Seq("a block hash or state root is not 32 bytes")
    }

  it should "hold the account trie's root to the two state roots" taggedAs UnitTest in {
    // the state root changes and nothing is said of the root row
    moving(oldRoot, newRoot)().copy(changes = Map(below -> Change(None, Some(big)))).validate shouldBe
      Seq("the state root changes and the account trie's root has no entry")
    // the root row is not the node either state root names: a diff built from another world of the block
    moving(oldRoot, newRoot)().copy(preStateRoot = ByteString(fill(0x33))).validate shouldBe
      Seq("the prior of the account trie's root is not the pre-state root")
    moving(oldRoot, newRoot)().copy(postStateRoot = ByteString(fill(0x44))).validate shouldBe
      Seq("the next of the account trie's root is not the post-state root")
    // a root entry where the state root does not change
    moving(oldRoot, newRoot)().copy(postStateRoot = rootHash(oldRoot)).validate should contain(
      "the state root does not change and the account trie's root has an entry"
    )
    // a prior claimed for a state that was empty
    moving(oldRoot, newRoot)().copy(preStateRoot = BlockDiff.EmptyRoot).validate shouldBe
      Seq("the prior of the account trie's root is not the pre-state root")
  }
