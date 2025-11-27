package com.chipprbots.ethereum

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.testkit.TestKit

import scala.concurrent.duration._

import org.scalatest.BeforeAndAfterAll
import org.scalatest.Suite

trait WithActorSystemShutDown extends BeforeAndAfterAll { this: Suite =>
  implicit val system: ActorSystem

  override def afterAll(): Unit = {
    // Use a longer timeout and don't verify shutdown to avoid test failures
    // when actor systems take longer to shut down
    TestKit.shutdownActorSystem(system, duration = 15.seconds, verifySystemShutdown = false)
    super.afterAll()
  }
}
