package com.chipprbots.ethereum

import com.chipprbots.ethereum.utils.ForkBlockNumbers
import com.chipprbots.ethereum.utils.Logger

/** Logs fork milestone activations at node startup.
  *
  * Besu reference: ProtocolScheduleBuilder.java — `LOG.info("Protocol schedule created with milestones: {}", ...)`
  * Only activated forks (block number != Long.MaxValue) are included in the output.
  */
object MilestoneLog extends Logger {

  def logMilestones(forkBlockNumbers: ForkBlockNumbers): Unit =
    log.info("Protocol schedule milestones: {}", formatMilestones(forkBlockNumbers))

  private[ethereum] def formatMilestones(forkBlockNumbers: ForkBlockNumbers): String = {
    val active = namedMilestones(forkBlockNumbers).filter { case (_, block) => block != Long.MaxValue }
    if (active.isEmpty) "none configured"
    else active.map { case (name, block) => s"$name@$block" }.mkString(", ")
  }

  private def namedMilestones(f: ForkBlockNumbers): Seq[(String, BigInt)] = Seq(
    "Frontier"                  -> f.frontierBlockNumber,
    "Homestead"                 -> f.homesteadBlockNumber,
    "EIP-106"                   -> f.eip106BlockNumber,
    "EIP-150"                   -> f.eip150BlockNumber,
    "EIP-155"                   -> f.eip155BlockNumber,
    "EIP-160"                   -> f.eip160BlockNumber,
    "EIP-161"                   -> f.eip161BlockNumber,
    "DiffBomb-Pause"            -> f.difficultyBombPauseBlockNumber,
    "DiffBomb-Continue"         -> f.difficultyBombContinueBlockNumber,
    "DiffBomb-Removal"          -> f.difficultyBombRemovalBlockNumber,
    "Byzantium"                 -> f.byzantiumBlockNumber,
    "Constantinople"            -> f.constantinopleBlockNumber,
    "Istanbul"                  -> f.istanbulBlockNumber,
    "Atlantis"                  -> f.atlantisBlockNumber,
    "Agharta"                   -> f.aghartaBlockNumber,
    "Phoenix"                   -> f.phoenixBlockNumber,
    "Petersburg"                -> f.petersburgBlockNumber,
    "ECIP-1099"                 -> f.ecip1099BlockNumber,
    "Muir Glacier"              -> f.muirGlacierBlockNumber,
    "Magneto"                   -> f.magnetoBlockNumber,
    "Berlin"                    -> f.berlinBlockNumber,
    "Mystique"                  -> f.mystiqueBlockNumber,
    "Spiral"                    -> f.spiralBlockNumber,
    "Olympia"                   -> f.olympiaBlockNumber
  )
}
