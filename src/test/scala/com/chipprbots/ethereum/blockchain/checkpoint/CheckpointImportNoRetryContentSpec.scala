package com.chipprbots.ethereum.blockchain.checkpoint

import java.io.IOException
import java.io.InputStream

import org.apache.pekko.util.ByteString

import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointImporter.*
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointUnifiedKit.*
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointV2ImportKit.importInto
import com.chipprbots.ethereum.blockchain.checkpoint.CheckpointV2ImportKit.trustOf
import com.chipprbots.ethereum.db.storage.pathstate.BlockRef
import com.chipprbots.ethereum.testing.Tags.UnitTest

/** B-384 (user decision, 2026-11-18, on spec 018 E.1 "no retry"): only a content fault of the archive is recorded as
  * refused. A cut-off stream, a failed checksum, a failed read and an expected block hash that is not the archive's
  * (the operator's config may be the wrong one) still clear the rows, but are not recorded, so a new download or a
  * corrected config is imported at the next start without the operator's flag.
  */
class CheckpointImportNoRetryContentSpec extends AnyWordSpec with Matchers with EitherValues:
  import CheckpointV2Fixtures.*

  private val (chainA, tipA) = streamAChain()
  private val bytesA: Array[Byte] = CheckpointV2GoldenStreamSpec.golden("a")
  private val trustA = trustOf(chainA, 61, tipA)
  private val genesisA = chainA.reader.genesisHeader
  private val tipRef = BlockRef(tipA.hash.value, tipA.number.value, tipA.stateRoot.value)
  private val idA: ByteString = CheckpointArchive.archiveIdOf(decode(bytesA)._1)

  private def fresh(): Datadir =
    val d = new Datadir(new Faulty, genesisA)
    d.commitGenesis()
    d.source.writes.clear()
    d

  // ---- archives that fail in each way -----------------------------------------------------------------------------

  private def badCrc: Array[Byte] =
    val bytes = bytesA.clone()
    bytes(bytes.length - 1) = (bytes(bytes.length - 1) ^ 0xff).toByte
    bytes

  /** Cut off among the codes or nodes (after rows were written), and inside the CRC trailer. */
  private def truncatedAmongEntries: Array[Byte] = bytesA.take(bytesA.length - 12)
  private def truncatedInTrailer: Array[Byte] = bytesA.take(bytesA.length - 2)

  /** The second node entry spoiled: a content fault found after the first node was written. */
  private def badNode: Array[Byte] =
    val (header, entries) = decode(bytesA)
    var nodes = 0
    CheckpointV2ImportKit.Raw.rebuild(
      header,
      entries.map {
        case CheckpointArchive.NodeEntry(b) =>
          nodes += 1
          CheckpointArchive.NodeEntry(if nodes == 2 then b.updated(b.length - 1, (b.last ^ 1).toByte) else b)
        case other => other
      }
    )

  /** A stream that fails to read, as a dropped connection or a bad disk does, after `after` bytes. */
  final private class FailingStream(bytes: Array[Byte], after: Int) extends InputStream:
    private var pos = 0
    override def read(): Int =
      if pos >= after then throw new IOException("read failed")
      val b = bytes(pos) & 0xff
      pos += 1
      b

  private val otherBlock = ByteString(Array.fill[Byte](32)(9))

  private def importerOn(d: Datadir, trust: Trust, in: InputStream) = d.importer(trust).importFromStream(in)

  /** The fault, on the unified layout: the datadir is clean, nothing is recorded, and a restart imports the good
    * archive with no flag.
    */
  private def retried(what: String)(attempt: Datadir => Either[ImportError, ImportResult]): Unit =
    withClue(s"$what: ") {
      val d = fresh()
      attempt(d).isLeft shouldBe true
      d.appState.getRefusedCheckpointArchives() shouldBe empty
      d.rows.isEmpty shouldBe true
      d.state.anchor.foreach(_ shouldBe d.genesisRef) // a refusal before any write leaves the genesis
      d.appState.getCheckpointImportInProgress() shouldBe None
      d.appState.isStateResetPending() shouldBe false
      val restarted = new Datadir(d.source, genesisA)
      CheckpointImporter.recoverInterrupted(restarted.appState, Some(restarted.target)) shouldBe false
      restarted.importBytes(bytesA, trustA).value.blockNumber shouldBe tipA.number.value
      restarted.state.anchor shouldBe Some(tipRef)
    }

  "A fault that is not the archive's content" should {

    "clear the rows, not be recorded, and be imported again at the next start (unified layout)" taggedAs UnitTest in {
      retried("truncated among the entries")(_.importBytes(truncatedAmongEntries, trustA))
      retried("truncated in the CRC trailer")(_.importBytes(truncatedInTrailer, trustA))
      retried("CRC failure")(_.importBytes(badCrc, trustA))
      retried("read error mid-nodes")(d => importerOn(d, trustA, new FailingStream(bytesA, bytesA.length - 40)))
      retried("read error at the trailer")(d => importerOn(d, trustA, new FailingStream(bytesA, bytesA.length - 2)))
      retried("expected block hash that is not the archive's")(
        _.importBytes(bytesA, trustA.copy(expectedBlockHash = Some(otherBlock)))
      )
    }

    "name the cause: a truncated stream, a CRC failure and a failed read" taggedAs UnitTest in {
      def refusal(r: Either[ImportError, ImportResult]): Refusal = r.left.value match
        case BadFormat(x) => x
        case other        => fail(s"not BadFormat: $other")
      refusal(fresh().importBytes(truncatedAmongEntries, trustA)) match
        case Decode(CheckpointArchive.Truncated(_)) => succeed
        case other                                  => fail(s"not Truncated: $other")
      refusal(fresh().importBytes(badCrc, trustA)) shouldBe Decode(CheckpointArchive.BadCrc)
      refusal(importerOn(fresh(), trustA, new FailingStream(bytesA, bytesA.length - 40))) shouldBe
        Decode(CheckpointArchive.ReadFailed("Nodes section: read failed"))
    }

    "leave the key nameless on the hash layout, so that the next start does not record it as a killed import" taggedAs UnitTest in {
      Seq(
        ("truncation", truncatedAmongEntries, trustA),
        ("CRC", badCrc, trustA)
      ).foreach { (what, bytes, trust) =>
        withClue(s"$what: ") {
          val target = new Chain
          val app = target.storages.storages.appStateStorage
          importInto(target, bytes, trust).left.value shouldBe a[BadFormat]
          app.getRefusedCheckpointArchives() shouldBe empty
          app.getCheckpointImportInProgress() shouldBe defined
          app.getCheckpointImportArchive() shouldBe None
          CheckpointImporter.recoverInterrupted(app, None) shouldBe true
          app.getRefusedCheckpointArchives() shouldBe empty
          // the same archive, whole, is imported with no flag
          importInto(target, bytesA, trustA).value.blockNumber shouldBe tipA.number.value
          app.isSnapSyncDone() shouldBe true
        }
      }
    }
  }

  "A content fault" should {

    /** Each is recorded by the archive's id, clears the rows, and is not imported again. */
    def recorded(what: String, bytes: Array[Byte], trust: Trust): Unit =
      withClue(s"$what: ") {
        val id = CheckpointArchive.archiveIdOf(decode(bytes)._1)
        val d = fresh()
        d.importBytes(bytes, trust).left.value shouldBe a[BadFormat]
        d.appState.getRefusedCheckpointArchives() shouldBe Seq(id)
        d.rows.isEmpty shouldBe true
        d.appState.getCheckpointImportInProgress() shouldBe None
        val restarted = new Datadir(d.source, genesisA)
        CheckpointImporter.recoverInterrupted(restarted.appState, Some(restarted.target)) shouldBe false
        restarted.importBytes(bytes, trust).left.value shouldBe AlreadyRefused(id)
      }

    "be recorded, and not retried (unified layout)" taggedAs UnitTest in {
      recorded("a node that is not the one its frame expects", badNode, trustA)
      recorded("another chain's id", bytesA, trustA.copy(chainId = 62))
      recorded("another genesis", bytesA, trustA.copy(genesisHash = ByteString(Array.fill[Byte](32)(7))))
      recorded("a window that does not chain", CheckpointV2GoldenStreamSpec.golden("c"), trustA)
    }

    "be recorded on the hash layout too" taggedAs UnitTest in {
      val target = new Chain
      val app = target.storages.storages.appStateStorage
      importInto(target, badNode, trustA).left.value shouldBe a[BadFormat]
      app.getRefusedCheckpointArchives() shouldBe Seq(idA)
      importInto(target, bytesA, trustA).left.value shouldBe AlreadyRefused(idA)
    }

    "be told from a fault of the transport by the refusal itself, for every refusal" taggedAs UnitTest in {
      val h = ByteString(Array.fill[Byte](32)(1))
      val content: Seq[Refusal] = Seq(
        Decode(CheckpointArchive.BadMagic),
        Decode(CheckpointArchive.UnsupportedVersion(1)),
        Decode(CheckpointArchive.Malformed("x")),
        WrongChainId(1, 2),
        WrongGenesis(h, h),
        NonCanonicalHeader(1),
        TotalDifficultyRefused(1, 1, 1, 1),
        WindowBroken("x"),
        WindowBodyRefused("x"),
        BodyOnProofOfStake(h),
        NodeHashMismatch("x", h, h),
        MalformedNode("x", "y"),
        AccountKeyLength("x", 1),
        Assertion(1, "x"),
        UnreferencedCode(h),
        MissingCode(1, Nil)
      )
      val mendable: Seq[Refusal] = Seq(
        Decode(CheckpointArchive.Truncated("x")),
        Decode(CheckpointArchive.BadCrc),
        Decode(CheckpointArchive.ReadFailed("x")),
        UnexpectedBlockHash(h, h)
      )
      content.foreach(r => withClue(s"$r: ")(r.archivesFault shouldBe true))
      mendable.foreach(r => withClue(s"$r: ")(r.archivesFault shouldBe false))
    }
  }
