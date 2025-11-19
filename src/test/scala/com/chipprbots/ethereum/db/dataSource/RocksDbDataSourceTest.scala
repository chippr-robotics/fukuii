package com.chipprbots.ethereum.db.dataSource

import java.nio.file.Files

import org.scalatest.flatspec.AnyFlatSpec

import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.testing.Tags._

class RocksDbDataSourceTest extends AnyFlatSpec with DataSourceTestBehavior {

  private def createDataSource(path: String): RocksDbDataSource = {
    val dbPath: String = Files.createTempDirectory("temp-test-rocksdb").toAbsolutePath.toString

    RocksDbDataSource(
      new RocksDbConfig {
        override val createIfMissing: Boolean = true
        override val paranoidChecks: Boolean = true
        override val path: String = dbPath
        override val maxThreads: Int = 1
        override val maxOpenFiles: Int = 32
        override val verifyChecksums: Boolean = true
        override val levelCompaction: Boolean = true
        override val blockSize: Long = 16384
        override val blockCacheSize: Long = 33554432
      },
      Namespaces.nsSeq
    )
  }

  (it should behave).like(dataSource(createDataSource))
}
