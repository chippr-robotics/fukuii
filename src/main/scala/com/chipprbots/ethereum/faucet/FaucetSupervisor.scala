package com.chipprbots.ethereum.faucet

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.OneForOneStrategy
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.SupervisorStrategy
import org.apache.pekko.pattern.BackoffOpts
import org.apache.pekko.pattern.BackoffSupervisor

import cats.effect.unsafe.IORuntime

import scala.concurrent.duration._

import com.chipprbots.ethereum.faucet.FaucetHandler.WalletException
import com.chipprbots.ethereum.faucet.jsonrpc.WalletService
import com.chipprbots.ethereum.utils.Logger

object FaucetSupervisor {
  val name = "FaucetSupervisor"
}

class FaucetSupervisor(walletService: WalletService, config: FaucetConfig, shutdown: () => Unit)(using
    system: ActorSystem,
    runtime: IORuntime
) extends Logger {

  val childProps: Props = FaucetHandler.props(walletService, config)

  val minBackoff: FiniteDuration = config.supervisor.minBackoff
  val maxBackoff: FiniteDuration = config.supervisor.maxBackoff
  val randomFactor: Double = config.supervisor.randomFactor
  val autoReset: FiniteDuration = config.supervisor.autoReset

  val supervisorProps: Props = BackoffSupervisor.props(
    BackoffOpts
      .onFailure(
        childProps,
        childName = FaucetHandler.name,
        minBackoff = minBackoff,
        maxBackoff = maxBackoff,
        randomFactor = randomFactor
      )
      .withAutoReset(autoReset)
      .withSupervisorStrategy(OneForOneStrategy() {
        case error: WalletException =>
          log.error(s"Stop ${FaucetHandler.name}", error)
          shutdown()
          SupervisorStrategy.Stop
        case error =>
          log.error(s"Restart ${FaucetHandler.name}", error)
          SupervisorStrategy.Restart
      })
  )
  val supervisor: ActorRef = system.actorOf(supervisorProps, FaucetSupervisor.name)
}
