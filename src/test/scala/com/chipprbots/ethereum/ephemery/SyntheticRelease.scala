package com.chipprbots.ethereum.ephemery

import com.chipprbots.ethereum.ephemery.EphemeryTestInputs.*
import com.chipprbots.ethereum.ephemery.StrictJson.*

/** A valid release that follows a committed one by one reset interval (spec 028 R16): the timestamp and the reset are
  * shifted by one interval, the chain id and the iteration are one more, and the published hash is the one the library
  * computes. It tests the paths that need an iteration newer than the fixtures, without waiting for a real one.
  */
object SyntheticRelease:

  /** The inputs of the iteration after `base`. */
  def next(base: ReleaseInputs): ReleaseInputs =
    val genesis = parsed(base.genesisJson)
    val config = genesis.obj("config")
    val chainId = config.get("chainId") match
      case Some(n: JNum) => BigInt(n.raw)
      case other         => throw new AssertionError(s"chainId: $other")
    val timestamp = genesis.get("timestamp") match
      case Some(JStr(s)) => s.toLong
      case other         => throw new AssertionError(s"timestamp: $other")
    val iteration = (chainId - EphemeryRelease.IterationBase).toInt + 1
    val interval = EphemeryRelease.DefaultResetInterval
    val shifted = base
      .editGenesis(_.set("timestamp", JStr((timestamp + interval).toString)))
      .editConfig(_.set("chainId", num((chainId + 1).toLong)))
      .retention("GENESIS_TIMESTAMP", (timestamp + interval).toString)
      .retention("CHAIN_ID", (chainId + 1).toString)
      .retention("ITERATION_NUMBER", iteration.toString)
      .retention("ITERATION_RELEASE", s"ephemery-$iteration")
    // The published hash is what the library computes: the first run names it in the refusal.
    EphemeryRelease.validate(shifted) match
      case Left(ReleaseRefusal.HashMismatch(_, computed)) => shifted.copy(publishedHashText = computed)
      case other => throw new AssertionError(s"the synthetic release is not a hash mismatch first: $other")

  /** The entry the node's store holds for the release after `base`. */
  def nextEntry(base: ReleaseInputs, producedAt: java.time.Instant): ReleaseEntry =
    val valid = EphemeryRelease.validate(next(base)).fold(r => throw new AssertionError(r.message), identity)
    ReleaseEntry.of(valid, "https://example.invalid/synthetic", None, "test", producedAt)
