package com.chipprbots.ethereum

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite

trait WithActorSystemShutDown extends BeforeAndAfterAll { this: Suite =>
  implicit val system: ActorSystem

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
}
