package com.chipprbots.ethereum.db.cache

import com.chipprbots.ethereum.db.storage.NodeStorage.NodeEncoded
import com.chipprbots.ethereum.db.storage.NodeStorage.NodeHash
import com.chipprbots.ethereum.utils.Config

trait AppCaches extends CacheComponent {
  val caches: Caches = new Caches {
    override val nodeCache: Cache[NodeHash, NodeEncoded] = MapCache.createCache(Config.NodeCacheConfig)
  }
}
