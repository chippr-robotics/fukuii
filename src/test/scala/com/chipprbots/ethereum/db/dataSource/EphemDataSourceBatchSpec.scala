package com.chipprbots.ethereum.db.dataSource

import org.scalatest.flatspec.AnyFlatSpec

/** The write-batch behaviour of [[DataSourceTestBehavior.batchUpdate]] on the in-memory data source the unit tests use.
  * Only that behaviour: the shared `dataSource` cases assert RocksDB-specific errors. An `EphemDataSource` has no
  * column families, so an unknown namespace is not an error and the failure half does not apply.
  */
class EphemDataSourceBatchSpec extends AnyFlatSpec with DataSourceTestBehavior:

  (it should behave).like(batchUpdate(_ => EphemDataSource(), rejectsUnknownNamespace = false))
