package com.chipprbots.ethereum.utils

import com.typesafe.config.ConfigFactory

/** Sizes of the in-process read caches on the block-import path (`state-read-caches` in `base/state-read-caches.conf`).
  *
  * Read straight from the typesafe config rather than through [[Config]], so the EVM and storage classes that hold
  * these caches as process-wide singletons do not drag the whole node configuration into a unit test. A missing key
  * falls back to the default below, which is also the value shipped in the conf file.
  */
object StateReadCacheConfig:

  private lazy val section: Option[com.typesafe.config.Config] =
    val root = ConfigFactory.load()
    if root.hasPath("fukuii.state-read-caches") then Some(root.getConfig("fukuii.state-read-caches")) else None

  private def bytes(path: String, default: Long): Long =
    section.filter(_.hasPath(path)).map(_.getLong(path)).getOrElse(default)

  /** Budget of each `EvmCodeStorage`'s execution-side code cache; 0 disables it. */
  lazy val codeCacheBytes: Long = bytes("code-cache-bytes", 64L * 1024 * 1024)

  /** Budget of a state storage's decoded-node cache; 0 disables it. */
  lazy val decodedNodeCacheBytes: Long = bytes("decoded-node-cache-bytes", 96L * 1024 * 1024)

  /** Entries one world's base-trie read memos may hold in total; 0 disables them. */
  lazy val worldReadMemoEntries: Int = bytes("world-read-memo-entries", 250000L).toInt

  /** Budget of the JUMPDEST analysis cache; 0 disables it. */
  lazy val jumpDestCacheBytes: Long = bytes("jumpdest-cache-bytes", 32L * 1024 * 1024)
