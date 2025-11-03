package com.chipprbots.ethereum.sync.util

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeoutException

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.network.PeerManagerActor.FastSyncHostConfiguration

object SyncCommonItSpecUtils {
  def retryUntilWithDelay[A](source: IO[A], delay: FiniteDuration, maxRetries: Int)(
      predicate: A => Boolean
  ): IO[A] =
    source.delayBy(delay).flatMap { result =>
      if (predicate(result)) {
        IO.pure(result)
      } else {
        if (maxRetries > 0) {
          retryUntilWithDelay(source, delay, maxRetries - 1)(predicate)
        } else {
          IO.raiseError(new TimeoutException("Task time out after all retries"))
        }
      }
    }

  case class HostConfig(
      maxBlocksHeadersPerMessage: Int,
      maxBlocksBodiesPerMessage: Int,
      maxReceiptsPerMessage: Int,
      maxMptComponentsPerMessage: Int
  ) extends FastSyncHostConfiguration

  object HostConfig {
    def apply(): HostConfig = {
      val random: ThreadLocalRandom = ThreadLocalRandom.current()
      new HostConfig(
        maxBlocksHeadersPerMessage = random.nextInt(100, 201),
        maxBlocksBodiesPerMessage = random.nextInt(30, 51),
        maxReceiptsPerMessage = random.nextInt(30, 51),
        maxMptComponentsPerMessage = random.nextInt(100, 201)
      )
    }
  }

  final case class FakePeerCustomConfig(hostConfig: HostConfig)

  object FakePeerCustomConfig {
    val defaultConfig: FakePeerCustomConfig = FakePeerCustomConfig(HostConfig(200, 200, 200, 200))
  }
}
