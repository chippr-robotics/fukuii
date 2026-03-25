package com.chipprbots.ethereum.jsonrpc

import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters._
import scala.util.Try

import com.chipprbots.ethereum.jsonrpc.ExpiringMap.ValueWithDuration

object ExpiringMap {

  case class ValueWithDuration[V](value: V, expiration: Duration)

  def empty[K, V](defaultElementRetentionTime: Duration): ExpiringMap[K, V] =
    new ExpiringMap(new ConcurrentHashMap[K, ValueWithDuration[V]](), defaultElementRetentionTime)
}

/** Thread-safe wrapper around ConcurrentHashMap which enriches each element with expiration time (specified by user or
  * default). Map is passive which means it only checks for expiration and removes expired elements during get. Duration
  * in all calls is relative to current System.nanoTime().
  */
class ExpiringMap[K, V] private (
    val underlying: ConcurrentHashMap[K, ValueWithDuration[V]],
    val defaultRetentionTime: Duration
) {
  private val maxHoldDuration = ChronoUnit.CENTURIES.getDuration

  def addFor(k: K, v: V, duration: Duration): ExpiringMap[K, V] = {
    underlying.put(k, ValueWithDuration(v, Try(currentPlus(duration)).getOrElse(currentPlus(maxHoldDuration))))
    this
  }

  def add(k: K, v: V, duration: Duration): ExpiringMap[K, V] =
    addFor(k, v, duration)

  def addForever(k: K, v: V): ExpiringMap[K, V] =
    addFor(k, v, maxHoldDuration)

  def add(k: K, v: V): ExpiringMap[K, V] =
    addFor(k, v, defaultRetentionTime)

  def remove(k: K): ExpiringMap[K, V] = {
    underlying.remove(k)
    this
  }

  def get(k: K): Option[V] =
    Option(underlying.get(k)).flatMap(value =>
      if (isNotExpired(value))
        Some(value.value)
      else {
        underlying.remove(k, value) // Atomic remove only if value hasn't changed
        None
      }
    )

  private def isNotExpired(value: ValueWithDuration[V]) =
    currentNanoDuration().minus(value.expiration).isNegative

  private def currentPlus(duration: Duration) =
    currentNanoDuration().plus(duration)

  private def currentNanoDuration() =
    Duration.ofNanos(System.nanoTime())

}
