package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.ByteArrayOutputStream

import org.apache.pekko.util.ByteString

import com.typesafe.config.ConfigFactory
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.*
import com.chipprbots.ethereum.consensus.mining.GetBlockHeaderByHash
import com.chipprbots.ethereum.consensus.mining.GetNBlocksBack
import com.chipprbots.ethereum.consensus.mining.OmmerAncestor
import com.chipprbots.ethereum.consensus.pow.validators.OmmersValidator.OmmersValid
import com.chipprbots.ethereum.consensus.pow.validators.StdOmmersValidator
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValid
import com.chipprbots.ethereum.consensus.validators.BlockHeaderValidator
import com.chipprbots.ethereum.db.storage.AncestorWindow
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockBody.BlockBodyEnc
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.BlockchainConfig

/** The ancestor window on import (spec 018 T224, data-model E.1 "Window headers" and "Window bodies"). Every failure is
  * `BadFormat`, and comes before any write: the window is read and checked whole before the first node.
  */
class CheckpointV2AncestorWindowSpec extends AnyWordSpec with Matchers with EitherValues:
  import CheckpointV2Fixtures.*
  import CheckpointV2ImportKit.*

  private def refusedBeforeAnyWrite(bytes: Array[Byte], trust: Trust): Refusal =
    val target = new Chain
    val before = snapshot(target)
    val result = importInto(target, bytes, trust)
    withClue("nothing is written: ")(snapshot(target) shouldBe before)
    result.left.value match
      case BadFormat(reason) => reason
      case other             => fail(s"not BadFormat: $other")

  /** An archive of `c` over the empty state, with these window headers and bodies, written by the codec's writer. */
  private def archive(
      chainId: Long,
      genesisHash: ByteString,
      c: BlockHeader,
      window: Seq[BlockHeader],
      bodies: Seq[(ByteString, BlockBody)] = Nil
  ): Array[Byte] =
    val out = new ByteArrayOutputStream()
    val w = new CheckpointArchive.Writer(out)
    w.writeHeader(CheckpointArchive.Header(chainId, genesisHash, true, c, BigInt(1)))
    window.foreach(w.writeWindowHeader)
    bodies.foreach((hash, body) => w.writeWindowBody(hash, body))
    w.finish()
    out.toByteArray

  /** An ETC-shaped chain of blocks 0 to 8: ommers in blocks 2 (C-6, below the set), 5 (C-3) and 8 (C itself). */
  private object Etc:
    val ommers: Map[Int, BlockHeader] = Seq(2, 5, 8).map(n => n -> ommerAt(n)).toMap
    val bodies: Map[BigInt, BlockBody] = ommers.map((n, o) => BigInt(n) -> BlockBody(Nil, Seq(o)))
    val (chain, tip) =
      val c = new Chain
      val stored = c.store((0 to 8).map(n => powHeader(n, ommers.get(n).toSeq)), bodies)
      (c, stored.last)
    val bytes: Array[Byte] = chain.exportBytes(chain.exporter(61), tip.number.value).value
    val trust: Trust = trustOf(chain, 61, tip)
    val (header, entries) = decode(bytes)
    def headerAt(n: Int): BlockHeader = chain.reader.getBlockHeaderByNumber(n).get
    def withBodies(bodies: Seq[(ByteString, BlockBody)]): Array[Byte] =
      Raw.rebuild(
        header,
        entries.filterNot(_.isInstanceOf[CheckpointArchive.WindowBodyEntry]).flatMap {
          case h: CheckpointArchive.WindowHeaderEntry if h.header.number.value == 0 =>
            h +: bodies.map((hash, body) => CheckpointArchive.WindowBodyEntry(hash, body, body.toBytes))
          case e => Seq(e)
        }
      )

  "The importer" should {

    "refuse golden stream C, whose window does not chain, before any write" taggedAs UnitTest in {
      val (chain, tip) = streamAChain()
      refusedBeforeAnyWrite(CheckpointV2GoldenStreamSpec.golden("c"), trustOf(chain, 61, tip)) shouldBe a[WindowBroken]
    }

    "refuse window headers whose numbers are not consecutive, though they chain by hash" taggedAs UnitTest in {
      val h0 = powHeader(0)
      val h1 = powHeader(1).copy(number = BlockNumber(7), parentHash = h0.hash)
      val h2 = powHeader(2).copy(parentHash = h1.hash)
      val c = powHeader(3).copy(parentHash = h2.hash)
      val trust = Trust(61, h0.hash.value, h0.difficulty.value, None, Some(c.hash.value))
      refusedBeforeAnyWrite(archive(61L, h0.hash.value, c, Seq(h2, h1, h0)), trust) match
        case WindowBroken(detail) => detail should include("numbered 7, not 1")
        case other                => fail(s"not WindowBroken: $other")
    }

    "refuse a window below block 256 that does not end at the genesis" taggedAs UnitTest in {
      val (c, window) = CheckpointV2TestKit.withWindow(powHeader(3))
      val genesis = ByteString(Array.fill[Byte](32)(0x77))
      val trust = Trust(61, genesis, BigInt(131072), None, Some(c.hash.value))
      refusedBeforeAnyWrite(archive(61L, genesis, c, window), trust) match
        case WindowBroken(detail) => detail should include("not this chain's genesis")
        case other                => fail(s"not WindowBroken: $other")
    }

    "take a window of 256 headers above block 256 without reaching the genesis, and store it" taggedAs UnitTest in {
      val (c, window) = CheckpointV2TestKit.withWindow(powHeader(300))
      window.size shouldBe 256
      window.last.number.value shouldBe 44
      val genesis = ByteString(Array.fill[Byte](32)(0x77))
      val target = new Chain
      importInto(target, archive(61L, genesis, c, window), Trust(61, genesis, 1, None, Some(c.hash.value))).value
      window.foreach(h => target.reader.getBlockHeaderByHash(h.hash) shouldBe Some(h))
      AncestorWindow
        .check(c, proofOfWork = true, target.reader.getBlockHeaderByHash, target.reader.getBlockBodyByHash)
        .isEmpty shouldBe true
    }

    "take B-302's bodies on ETC (C..C-4 whose headers name ommers, C's own included), so C+1 with an ommer validates" taggedAs UnitTest in {
      Etc.entries.collect { case b: CheckpointArchive.WindowBodyEntry => b.blockHash } shouldBe
        Seq(Etc.tip.hash.value, Etc.headerAt(5).hash.value)
      val target = new Chain
      importInto(target, Etc.bytes, Etc.trust).value
      AncestorWindow
        .check(Etc.tip, proofOfWork = true, target.reader.getBlockHeaderByHash, target.reader.getBlockBodyByHash)
        .isEmpty shouldBe true

      // C+1 names a sibling of C as its ommer: the rules read C..C-5 and the bodies of C..C-4 that have ommers.
      val ommer = powHeader(8).copy(parentHash = Etc.tip.parentHash, extraData = ByteString("sibling".getBytes))
      val getNBlocksBack: GetNBlocksBack = (hash, n) =>
        Iterator
          .iterate(target.reader.getBlockHeaderByHash(BlockHash(hash)))(
            _.filter(_.number.value > 0).flatMap(h => target.reader.getBlockHeaderByHash(h.parentHash))
          )
          .take(n)
          .flatten
          .map(h => OmmerAncestor.of(h, target.reader.getBlockBodyByHash(h.hash)))
          .toList
      val getHeader: GetBlockHeaderByHash = hash => target.reader.getBlockHeaderByHash(BlockHash(hash))
      given BlockchainConfig = BlockchainConfig.fromRawConfig(ConfigFactory.load().getConfig("fukuii.blockchains.etc"))
      new StdOmmersValidator(AcceptingHeaders).validate(
        Etc.tip.hash.value,
        Etc.tip.number.value + 1,
        Seq(ommer),
        getHeader,
        getNBlocksBack
      ) shouldBe Right(OmmersValid)
    }

    "refuse a window body that is missing, extra, unpaired, mismatched, out of the set or out of order" taggedAs UnitTest in {
      val c = Etc.tip
      val b5 = Etc.headerAt(5)
      def bodyOf(h: BlockHeader): BlockBody = Etc.chain.reader.getBlockBodyByHash(h.hash).get
      withClue("missing (C-3's): ") {
        refusedBeforeAnyWrite(Etc.withBodies(Seq(c.hash.value -> bodyOf(c))), Etc.trust) match
          case WindowBodyRefused(detail) => detail should include("lacks")
          case other                     => fail(s"not WindowBodyRefused: $other")
      }
      withClue("extra, for a block whose header names no ommers: ") {
        val b7 = Etc.headerAt(7)
        refusedBeforeAnyWrite(
          Etc.withBodies(Seq(c.hash.value -> bodyOf(c), b7.hash.value -> BlockBody.empty, b5.hash.value -> bodyOf(b5))),
          Etc.trust
        ) shouldBe a[WindowBodyRefused]
      }
      withClue("for C-6, which has ommers but is below the set: ") {
        val b2 = Etc.headerAt(2)
        refusedBeforeAnyWrite(
          Etc.withBodies(Seq(c.hash.value -> bodyOf(c), b5.hash.value -> bodyOf(b5), b2.hash.value -> bodyOf(b2))),
          Etc.trust
        ) shouldBe a[WindowBodyRefused]
      }
      withClue("unpaired, by a hash that names no block of the window: ") {
        refusedBeforeAnyWrite(
          Etc.withBodies(Seq(ByteString(Array.fill[Byte](32)(9)) -> bodyOf(c), b5.hash.value -> bodyOf(b5))),
          Etc.trust
        ) shouldBe a[WindowBodyRefused]
      }
      withClue("mismatched, C-3's body under C's hash: ") {
        refusedBeforeAnyWrite(
          Etc.withBodies(Seq(c.hash.value -> bodyOf(b5), b5.hash.value -> bodyOf(b5))),
          Etc.trust
        ) match
          case WindowBodyRefused(detail) => detail should include("does not match its header")
          case other                     => fail(s"not WindowBodyRefused: $other")
      }
      withClue("lowest first, and repeated: ") {
        refusedBeforeAnyWrite(
          Etc.withBodies(Seq(b5.hash.value -> bodyOf(b5), c.hash.value -> bodyOf(c))),
          Etc.trust
        ) shouldBe
          a[WindowBodyRefused]
        refusedBeforeAnyWrite(
          Etc.withBodies(Seq(c.hash.value -> bodyOf(c), c.hash.value -> bodyOf(c))),
          Etc.trust
        ) shouldBe
          a[WindowBodyRefused]
      }
    }

    "refuse any window body on a proof-of-stake chain" taggedAs UnitTest in {
      val (chain, tip) = streamBChain()
      val (header, entries) = decode(CheckpointV2GoldenStreamSpec.golden("b"))
      val parent = chain.reader.getBlockHeaderByHash(tip.parentHash).get
      val withBody = Raw.archive(
        CheckpointArchive.headerBlockBytes(header),
        entries.collect { case h: CheckpointArchive.WindowHeaderEntry => Raw.of(h) } ++
          Seq(Raw.body(parent.hash.value, BlockBody.empty.toBytes)) ++
          entries.collect { case n: CheckpointArchive.NodeEntry => Raw.of(n) }
      )
      refusedBeforeAnyWrite(withBody, trustOf(chain, StreamBChainId, tip, ttd = Some(0))) shouldBe
        BodyOnProofOfStake(parent.hash.value)
    }

    "take proof of stake from the chain's TTD, not from the checkpoint's difficulty" taggedAs UnitTest in {
      // A chain with a TTD it has not reached at C (difficulty > 0, not merged at genesis), and an ommer in C-1.
      val source = new Chain
      val ommer = ommerAt(2)
      val stored = source.store((0 to 3).map(n => powHeader(n, if n == 2 then Seq(ommer) else Nil)), Map.empty)
      val (c, window) = (stored.last, stored.init.reverse)
      c.difficulty.value should be > BigInt(0)
      val genesis = stored.head
      val trust =
        Trust(11155111, genesis.hash.value, genesis.difficulty.value, Some(BigInt(10).pow(9)), Some(c.hash.value))
      refusedBeforeAnyWrite(
        archive(11155111L, genesis.hash.value, c, window, Seq(window.head.hash.value -> BlockBody(Nil, Seq(ommer)))),
        trust
      ) shouldBe BodyOnProofOfStake(window.head.hash.value)
      withClue("headers only: the window is whole without the body: ") {
        importInto(new Chain, archive(11155111L, genesis.hash.value, c, window), trust).value
      }
      withClue("the same archive on a chain with no TTD needs the body: ") {
        refusedBeforeAnyWrite(
          archive(11155111L, genesis.hash.value, c, window),
          trust.copy(terminalTotalDifficulty = None)
        ) shouldBe
          a[WindowBodyRefused]
      }
    }
  }

/** Ommer headers as valid, so that only the ancestor rules decide (the seal of a fixture header is not real). */
object AcceptingHeaders extends BlockHeaderValidator:
  def validate(blockHeader: BlockHeader, getBlockHeaderByHash: GetBlockHeaderByHash)(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockHeaderError, BlockHeaderValid] = Right(BlockHeaderValid)
  def validateHeaderOnly(blockHeader: BlockHeader)(implicit
      blockchainConfig: BlockchainConfig
  ): Either[BlockHeaderError, BlockHeaderValid] = Right(BlockHeaderValid)
