package com.chipprbots.ethereum.testing

import org.apache.pekko.util.ByteString

import scala.collection.mutable

import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.BatchUpdate

/** Simple in-memory test storage for EVM code
  *
  * Provides a minimal EvmCodeStorage implementation for unit tests. This implementation stores bytecode in memory
  * without any persistence.
  */
class TestEvmCodeStorage extends EvmCodeStorage {
  private val codes = mutable.Map[ByteString, ByteString]()

  override def get(key: ByteString): Option[ByteString] =
    codes.get(key)

  override def put(key: ByteString, value: ByteString): BatchUpdate = {
    codes(key) = value
    emptyBatchUpdate
  }

  override def remove(key: ByteString): BatchUpdate = {
    codes.remove(key)
    emptyBatchUpdate
  }

  override def emptyBatchUpdate: BatchUpdate = new BatchUpdate {
    private val updates = mutable.ArrayBuffer[() => Unit]()

    override def and(other: BatchUpdate): BatchUpdate = {
      updates += (() => other.commit())
      this
    }

    override def commit(): Unit = {
      updates.foreach(_.apply())
      updates.clear()
    }
  }
}
