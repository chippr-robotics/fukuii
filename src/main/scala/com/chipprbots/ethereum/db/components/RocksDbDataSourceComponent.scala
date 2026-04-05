package com.chipprbots.ethereum.db.components

import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.utils.InstanceConfigProvider

trait RocksDbDataSourceComponent extends DataSourceComponent {
  self: InstanceConfigProvider =>

  override lazy val dataSource: RocksDbDataSource = RocksDbDataSource(instanceConfig.Db.RocksDb, Namespaces.nsSeq)

}
