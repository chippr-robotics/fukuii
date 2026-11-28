package com.chipprbots.ethereum.ommers

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.annotation.tailrec

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockchainReader

object OmmersPool:

  sealed trait Command
  case class AddOmmers(ommers: List[BlockHeader]) extends Command

  object AddOmmers:
    def apply(b: BlockHeader*): AddOmmers = AddOmmers(b.toList)

  case class GetOmmers(parentBlockHash: ByteString, replyTo: ActorRef[Ommers]) extends Command

  case class Ommers(headers: Seq[BlockHeader])

  /** As is stated on section 11.1, eq. (143) of the YP
    *
    * @param ommerGenerationLimit
    *   should be === 6
    * @param returnedOmmersSizeLimit
    *   should be === 2
    *
    * Probably not worthy but those params could be placed in mining config.
    */
  def apply(
      blockchainReader: BlockchainReader,
      ommersPoolSize: Int,
      ommerGenerationLimit: Int = 6,
      returnedOmmersSizeLimit: Int = 2
  ): Behavior[Command] =
    running(blockchainReader, ommersPoolSize, ommerGenerationLimit, returnedOmmersSizeLimit, Nil)

  private def running(
      blockchainReader: BlockchainReader,
      ommersPoolSize: Int,
      ommerGenerationLimit: Int,
      returnedOmmersSizeLimit: Int,
      ommersPool: Seq[BlockHeader]
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match
        case AddOmmers(ommers) =>
          // Newest first, so the trim drops the oldest. De-duplicate (by hash) before trimming: trimmed first, a
          // header already in the pool took a second slot and pushed a real candidate out of a full pool (B-499).
          // A re-added header moves to the front, as the newest.
          val updated = (ommers ++ ommersPool).distinctBy(_.hash).take(ommersPoolSize)
          logStatus(context, event = "Ommers after add", ommers = updated)
          running(blockchainReader, ommersPoolSize, ommerGenerationLimit, returnedOmmersSizeLimit, updated)

        case GetOmmers(parentBlockHash, replyTo) =>
          val ancestors = collectAncestors(blockchainReader, parentBlockHash, ommerGenerationLimit)
          val ancestorHashes = ancestors.map(_.hash).toSet
          // An ommer of block N is a child of a block from N - 2 down to N - 7: of one of the parents of the ancestors
          // walked (YP 11.1, the rule StdOmmersValidator.validateOmmersAncestors applies). A child of the parent itself is
          // a sibling of block N, never its ommer. As core-geth's `commitUncle`: "uncle is sibling", and the uncle's
          // parent among the seven blocks from the parent down.
          val ommerParents = ancestors.map(_.parentHash).toSet
          // What the ancestors' bodies hold already: an ommer is included once (B-500). One handed out again failed the
          // ommers check of the block built with it. As core-geth's worker, whose `family` (`makeEnv`) holds the
          // ancestors and the uncles they included, and `commitUncle` refuses an uncle in it ("uncle already included").
          val included = ancestors.flatMap(includedBy(blockchainReader, _)).map(_.hash).toSet
          val ommers = ommersPool
            .filter { b =>
              val notAncestor = !ancestorHashes.contains(b.hash)
              ommerParents.contains(b.parentHash) && notAncestor && !included.contains(b.hash)
            }
            .take(returnedOmmersSizeLimit)
          logStatus(context, event = s"Ommers given parent block ${Hex.toHexString(parentBlockHash.toArray)}", ommers)
          replyTo ! OmmersPool.Ommers(ommers)
          Behaviors.same
    }

  private def collectAncestors(
      blockchainReader: BlockchainReader,
      parentHash: ByteString,
      generationLimit: Int
  ): List[BlockHeader] =
    @tailrec
    def rec(hash: BlockHash, limit: Int, acc: List[BlockHeader]): List[BlockHeader] =
      if limit > 0 then
        blockchainReader.getBlockHeaderByHash(hash) match
          case Some(bh) => rec(bh.parentHash, limit - 1, acc :+ bh)
          case None     => acc
      else acc
    rec(BlockHash(parentHash), generationLimit, List.empty)

  /** The ommers `ancestor` included. Its body is read only where its header names some (B-302). Where that body is not
    * stored, none is known: the block builder's ommers check reads the bodies it needs and takes no ommers without them
    * (B-283), so a header handed out here is never one an ancestor's unread body holds in a block this node builds.
    */
  private def includedBy(blockchainReader: BlockchainReader, ancestor: BlockHeader): Seq[BlockHeader] =
    if ancestor.ommersHash.value == BlockHeader.EmptyOmmers then Nil
    else blockchainReader.getBlockBodyByHash(ancestor.hash).map(_.uncleNodesList).getOrElse(Nil)

  private def logStatus(
      context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
      event: String,
      ommers: Seq[BlockHeader]
  ): Unit =
    lazy val ommersAsString: Seq[String] = ommers.map(bh => s"[number = ${bh.number}, hash = ${bh.hashAsHexString}]")
    context.log.debug(s"$event ${ommersAsString}")
