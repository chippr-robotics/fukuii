package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.ReferenceCountNodeStorage.StoredNode
import com.chipprbots.ethereum.db.storage.encoding.*

/** Test-only door into basic pruning's stored format, for specs outside this package. */
object StoredNodeTestAccess:
  def wrapped(bytes: Array[Byte], references: Int): Array[Byte] =
    storedNodeToBytes(StoredNode(ByteString(bytes), references, 0))
  def references(stored: Array[Byte]): Int = storedNodeFromBytes(stored).references
