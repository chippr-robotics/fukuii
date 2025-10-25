package com.chipprbots.ethereum.db.components

import com.chipprbots.ethereum.db.dataSource.EphemDataSource

trait EphemDataSourceComponent extends DataSourceComponent {
  val dataSource: EphemDataSource = EphemDataSource()
}
