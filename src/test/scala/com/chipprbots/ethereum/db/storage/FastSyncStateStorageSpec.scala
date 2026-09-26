package com.chipprbots.ethereum.db.storage

import java.nio.charset.StandardCharsets

import scala.collection.immutable.ArraySeq

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.testing.Tags.*

/** [[FastSyncStateStorage]] only reports whether fast sync left its progress record behind. The key and namespace below
  * are the ones fast sync wrote before it was removed; the check must keep finding records written under them.
  */
class FastSyncStateStorageSpec extends AnyFlatSpec with Matchers:

  private val legacyKey = ArraySeq.unsafeWrapArray("fast-sync-state".getBytes(StandardCharsets.UTF_8))
  private val someRecord = ArraySeq[Byte](1, 2, 3)

  "FastSyncStateStorage" should "report no record on an empty database" taggedAs (UnitTest, DatabaseTest) in {
    new FastSyncStateStorage(EphemDataSource()).hasSyncState shouldBe false
  }

  it should "find a record fast sync wrote, without decoding it" taggedAs (UnitTest, DatabaseTest) in {
    val dataSource = EphemDataSource()
    dataSource.update(Seq(DataSourceUpdate(Namespaces.FastSyncStateNamespace, Nil, Seq(legacyKey -> someRecord))))

    new FastSyncStateStorage(dataSource).hasSyncState shouldBe true
  }

  it should "ignore the same key in another namespace" taggedAs (UnitTest, DatabaseTest) in {
    val dataSource = EphemDataSource()
    dataSource.update(Seq(DataSourceUpdate(Namespaces.AppStateNamespace, Nil, Seq(legacyKey -> someRecord))))

    new FastSyncStateStorage(dataSource).hasSyncState shouldBe false
  }
