package com.chipprbots.ethereum.db.cache

import com.chipprbots.ethereum.common.SimpleMap

trait Cache[K, V] extends SimpleMap[K, V, Cache[K, V]] {
  def getValues: Seq[(K, V)]
  def clear(): Unit
  def shouldPersist: Boolean
}
