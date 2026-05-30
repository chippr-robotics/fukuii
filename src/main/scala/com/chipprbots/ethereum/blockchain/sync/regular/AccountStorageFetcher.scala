package com.chipprbots.ethereum.blockchain.sync.regular

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.AbstractBehavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.{ActorRef => ClassicActorRef}
import org.apache.pekko.util.ByteString

import cats.effect.unsafe.IORuntime

import scala.util.Failure
import scala.util.Success

import org.slf4j.Logger

import com.chipprbots.ethereum.blockchain.sync.PeersClient.BestSnapPeer
import com.chipprbots.ethereum.blockchain.sync.PeersClient.Request
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.Message
import com.chipprbots.ethereum.network.p2p.messages.SNAP.AccountRange
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetStorageRanges.GetStorageRangesEnc
import com.chipprbots.ethereum.network.p2p.messages.SNAP.StorageRanges
import com.chipprbots.ethereum.network.p2p.messages.ETH66
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Config.SyncConfig

object AccountStorageFetcher {

  sealed trait Command
  private[AccountStorageFetcher] case class AdaptedMessage[T <: Message](peer: Peer, message: T) extends Command
  private[AccountStorageFetcher] case object Retry extends Command

  def apply(
      accountAddress: ByteString,
      replyTo: ClassicActorRef,
      canonicalStateRoot: Option[ByteString],
      peersClient: ClassicActorRef,
      stateStorage: StateStorage,
      syncConfig: SyncConfig
  ): Behavior[Command] =
    Behaviors.setup { context =>
      new AccountStorageFetcher(
        accountAddress,
        replyTo,
        canonicalStateRoot,
        peersClient,
        stateStorage,
        syncConfig,
        context
      ).sendGetAccountRange()
    }
}

/** Lightweight single-account storage re-download actor. Reference-client aligned: runs GetAccountRange +
  * GetStorageRanges at a canonical pivot, writes storage proof trie nodes to RocksDB, and sends FetchedAccountStorage
  * back to BlockImporter. BlockImporter then updates the MPT account leaf and retries the block.
  */
