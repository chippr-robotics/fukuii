package com.chipprbots.ethereum.consensus.pow.miners

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.consensus.pow.PoWMiningCoordinator.CoordinatorProtocol
import com.chipprbots.ethereum.domain.Block

trait MinerProtocol

object MinerProtocol {
  case object StartMining extends MinerProtocol
  case object StopMining extends MinerProtocol
  final case class ProcessMining(currentBestBlock: Block, replyTo: ActorRef[CoordinatorProtocol]) extends MinerProtocol

  sealed trait MiningResult {
    def triedHashes: Int
  }
  case class MiningSuccessful(triedHashes: Int, mixHash: ByteString, nonce: ByteString) extends MiningResult
  case class MiningUnsuccessful(triedHashes: Int) extends MiningResult
}
