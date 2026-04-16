package com.chipprbots.ethereum.testing

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.dataSource.EphemDataSource

/** Simple in-memory test storage for EVM code
  *
  * Provides a minimal EvmCodeStorage implementation for unit tests. This implementation stores bytecode in memory
  * without any persistence.
  */
class TestEvmCodeStorage extends EvmCodeStorage(EphemDataSource())
