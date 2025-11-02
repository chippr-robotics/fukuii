package com.chipprbots.ethereum

import scala.concurrent.duration._

object Timeouts {

  val shortTimeout: FiniteDuration = 500.millis
  val normalTimeout: FiniteDuration = 5.seconds
  val longTimeout: FiniteDuration = 25.seconds // Increased to accommodate 20s actor timeouts
  val veryLongTimeout: FiniteDuration = 30.seconds
  val miningTimeout: FiniteDuration = 20.minutes
}
