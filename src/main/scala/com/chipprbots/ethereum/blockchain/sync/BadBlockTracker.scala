package com.chipprbots.ethereum.blockchain.sync

import org.apache.pekko.util.ByteString

import com.github.blemale.scaffeine.Scaffeine

import scala.concurrent.duration._

/** Tracks known-bad block hashes to short-circuit validation on re-encounter.
  *
  * Modeled after core-geth's BadHashes map (10 entries). When a block fails validation,
  * its hash is recorded so future encounters from other peers are rejected immediately
  * without re-running full validation. Each entry also tracks the peer that first sent it
  * and the error reason.
  *
  * Thread-safe: backed by Caffeine concurrent cache.
  *
  * Reference: core-geth `headerchain.go:318` BadHashes map,
  *            go-ethereum `core/block_validator.go` bad block tracking
  */
class BadBlockTracker(maxEntries: Int = 128, ttl: FiniteDuration = 1.hour) {

  case class BadBlockEntry(
      hash: ByteString,
      number: BigInt,
      reason: String,
      firstPeerId: Option[String] = None
  )

  private val cache = Scaffeine()
    .maximumSize(maxEntries)
    .expireAfterWrite(ttl)
    .build[ByteString, BadBlockEntry]()

  /** Record a block as known-bad. */
  def markBad(hash: ByteString, number: BigInt, reason: String, peerId: Option[String] = None): Unit =
    cache.put(hash, BadBlockEntry(hash, number, reason, peerId))

  /** Check if a block hash is known-bad. Returns the entry if found. */
  def isBad(hash: ByteString): Option[BadBlockEntry] =
    cache.getIfPresent(hash)

  /** Number of tracked bad blocks. */
  def size: Int = cache.estimatedSize().toInt

  /** Remove a bad block entry (e.g., if it turns out to be valid on a different branch). */
  def remove(hash: ByteString): Unit =
    cache.invalidate(hash)

  /** Get all tracked bad block entries. */
  def entries: Seq[BadBlockEntry] =
    cache.asMap().values.toSeq
}
