package com.chipprbots.ethereum.faucet.jsonrpc

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.pattern.RetrySupport
import akka.util.Timeout

import cats.effect.IO

import com.chipprbots.ethereum.faucet.FaucetConfigBuilder
import com.chipprbots.ethereum.faucet.FaucetHandler
import com.chipprbots.ethereum.faucet.FaucetSupervisor

trait FaucetHandlerSelector {
  self: FaucetConfigBuilder with RetrySupport =>

  val handlerPath: String = s"user/${FaucetSupervisor.name}/${FaucetHandler.name}"
  lazy val attempts = faucetConfig.supervisor.attempts
  lazy val delay = faucetConfig.supervisor.delay

  lazy val handlerTimeout: Timeout = Timeout(faucetConfig.handlerTimeout)

  def selectFaucetHandler()(implicit system: ActorSystem): IO[ActorRef] =
    IO.fromFuture(
      IO(
        retry(() => system.actorSelection(handlerPath).resolveOne(handlerTimeout.duration), attempts, delay)(
          system.dispatcher,
          system.scheduler
        )
      )
    )

}
