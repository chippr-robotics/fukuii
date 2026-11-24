package com.chipprbots.ethereum.consensus.pow

import org.apache.pekko.actor.typed.ActorRef

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.CoordinatorProtocol

/** Gives a test a built-in miner without starting one: a coordinator `ref` (a probe) stands in for the one PoWMining
  * spawns at start, so the RPC paths that need a miner (miner_start, miner_stop, B-279) can be exercised.
  */
object PoWMiningTestAccess:
  def attachCoordinator(mining: Mining, ref: ActorRef[CoordinatorProtocol]): Unit = mining match
    case pow: PoWMining => pow.minerCoordinatorRef = Some(ref)
    case other          => throw new IllegalArgumentException(s"not a PoWMining: $other")
