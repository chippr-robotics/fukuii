package com.chipprbots.ethereum.db.storage

import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.encoding.*

/** Test-only view of the reference counts a reference-counted node storage holds. */
object RefCountInspector:
  /** The reference count of every stored trie node (the node-namespace entries keyed by a 32-byte hash). */
  def refCounts(dataSource: EphemDataSource): Seq[Int] =
    dataSource.storage.collect {
      case (k, v)
          if k.array().length == 32 + Namespaces.NodeNamespace.length &&
            k.array().take(Namespaces.NodeNamespace.length).toSeq == Namespaces.NodeNamespace.toSeq =>
        storedNodeFromBytes(v).references
    }.toSeq
