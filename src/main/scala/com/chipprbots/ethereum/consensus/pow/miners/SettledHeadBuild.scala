package com.chipprbots.ethereum.consensus.pow.miners

import cats.effect.IO

import com.chipprbots.ethereum.db.storage.pathstate.StateFault
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockchainReader

/** A block built on the head, kept only when the head was settled for the whole of the build (spec 018 C4e part 4,
  * B-293): the sequence lock of [[com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage.headGeneration]]. The
  * generation is read settled before the head is read, the block is built on that head, and the build is kept only if
  * the generation is still the one read. One overtaken by a move or an adoption in place is built again on the head
  * there is then, at most `maxBuilds` times.
  *
  * A build can also fail on a view of the state that went stale (a [[StateFault]]): an import that extends the head
  * moves the disk without a new generation. It is built again when the head is no longer the block it was built on, or
  * the generation moved; otherwise the fault is the build's own, and is raised (forge).
  *
  * With the unified layout off the generation is always 0 and the head is never stepped through: one build, the one
  * kept, as before. That it is no check of validity is the import's to say: a block sealed on a head that has moved is
  * a side block like any other, and is judged as one. What is saved is work.
  *
  * @param waitMillis
  *   how long a build waits for a move of the head to end; blocking, so it is waited on a blocking thread
  */
final class SettledHeadBuild(reader: BlockchainReader, waitMillis: Long, maxBuilds: Int)
    extends com.chipprbots.ethereum.utils.Logger:
  require(maxBuilds >= 1, "at least one build")

  /** The head built on and what `build` made of it; or why nothing was built. */
  def apply[A](build: Block => IO[A]): IO[Either[String, (Block, A)]] =
    def again(left: Int): IO[Either[String, (Block, A)]] =
      if left <= 1 then IO.pure(Left(s"the head was moved during each of $maxBuilds builds"))
      else attempt(left - 1)
    def attempt(left: Int): IO[Either[String, (Block, A)]] =
      IO.blocking(reader.awaitHeadSettled(waitMillis)).flatMap {
        case None => IO.pure(Left(s"the head is still being moved after $waitMillis ms"))
        case Some(settled) =>
          IO.defer(reader.getBestBlock match
            case None => IO.raiseError(new SettledHeadBuild.NoBestBlock)
            case Some(parent) =>
              def moved: Boolean =
                reader.headGeneration != settled || !reader.getBestBlock.exists(_.hash == parent.hash)
              build(parent).attempt.flatMap {
                case Right(built) if reader.headGeneration == settled => IO.pure(Right((parent, built)))
                case Right(_) =>
                  log.debug(
                    s"The block built on block ${parent.number} is not kept: the head was moved during the build"
                  )
                  again(left)
                case Left(_: StateFault) if moved =>
                  log.debug(s"The build on block ${parent.number} read a state the head has left: it is built again")
                  again(left)
                case Left(failed) => IO.raiseError(failed)
              }
          )
      }
    attempt(maxBuilds)

object SettledHeadBuild:

  /** The node has no best block to build on. */
  final class NoBestBlock extends IllegalStateException("No best block found")

  /** How many builds a miner makes before it gives a turn up: the head moved during each of them. */
  val Builds: Int = 3
