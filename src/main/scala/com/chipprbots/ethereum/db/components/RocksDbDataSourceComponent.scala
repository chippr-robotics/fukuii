package com.chipprbots.ethereum.db.components

import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.utils.Config

trait RocksDbDataSourceComponent extends DataSourceComponent {

  lazy val dataSource: RocksDbDataSource = RocksDbDataSource(Config.Db.RocksDb, Namespaces.nsSeq)

}
