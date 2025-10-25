package com.chipprbots.ethereum

import java.util.logging.LogManager

import org.rocksdb

import com.chipprbots.ethereum.nodebuilder.StdNode
import com.chipprbots.ethereum.nodebuilder.TestNode
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Logger

object Fukuii extends Logger {
  def main(args: Array[String]): Unit = {
    LogManager.getLogManager().reset(); // disable java.util.logging, ie. in legacy parts of jupnp

    val node =
      if (Config.testmode) {
        log.info("Starting Fukuii in test mode")
        deleteRocksDBFiles()
        new TestNode
      } else new StdNode

    log.info("Fukuii app {}", Config.clientVersion)
    log.info("Using network {}", Config.blockchains.network)

    node.start()
  }

  private def deleteRocksDBFiles(): Unit = {
    log.warn("Deleting previous database {}", Config.Db.RocksDb.path)
    rocksdb.RocksDB.destroyDB(Config.Db.RocksDb.path, new rocksdb.Options())
  }
}
