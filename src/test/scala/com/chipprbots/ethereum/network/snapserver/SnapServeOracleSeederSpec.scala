package com.chipprbots.ethereum.network.snapserver

import java.io.File
import java.nio.file.Files

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.testing.Tags.*

/** B-212 (spec 018 T052): the datadirs `SnapServeOracleSeeder` writes are a state SNAP's limits bite on, held two ways.
  * The oracle run (`scripts/oracle/b212/run.sh`) hands them to the pinned v0.12.1's server, so a seeder that wrote a
  * state a response is never cut short on, or one the two layouts disagree about, would make the diff meaningless.
  */
class SnapServeOracleSeederSpec extends AnyFlatSpec with Matchers:

  private val zero = ByteString(new Array[Byte](32))
  private val max = ByteString(Array.fill[Byte](32)(0xff.toByte))

  /** The lines of `manifest.tsv` this spec holds the datadirs to; `SnapServeOracleRunner` (copied into the builds by
    * `run.sh`, so not visible here) reads the rest.
    */
  final private case class Account(hash: ByteString, slots: Int, storageRoot: String)
  final private case class Manifest(root: ByteString, accounts: Seq[Account], codes: Seq[ByteString]):
    val sorted: Seq[ByteString] = accounts.map(_.hash).sortBy(_.map("%02x".format(_)).mkString)

  private object Manifest:
    private def unhex(s: String): ByteString = ByteString(org.bouncycastle.util.encoders.Hex.decode(s))
    def read(file: File): Manifest =
      val src = scala.io.Source.fromFile(file, "UTF-8")
      val lines =
        try src.getLines().filter(l => l.nonEmpty && !l.startsWith("#")).map(_.split("\t", -1).toList).toList
        finally src.close()
      Manifest(
        lines.collectFirst { case "root" :: r :: _ => unhex(r) }.get,
        lines.collect { case "account" :: h :: n :: sr :: _ => Account(unhex(h), n.toInt, sr) },
        lines.collect { case "code" :: h :: _ => unhex(h) }
      )

  private def deleteRecursively(f: File): Unit =
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()

  private def open(dir: File): RocksDbDataSource =
    RocksDbDataSource(SnapServeOracleSeeder.config(dir), Namespaces.nsSeq)

  "The seeded datadirs" should "hold one state, whose ranges are cut short by a budget, as a hash store and as a position store" taggedAs
    UnitTest in {
      val out = Files.createTempDirectory("b212-seed").toFile
      try
        SnapServeOracleSeeder.seed(out)
        val manifest = Manifest.read(new File(out, "manifest.tsv"))
        manifest.accounts should have size SnapServeOracleSeeder.Accounts.toLong
        val hashDs = open(new File(out, "hash/db"))
        val positionDs = open(new File(out, "position/db"))
        try
          val onHashes: MptStorage = new SerializingMptStorage(new ArchiveNodeStorage(new NodeStorage(hashDs)))
          val appState = new AppStateStorage(positionDs)
          val anchor = appState.getStateAnchor().get
          anchor.stateRoot shouldBe manifest.root
          val onPositions: MptStorage =
            new PathStateStorage(new PathNodeStorage(positionDs), appState).readOnly(anchor)

          // every code is in both
          manifest.codes.size should be >= 39
          manifest.codes.foreach { h =>
            new EvmCodeStorage(hashDs).get(h).isDefined shouldBe true
            new EvmCodeStorage(positionDs).get(h).isDefined shouldBe true
          }

          // the account range: the whole state at a generous budget, and a cut at 8 KiB, the same from both
          def accounts(budget: BigInt, store: MptStorage) =
            SnapServer.serveAccountRange(1, manifest.root, zero, max, budget, store)
          accounts(BigInt(2 * 1024 * 1024), onHashes).accounts.map(_._1) shouldBe manifest.sorted
          accounts(BigInt(2 * 1024 * 1024), onPositions).accounts.map(_._1) shouldBe manifest.sorted
          val cut = accounts(BigInt(8192), onHashes)
          cut.accounts.size should (be > 10).and(be < SnapServeOracleSeeder.Accounts / 4)
          accounts(BigInt(8192), onPositions) shouldBe cut

          // the 1500-slot storage trie is held by two accounts, and takes several responses at 2 KiB
          val holders = manifest.accounts.filter(_.slots == 1500)
          holders should have size 2
          holders.map(_.storageRoot).distinct should have size 1
          def slots(store: MptStorage, from: ByteString): StorageRanges =
            SnapServer.serveStorageRanges(
              2,
              manifest.root,
              Seq(holders.head.hash),
              from,
              max,
              BigInt(2048),
              store,
              SnapServer.storageRootOf(manifest.root, store)
            )
          val first = slots(onPositions, zero)
          first shouldBe slots(onHashes, zero)
          first.slots.head.size should (be > 10).and(be < 500)
          first.proof should not be empty
          // and the two holders and the pair of 300 are each served from under their own account
          val pairs = manifest.accounts.filter(_.slots == 300)
          pairs should have size 2
          pairs.map(_.storageRoot).distinct should have size 1
        finally
          hashDs.close()
          positionDs.close()
      finally deleteRecursively(out)
    }
