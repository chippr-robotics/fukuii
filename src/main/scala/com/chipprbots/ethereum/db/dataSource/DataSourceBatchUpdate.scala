package com.chipprbots.ethereum.db.dataSource

import scala.collection.immutable.ArraySeq

case class DataSourceBatchUpdate(dataSource: DataSource, updates: Array[DataUpdate] = Array.empty) {

  def and(that: DataSourceBatchUpdate): DataSourceBatchUpdate = {
    require(
      this.dataSource eq that.dataSource,
      "Transactional storage updates must be performed on the same data source"
    )
    DataSourceBatchUpdate(dataSource, this.updates ++ that.updates)
  }

  def commit(): Unit =
    dataSource.update(ArraySeq.unsafeWrapArray(updates))

  /** Fsync-backed commit: calls [[DataSource.updateSync]] to flush the OS write buffer to disk. Use only for critical
    * one-time writes where durability matters more than throughput.
    */
  def commitSync(): Unit =
    dataSource.updateSync(ArraySeq.unsafeWrapArray(updates))

}
