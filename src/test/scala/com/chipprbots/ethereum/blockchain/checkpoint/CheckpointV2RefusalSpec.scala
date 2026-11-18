package com.chipprbots.ethereum.blockchain.checkpoint

import org.apache.pekko.util.ByteString

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.*
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.BlockHeaderEnc
import com.chipprbots.ethereum.domain.BlockHeaderImplicits.toBlockHeader
import com.chipprbots.ethereum.rlp
import com.chipprbots.ethereum.rlp.RLPList
import com.chipprbots.ethereum.rlp.RLPValue
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.ByteUtils

/** The importer's refusals (spec 018 T223, data-model E.1). Each is `BadFormat` (a missing expected hash is the one
  * that is not: the archive is not at fault). A refusal of the header block or of the window comes before any write and
  * leaves the datadir as it was, byte for byte. A refusal among the nodes or the codes, or at the CRC, comes after the
  * first node went in: on the hash layout of PR2 nothing then says the import is done (no done flag, no best block),
  * and the import key stays, so the next start treats the datadir as an interrupted import. Clearing the rows such a
  * refusal leaves is PR3's (T225, T291).
  */
class CheckpointV2RefusalSpec extends AnyWordSpec with Matchers with EitherValues:
  import CheckpointV2Fixtures.*
  import CheckpointV2ImportKit.*

  private val (sourceA, tipA) = streamAChain()
  private val goldenA: Array[Byte] = CheckpointV2GoldenStreamSpec.golden("a")
  private val trustA: Trust = trustOf(sourceA, 61, tipA)
  private val (headerA, entriesA) = decode(goldenA)
  private val nodesA: Seq[Array[Byte]] = entriesA.collect { case n: CheckpointArchive.NodeEntry => n.rlp }
  private val emptyNode: Array[Byte] = rlp.encode(RLPValue(Array.emptyByteArray))

  /** The refusal, with the datadir unchanged. */
  private def refusedBeforeAnyWrite(bytes: Array[Byte], trust: Trust = trustA): Refusal =
    val target = new Chain
    val before = snapshot(target)
    val result = importInto(target, bytes, trust)
    withClue("nothing is written: ")(snapshot(target) shouldBe before)
    result.left.value match
      case BadFormat(reason) => reason
      case other             => fail(s"not BadFormat: $other")

  /** The refusal, with nothing saying the import is done, and the import key left. */
  private def refusedAfterWrites(bytes: Array[Byte], trust: Trust = trustA): Refusal =
    val target = new Chain
    val result = importInto(target, bytes, trust)
    val app = target.storages.storages.appStateStorage
    withClue("nothing says the import is done: ") {
      app.isSnapSyncDone() shouldBe false
      app.getBestBlockNumber() shouldBe 0
      app.getCheckpointImportInProgress() shouldBe defined
      target.reader.getChainWeightByHash(
        trust.expectedBlockHash.map(com.chipprbots.ethereum.domain.BlockHash(_)).get
      ) shouldBe None
    }
    result.left.value match
      case BadFormat(reason) => reason
      case other             => fail(s"not BadFormat: $other")

  private def imports(bytes: Array[Byte], trust: Trust): Chain =
    val target = new Chain
    importInto(target, bytes, trust).value
    target.storages.storages.appStateStorage.isSnapSyncDone() shouldBe true
    target

  /** Golden A with its entries replaced. */
  private def withEntries(f: Seq[CheckpointArchive.Entry] => Seq[CheckpointArchive.Entry]): Array[Byte] =
    Raw.rebuild(headerA, f(entriesA))

  /** Golden A with its node entries replaced. */
  private def withNodes(f: Seq[Array[Byte]] => Seq[Array[Byte]]): Array[Byte] =
    val before = entriesA.takeWhile(!_.isInstanceOf[CheckpointArchive.NodeEntry])
    val after = entriesA.dropWhile(!_.isInstanceOf[CheckpointArchive.CodeEntry])
    Raw.rebuild(headerA, before ++ f(nodesA).map(CheckpointArchive.NodeEntry(_)) ++ after)

  /** An RLP of `header` that is not `header.toBytes`: its number with a leading zero byte. It decodes to the same
    * header.
    */
  private def nonCanonical(header: com.chipprbots.ethereum.domain.BlockHeader): Array[Byte] =
    val RLPList(items*) = header.toRLPEncodable: @unchecked
    val RLPValue(number) = items(8): @unchecked
    val bytes = rlp.encode(RLPList(items.updated(8, RLPValue(0.toByte +: number))*))
    withClue("the encoding decodes to the same header: ")(bytes.toBlockHeader shouldBe header)
    bytes should not be header.toBytes
    bytes

  "The importer" should {

    "import golden A as it is (the base every refusal below changes)" taggedAs UnitTest in {
      imports(goldenA, trustA)
    }

    // ---- the header block ----------------------------------------------------------------------------------------

    "refuse a version 1 archive with a message naming the format, before any write" taggedAs UnitTest in {
      val v1 = goldenA.clone()
      v1(4) = 1
      val reason = refusedBeforeAnyWrite(v1)
      reason shouldBe Decode(CheckpointArchive.UnsupportedVersion(1))
      reason.describe should include("format version 1 is not supported")
    }

    "refuse a wrong chain id, a wrong genesis hash and a wrong expected block hash, before any write" taggedAs UnitTest in {
      refusedBeforeAnyWrite(goldenA, trustA.copy(chainId = 62)) shouldBe WrongChainId(62, 61)
      val otherGenesis = ByteString(Array.fill[Byte](32)(0x42))
      refusedBeforeAnyWrite(goldenA, trustA.copy(genesisHash = otherGenesis)) shouldBe
        WrongGenesis(otherGenesis, trustA.genesisHash)
      val otherBlock = ByteString(Array.fill[Byte](32)(0x24))
      refusedBeforeAnyWrite(goldenA, trustA.copy(expectedBlockHash = Some(otherBlock))) shouldBe
        UnexpectedBlockHash(otherBlock, tipA.hash.value)
    }

    "refuse an archive when no expected block hash is known, before any write, and not as the archive's fault" taggedAs UnitTest in {
      val target = new Chain
      val before = snapshot(target)
      importInto(target, goldenA, trustA.copy(expectedBlockHash = None)) shouldBe
        Left(NoExpectedBlockHash(tipA.number.value, tipA.hash.value))
      snapshot(target) shouldBe before
    }

    "refuse a TD with a leading zero byte and a TD-exact flag other than 0 or 1, before any write" taggedAs UnitTest in {
      val td = ByteUtils.bigIntToUnsignedByteArray(headerA.totalDifficulty)
      def block(flag: Int, tdBytes: Array[Byte]) =
        Raw.headerBlock(61L, headerA.genesisHash, flag, tipA.toBytes, tdBytes)
      val body = entriesA.filterNot(_ == CheckpointArchive.EndOfStream).map(Raw.of)
      refusedBeforeAnyWrite(Raw.archive(block(1, 0.toByte +: td), body)) shouldBe a[Decode]
      refusedBeforeAnyWrite(Raw.archive(block(2, td), body)) shouldBe a[Decode]
      withClue("the same fields, well formed, import: ")(imports(Raw.archive(block(1, td), body), trustA))
    }

    "refuse a checkpoint header whose RLP is not canonical, before any write" taggedAs UnitTest in {
      val td = ByteUtils.bigIntToUnsignedByteArray(headerA.totalDifficulty)
      val block = Raw.headerBlock(61L, headerA.genesisHash, 1, nonCanonical(tipA), td)
      val body = entriesA.filterNot(_ == CheckpointArchive.EndOfStream).map(Raw.of)
      refusedBeforeAnyWrite(Raw.archive(block, body)) shouldBe NonCanonicalHeader(tipA.number.value)
    }

    "refuse a window header whose RLP is not canonical, before any write" taggedAs UnitTest in {
      val bytes = withEntries(_.map {
        case CheckpointArchive.WindowHeaderEntry(h, _) if h.number.value == 1 =>
          CheckpointArchive.WindowHeaderEntry(h, nonCanonical(h))
        case e => e
      })
      refusedBeforeAnyWrite(bytes) shouldBe NonCanonicalHeader(1)
    }

    // ---- the order of the sections --------------------------------------------------------------------------------

    "refuse each tag out of section order, and an unknown tag" taggedAs UnitTest in {
      val headers = entriesA.collect { case e: CheckpointArchive.WindowHeaderEntry => e }
      val bodies = entriesA.collect { case e: CheckpointArchive.WindowBodyEntry => e }
      val nodes = entriesA.collect { case e: CheckpointArchive.NodeEntry => e }
      val codes = entriesA.collect { case e: CheckpointArchive.CodeEntry => e }
      withClue("a header after a body: ") {
        refusedBeforeAnyWrite(
          Raw.rebuild(headerA, headers.init ++ bodies ++ headers.takeRight(1) ++ nodes ++ codes)
        ) shouldBe
          a[Decode]
      }
      withClue("a body after a node: ") {
        // The window ends at the first node, without its body.
        refusedBeforeAnyWrite(
          Raw.rebuild(headerA, headers ++ nodes.take(1) ++ bodies ++ nodes.drop(1) ++ codes)
        ) shouldBe
          a[WindowBodyRefused]
      }
      withClue("a node after a code: ") {
        refusedAfterWrites(
          Raw.rebuild(headerA, headers ++ bodies ++ nodes.init ++ codes ++ nodes.takeRight(1))
        ) shouldBe
          an[Assertion]
      }
      withClue("an unknown tag: ") {
        val bytes = Raw.archive(
          CheckpointArchive.headerBlockBytes(headerA),
          (headers ++ bodies).map(Raw.of) ++ Seq(Raw.entry(0x05, Array[Byte](1))) ++ (nodes ++ codes).map(Raw.of)
        )
        refusedBeforeAnyWrite(bytes) shouldBe a[Decode]
      }
    }

    // ---- the entries ------------------------------------------------------------------------------------------------

    "refuse a corrupt node entry and a node that is not the one its frame expects" taggedAs UnitTest in {
      val corrupt = withNodes(ns => ns.updated(1, ns(1).updated(ns(1).length - 1, (ns(1).last ^ 1).toByte)))
      refusedAfterWrites(corrupt) shouldBe a[NodeHashMismatch]
      val swapped = withNodes(ns => ns.updated(1, ns(2)).updated(2, ns(1)))
      refusedAfterWrites(swapped) shouldBe a[NodeHashMismatch]
    }

    "refuse an entry longer than the reader's bound" taggedAs UnitTest in {
      val headersAndBodies = entriesA.takeWhile(!_.isInstanceOf[CheckpointArchive.NodeEntry]).map(Raw.of)
      val oversized = Array(0x01.toByte) ++ Raw.u32(CheckpointArchive.MaxEntryBytes + 1)
      val bytes =
        Raw.archive(CheckpointArchive.headerBlockBytes(headerA), headersAndBodies :+ Raw.node(nodesA.head) :+ oversized)
      refusedAfterWrites(bytes) shouldBe a[Decode]
    }

    "refuse a CRC32C that does not match, and an archive cut short" taggedAs UnitTest in {
      val badCrc = goldenA.clone()
      badCrc(badCrc.length - 1) = (badCrc.last ^ 0xff).toByte
      refusedAfterWrites(badCrc) shouldBe Decode(CheckpointArchive.BadCrc)
      refusedAfterWrites(goldenA.take(goldenA.length - 12)) shouldBe a[Decode]
      refusedAfterWrites(goldenA.take(goldenA.length - 2)) shouldBe Decode(CheckpointArchive.Truncated("crc trailer"))
    }

    "refuse codes out of order or repeated" taggedAs UnitTest in {
      val (c1, c2) = (Array[Byte](0x60, 0x01), Array[Byte](0x60, 0x02))
      val (root, nodes) = Nodes.twoAccounts(
        "a" -> Nodes.account(code = Some(c1)),
        "c" -> Nodes.account(code = Some(c2))
      )
      val sorted = CheckpointV2TestKit.sortedCodes(Seq(c1, c2))
      val (ok, trust) = crafted(root, nodes, sorted)
      imports(ok, trust)
      refusedAfterWrites(crafted(root, nodes, sorted.reverse)._1, trust) shouldBe a[Decode]
      refusedAfterWrites(crafted(root, nodes, sorted :+ sorted.last)._1, trust) shouldBe a[Decode]
    }

    "refuse an account leaf whose full key is not 64 nibbles" taggedAs UnitTest in {
      val leaf = Nodes.leaf(Array[Byte](3), Nodes.account())
      val (bytes, trust) = crafted(Nodes.hash(leaf), Seq(leaf))
      refusedAfterWrites(bytes, trust) shouldBe a[AccountKeyLength]
    }

    // ---- the five assertions of the stack machine --------------------------------------------------------------------

    "refuse an account leaf inside its parent (first assertion)" taggedAs UnitTest in {
      val k = Nodes.key("inline")
      val root = Nodes.branch(k(0).toInt -> Nodes.inline(Nodes.leaf(k.tail, Nodes.account())))
      val (bytes, trust) = crafted(Nodes.hash(root), Seq(root))
      refusedAfterWrites(bytes, trust) match
        case Assertion(n, _) => n shouldBe 1
        case other           => fail(s"not the first assertion: $other")
    }

    "refuse an inline node that holds a hash (second assertion)" taggedAs UnitTest in {
      val k = Nodes.key("hidden")
      val hidden = Nodes.leaf(k.drop(2), Nodes.account())
      val root = Nodes.branch(k(0).toInt -> Nodes.inline(Nodes.extension(Array(k(1)), Nodes.ref(hidden))))
      val (bytes, trust) = crafted(Nodes.hash(root), Seq(root, hidden))
      refusedAfterWrites(bytes, trust) match
        case Assertion(n, _) => n shouldBe 2
        case other           => fail(s"not the second assertion: $other")
    }

    "take a storage root under 32 bytes as a root entry, and refuse an archive that leaves it out (third assertion)" taggedAs UnitTest in {
      val tiny = Nodes.leaf(Array[Byte](5), Array[Byte](1))
      tiny.length should be < 32
      val account = Nodes.leaf(Nodes.key("tiny"), Nodes.account(storageRoot = Nodes.hash(tiny)))
      val (whole, trust) = crafted(Nodes.hash(account), Seq(account, tiny))
      val target = imports(whole, trust)
      target.storages.storages.nodeStorage.get(Nodes.hash(tiny)).map(_.toSeq) shouldBe Some(tiny.toSeq)
      refusedAfterWrites(crafted(Nodes.hash(account), Seq(account))._1, trust) match
        case Assertion(n, _) => n shouldBe 5
        case other           => fail(s"not the fifth assertion: $other")
    }

    "push no frame for the empty storage root (fourth assertion)" taggedAs UnitTest in {
      val account = Nodes.leaf(Nodes.key("empty"), Nodes.account())
      val (alone, trust) = crafted(Nodes.hash(account), Seq(account))
      imports(alone, trust)
      withClue("the empty trie's node, which hashes to the empty storage root, is owed by no frame: ") {
        refusedAfterWrites(crafted(Nodes.hash(account), Seq(account, emptyNode))._1, trust) match
          case Assertion(n, _) => n shouldBe 5
          case other           => fail(s"not the fifth assertion: $other")
      }
    }

    "refuse a state cut after a whole subtree, and nodes left over (fifth assertion)" taggedAs UnitTest in {
      withClue("the last node left out, with the archive whole around it: ") {
        refusedAfterWrites(withNodes(_.init)) match
          case Assertion(n, detail) => n shouldBe 5; detail should include("still owed")
          case other                => fail(s"not the fifth assertion: $other")
      }
      withClue("a node after the last one owed: ") {
        refusedAfterWrites(withNodes(ns => ns :+ ns.last)) match
          case Assertion(n, detail) => n shouldBe 5; detail should include("no node owed")
          case other                => fail(s"not the fifth assertion: $other")
      }
      withClue("the nodes cut short and no codes (the end tag follows the nodes): ") {
        val window = entriesA.takeWhile(!_.isInstanceOf[CheckpointArchive.NodeEntry]).map(Raw.of)
        refusedAfterWrites(
          Raw.archive(CheckpointArchive.headerBlockBytes(headerA), window ++ nodesA.init.map(Raw.node))
        ) shouldBe an[Assertion]
      }
    }

    "push no initial frame for the empty state root" taggedAs UnitTest in {
      val (empty, trust) = crafted(EmptyTrieRoot, Nil)
      imports(empty, trust)
      withClue("a node, even the empty trie's own: ") {
        refusedAfterWrites(crafted(EmptyTrieRoot, Seq(emptyNode))._1, trust) match
          case Assertion(n, _) => n shouldBe 5
          case other           => fail(s"not the fifth assertion: $other")
      }
    }
  }
