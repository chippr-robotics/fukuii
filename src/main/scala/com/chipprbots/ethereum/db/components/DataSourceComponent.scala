package com.chipprbots.ethereum.db.components

import com.chipprbots.ethereum.db.dataSource.DataSource

trait DataSourceComponent {
  def dataSource: DataSource
}
