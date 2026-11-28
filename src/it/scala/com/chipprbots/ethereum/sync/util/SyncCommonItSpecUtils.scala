package com.chipprbots.ethereum.sync.util

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeoutException

import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

import com.chipprbots.ethereum.network.PeerManagerActor.FastSyncHostConfiguration

object SyncCommonItSpecUtils:
  def retryUntilWithDelay[A](source: IO[A], delay: FiniteDuration, maxRetries: Int)(
      predicate: A => Boolean
  ): IO[A] =
    source.delayBy(delay).flatMap { result =>
      if predicate(result) then IO.pure(result)
      else if maxRetries > 0 then retryUntilWithDelay(source, delay, maxRetries - 1)(predicate)
      else IO.raiseError(new TimeoutException("Task time out after all retries"))
    }

  case class HostConfig(
      maxBlocksHeadersPerMessage: Int,
      maxBlocksBodiesPerMessage: Int,
      maxReceiptsPerMessage: Int,
      maxMptComponentsPerMessage: Int
  ) extends FastSyncHostConfiguration

  object HostConfig:
    def apply(): HostConfig =
      val random: ThreadLocalRandom = ThreadLocalRandom.current()
      new HostConfig(
        maxBlocksHeadersPerMessage = random.nextInt(100, 201),
        maxBlocksBodiesPerMessage = random.nextInt(30, 51),
        maxReceiptsPerMessage = random.nextInt(30, 51),
        maxMptComponentsPerMessage = random.nextInt(100, 201)
      )

  /** @param networkId
    *   what the peer's STATUS says, and what it demands of the STATUS it receives (a peer on another one is dropped)
    * @param validateStateRoots
    *   whether the peer's block import checks a block's state root and gas used against its own execution of the block.
    *   The harness default accepts any root; see `ValidatorsExecutorChecksStateRoot`.
    */
  final case class FakePeerCustomConfig(
      hostConfig: HostConfig,
      networkId: Long = 1L,
      validateStateRoots: Boolean = false
  )

  object FakePeerCustomConfig:
    val defaultConfig: FakePeerCustomConfig = FakePeerCustomConfig(HostConfig(200, 200, 200, 200))