private class AccountStorageFetcher(
    val accountAddress: ByteString,
    val replyTo: ClassicActorRef,
    val canonicalStateRoot: Option[ByteString],
    val peersClient: ClassicActorRef,
    val stateStorage: StateStorage,
    val syncConfig: SyncConfig,
    context: ActorContext[AccountStorageFetcher.Command]
) extends AbstractBehavior[AccountStorageFetcher.Command](context)
    with FetchRequest[AccountStorageFetcher.Command] {

  import AccountStorageFetcher._

  implicit val runtime: IORuntime = IORuntime.global
  override val log: Logger = context.log

  override def makeAdaptedMessage[T <: Message](peer: Peer, msg: T): Command = AdaptedMessage(peer, msg)

  private val accountHash: ByteString = com.chipprbots.ethereum.crypto.kec256(accountAddress)
  private val minHash: ByteString = ByteString(Array.fill(32)(0x00.toByte))
  private val maxHash: ByteString = ByteString(Array.fill(32)(0xff.toByte))

  def sendGetAccountRange(): Behavior[Command] = {
    val root = canonicalStateRoot.getOrElse(ByteString.empty)
    if (root.isEmpty) {
      log.warn(
        "[STATE-HEAL] No canonical state root available for account {} — failing",
        ByteStringUtils.hash2string(accountAddress)
      )
      replyTo ! BlockFetcher.FetchedAccountStorage(accountAddress, None, success = false)
      return Behaviors.stopped
    }

    log.info(
      "[STATE-HEAL] Starting account storage re-download — GetAccountRange",
      // slog-compatible key=value pairs surfaced in log message
      // account={} canonicalRoot={}
      ByteStringUtils.hash2string(accountAddress) + " canonicalRoot=" + ByteStringUtils.hash2string(root.take(8))
    )

    val request = GetAccountRange(
      requestId = ETH66.nextRequestId,
      rootHash = root,
      startingHash = accountHash, // start at this account's hash
      limitHash = maxHash, // any limit above will include it
      responseBytes = BigInt(256 * 1024)
    )
    val resp = makeRequest(
      Request(request, BestSnapPeer, (msg: GetAccountRange) => new GetAccountRangeEnc(msg)),
      Retry
    )
    context.pipeToSelf(resp.unsafeToFuture()) {
      case Success(res) => res
      case Failure(_)   => Retry
    }
    waitingForAccountRange()
  }

  private def waitingForAccountRange(): Behavior[Command] =
    Behaviors.receiveMessage {
      case AdaptedMessage(peer, AccountRange(_, accounts, _)) =>
        // Find our specific account in the response
        accounts.find { case (hash, _) => hash == accountHash } match {
          case Some((_, canonicalAccount)) =>
            log.info(
              "[STATE-HEAL] GetAccountRange complete — canonical storageRoot={} account={}",
              ByteStringUtils.hash2string(canonicalAccount.storageRoot.take(4)),
              ByteStringUtils.hash2string(accountAddress.take(4))
            )
            sendGetStorageRanges(canonicalAccount)
          case None =>
            log.warn(
              "[STATE-HEAL] Account {} not found in AccountRange response from {} — failing",
              ByteStringUtils.hash2string(accountAddress),
              peer.id
            )
            replyTo ! BlockFetcher.FetchedAccountStorage(accountAddress, None, success = false)
            Behaviors.stopped
        }

      case Retry =>
        log.warn(
          "[STATE-HEAL] GetAccountRange failed for account {} — failing",
          ByteStringUtils.hash2string(accountAddress)
        )
        replyTo ! BlockFetcher.FetchedAccountStorage(accountAddress, None, success = false)
        Behaviors.stopped

      case _ =>
        Behaviors.unhandled
    }

  private def sendGetStorageRanges(canonicalAccount: Account): Behavior[Command] = {
    val root = canonicalStateRoot.getOrElse(ByteString.empty)
    val storageRoot = canonicalAccount.storageRoot

    if (storageRoot == Account.EmptyStorageRootHash) {
      // Account has no storage — just return the account so BlockImporter can update the leaf
      log.info(
        "[STATE-HEAL] Account {} has empty storage — no storage nodes needed",
        ByteStringUtils.hash2string(accountAddress)
      )
      replyTo ! BlockFetcher.FetchedAccountStorage(accountAddress, Some(canonicalAccount), success = true)
      return Behaviors.stopped
    }

    val request = GetStorageRanges(
      requestId = ETH66.nextRequestId,
      rootHash = root,
      accountHashes = Seq(accountHash),
      startingHash = minHash,
      limitHash = maxHash,
      responseBytes = BigInt(2 * 1024 * 1024) // 2MB — get the full storage
    )
    val resp = makeRequest(
      Request(request, BestSnapPeer, (msg: GetStorageRanges) => new GetStorageRangesEnc(msg)),
      Retry
    )
    context.pipeToSelf(resp.unsafeToFuture()) {
      case Success(res) => res
      case Failure(_)   => Retry
    }
    waitingForStorageRanges(canonicalAccount)
  }

  private def waitingForStorageRanges(canonicalAccount: Account): Behavior[Command] =
    Behaviors.receiveMessage {
      case AdaptedMessage(_, StorageRanges(_, _, proof)) =>
        // Write the Merkle proof trie nodes to RocksDB so storage trie traversal works
        val proofNodes = proof.map { nodeRlp =>
          val nodeHash = com.chipprbots.ethereum.crypto.kec256(nodeRlp)
          (nodeHash, nodeRlp.toArray)
        }
        if (proofNodes.nonEmpty) {
          try {
            stateStorage.getBackingStorage(BigInt(0)).storeRawNodes(proofNodes)
            log.info(
              "[STATE-HEAL] GetStorageRanges complete — wrote {} proof nodes for account {}",
              proofNodes.size,
              ByteStringUtils.hash2string(accountAddress.take(4))
            )
          } catch {
            case ex: Exception =>
              log.warn(
                "[STATE-HEAL] Failed to write storage proof nodes for account {}: {}",
                ByteStringUtils.hash2string(accountAddress),
                ex.getMessage
              )
          }
        }
        // Reply with canonical account; BlockImporter will update the MPT leaf
        replyTo ! BlockFetcher.FetchedAccountStorage(accountAddress, Some(canonicalAccount), success = true)
        Behaviors.stopped

      case Retry =>
        log.warn(
          "[STATE-HEAL] GetStorageRanges failed for account {} — failing",
          ByteStringUtils.hash2string(accountAddress)
        )
        replyTo ! BlockFetcher.FetchedAccountStorage(accountAddress, None, success = false)
        Behaviors.stopped

      case _ =>
        Behaviors.unhandled
    }

  override def onMessage(message: Command): Behavior[Command] =
    Behaviors.unhandled // All state is managed via Behaviors.receiveMessage
}
