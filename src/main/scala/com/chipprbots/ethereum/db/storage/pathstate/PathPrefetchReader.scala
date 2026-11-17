package com.chipprbots.ethereum.db.storage.pathstate

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.db.storage.DecodedNodeCache
import com.chipprbots.ethereum.db.storage.PrefetchNodeReader

/** The access-list prefetch's reader of a parent state on the position-keyed store (spec 018 data-model 6.3, 9).
  *
  * It is [[com.chipprbots.ethereum.db.storage.PrefetchNodeReader]] over a read-only view, so it keeps that reader's
  * rules: safe from many threads, inserts into the decoded-node cache within the block's budget and only with
  * `putIfCurrent`, writes refused. Two things are particular to a view.
  *
  * It never feeds execution an unverified node. Every node it caches came back from [[PathMptStorage]], which returns a
  * row only when the row hashes to what its parent holds. The cache is keyed by hash, so a node that is right for this
  * view is right for the block that executes.
  *
  * It stops on a fault. A missing or corrupt row, or a view gone stale, ends the prefetch for every trie of the block:
  * the fault is thrown to the caller, which counts it, and the reader reports itself exhausted.
  *
  * It records nothing: a prefetch read is not a read of the block's execution (spec 017).
  */
object PathPrefetchReader:

  def apply(
      view: StateView,
      cache: Option[DecodedNodeCache],
      budgetBytes: Long,
      onLoaded: ByteString => Unit = _ => ()
  ): PrefetchNodeReader =
    PrefetchNodeReader.stoppingOnFault(PathMptStorage.readOnly(view), cache, budgetBytes, onLoaded)
