package com.chipprbots.ethereum.blockchain.sync

import java.io.File
import java.nio.file.Files

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.ArchiveNodeStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.SerializingMptStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit.Lacking
import com.chipprbots.ethereum.testing.Tags.*

/** B-375 (spec 018 T214): the datadirs `RecoveryScanOracleSeeder` writes are the stores `RecoveryScansVerdictSpec`
  * scans. The oracle run (scripts/oracle/b375/run.sh) hands them to the pinned v0.12.1's scans, so a seeder that wrote
  * the wrong gap would make the diff meaningless: this holds the seeder to the in-memory fixture.
  */
class RecoveryScanOracleSeederSpec extends AnyFlatSpec with Matchers:

  private def deleteRecursively(f: File): Unit =
    Option(f.listFiles()).foreach(_.foreach(deleteRecursively))
    f.delete()
    ()

  "A seeded datadir" should "hold every node and code of the state but the injected gaps, and be scanned as the in-memory store with the gaps is" taggedAs
    UnitTest in {
      val out = Files.createTempDirectory("b375-seed").toFile
      try
        val cases = RecoveryScanOracleSeeder.seed(out)
        cases.map(_.name).distinct should have size cases.size.toLong
        new File(out, "manifest.tsv").exists shouldBe true
        val fx = new RecoveryScanOracleSeeder.Fixture
        for c <- cases do
          withClue(s"${c.name}: ") {
            RecoveryScanOracleSeeder.withDb(new File(new File(out, c.name), "db")) { ds =>
              val nodeStore = new NodeStorage(ds)
              val code = new EvmCodeStorage(ds)
              fx.nodes.foreach { (hash, rlp) =>
                val held = nodeStore.get(hash).map(ByteString(_))
                if c.lostNodes(hash) then held shouldBe None
                else if c.replaced.contains(hash) then held should not be Some(rlp)
                else held shouldBe Some(rlp)
              }
              Seq(fx.codeA, fx.codeB).foreach { b =>
                code.get(fx.hashOfCode(b)).isDefined shouldBe !c.lostCodes(fx.hashOfCode(b))
              }
              if c.replaced.isEmpty then
                val onDisk: MptStorage = new SerializingMptStorage(new ArchiveNodeStorage(nodeStore))
                val inMemory: MptStorage = new Lacking(fx.hashed.nodes, c.lostNodes)
                // a node absent from the datadir is a MissingRootNodeException, from the in-memory one a
                // MissingNodeException: both are "threw", which is all this holds them to
                def found(store: MptStorage, codes: EvmCodeStorage): Either[Unit, (Set[ByteString], Set[ByteString])] =
                  RecoveryScannersPositionSpec
                    .thrown {
                      val scan = new CombinedRecoveryScan(store, codes)
                      scan.scanFrom(c.root)
                      (scan.missingBytecodes.toSet, scan.missingStorageTries.map(_._2).toSet)
                    }
                    .left
                    .map(_ => ())
                val lacking = new EvmCodeStorage(com.chipprbots.ethereum.db.dataSource.EphemDataSource())
                Seq(fx.codeA, fx.codeB).filterNot(b => c.lostCodes(fx.hashOfCode(b))).foreach { b =>
                  lacking.put(fx.hashOfCode(b), b).commit()
                }
                found(onDisk, code) shouldBe found(inMemory, lacking)
            }
          }
      finally deleteRecursively(out)
    }
