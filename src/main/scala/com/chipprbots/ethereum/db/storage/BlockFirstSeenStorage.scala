package com.chipprbots.ethereum.db.storage

import org.apache.pekko.util.ByteString

/** Storage for tracking when blocks were first seen by this node.
  * Used by MESS (Modified Exponential Subjective Scoring) to apply time-based
  * penalties to late-arriving blocks for protection against long-range attacks.
  */
trait BlockFirstSeenStorage {

  /** Records the timestamp when a block was first seen by this node.
    *
    * @param blockHash
    *   The hash of the block
    * @param timestamp
    *   Unix timestamp (milliseconds since epoch) when block was first observed
    */
  def put(blockHash: ByteString, timestamp: Long): Unit

  /** Retrieves the timestamp when a block was first seen.
    *
    * @param blockHash
    *   The hash of the block
    * @return
    *   Some(timestamp) if the block has been seen, None otherwise
    */
  def get(blockHash: ByteString): Option[Long]

  /** Removes the first-seen record for a block.
    * Used for cleanup of very old blocks to save storage space.
    *
    * @param blockHash
    *   The hash of the block
    */
  def remove(blockHash: ByteString): Unit

  /** Checks if a first-seen timestamp exists for a block.
    *
    * @param blockHash
    *   The hash of the block
    * @return
    *   true if timestamp exists, false otherwise
    */
  def contains(blockHash: ByteString): Boolean = get(blockHash).isDefined
}
