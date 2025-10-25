package com.chipprbots.ethereum.db.cache

import com.chipprbots.ethereum.db.storage.NodeStorage

trait CacheComponent {
  val caches: Caches

  trait Caches {
    val nodeCache: Cache[NodeStorage.NodeHash, NodeStorage.NodeEncoded]
  }
}
