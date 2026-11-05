package com.chipprbots.ethereum.blockchain.sync.snap.actors

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.PostStop
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.blockchain.sync.snap.*
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.p2p.messages.SNAP.*
import com.chipprbots.ethereum.utils.ByteStringUtils.toHex

/** AccountRangeWorker fetches a single account range from a peer.
  *
  * Responsibilities:
  *   - Request single account range from peer
  *   - Handle response and validate proofs
  *   - Report result to coordinator
  *
  * Lifecycle:
  *   1. Created by coordinator when needed 2. Fetches one task 3. Reports result 4. Can be reused for next task or
  *      stopped
  *
  * Pekko Typed leaf actor (Group W1). `coordinator` is a typed ref (§8k-A). `networkPeerManager` is a typed ref
  * (§8k-B11).
  */
object AccountRangeWorker:

  import AccountRangeCoordinator.*

  type Command = WorkerMessage

  /** @param coordinator
    *   Parent coordinator actor (Typed)
    * @param networkPeerManager
    *   Actor for network communication (Typed)
    * @param requestTracker
    *   Tracker for requests
    */
  def apply(
      coordinator: ActorRef[AccountRangeCoordinator.Command],
      networkPeerManager: ActorRef[com.chipprbots.ethereum.network.NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker
  ): Behavior[Command] =
    Behaviors.setup { context =>
      context.log.debug(s"AccountRangeWorker ${context.self.path.name} started")
      idle(coordinator, networkPeerManager, requestTracker, currentTask = None)
    }

  // currentTask 4-tuple: (task, peer, requestId, expectedRoot)
  // expectedRoot is snapshotted from task.rootHash at FetchAccountRange receive time so that
  // a concurrent pivot refresh (task.rootHash mutation by the coordinator) cannot affect
  // the Merkle proof verification that runs when the response arrives.

  private def idle(
      coordinator: ActorRef[AccountRangeCoordinator.Command],
      networkPeerManager: ActorRef[com.chipprbots.ethereum.network.NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      currentTask: Option[(AccountTask, Peer, BigInt, ByteString)]
  ): Behavior[Command] =
    Behaviors
      .receive[Command] { (context, msg) =>
        msg match
          case _: WorkerRequestCancelled  => Behaviors.same // #1184: idempotent — already idle, nothing to clear
          case _: WorkerPeerDisconnected  => Behaviors.same // No current task, nothing to do
          case _: AccountRangeResponseMsg => Behaviors.same // stale response while idle; ignore
          case _: RequestTimeout          => Behaviors.same // stale timeout while idle; ignore
          case FetchAccountRange(task, peer, requestId, responseBytes) =>
            // Snapshot rootHash now — the coordinator may mutate task.rootHash on pivot refresh
            // while this response is in-flight. Using the snapshot makes proof verification
            // deterministic regardless of when the mutation occurs.
            val expectedRoot = task.rootHash
            context.log.debug(
              s"Fetching account range ${task.rangeString} from peer ${peer.id} (responseBytes=$responseBytes)"
            )

            val request = GetAccountRange(
              requestId = requestId,
              rootHash = expectedRoot,
              startingHash = task.next,
              limitHash = task.last,
              responseBytes = responseBytes
            )

            // Track the request with adaptive timeout from SNAPRequestTracker / PeerRateTracker
            // (geth msgrate algorithm). Starts at ~12s for a fresh tracker, converges down as peers
            // respond — slow peers get pruned faster instead of holding in-flight slots for a full 30s.
            // The callback runs outside the actor context, so capture context.self for the self-send.
            val selfRef = context.self
            requestTracker.trackRequest(
              requestId,
              peer,
              SNAPRequestTracker.RequestType.GetAccountRange
            ) {
              selfRef ! RequestTimeout(requestId)
            }

            // Send message to peer
            import com.chipprbots.ethereum.network.NetworkPeerManagerActor
            import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetAccountRange.GetAccountRangeEnc
            import com.chipprbots.ethereum.network.p2p.MessageSerializable
            val messageSerializable: MessageSerializable = new GetAccountRangeEnc(request)
            networkPeerManager ! NetworkPeerManagerActor.SendMessageCmd(messageSerializable, peer.id)

            working(coordinator, networkPeerManager, requestTracker, Some((task, peer, requestId, expectedRoot)))
      }
      .receiveSignal(postStopSignal(currentTask))

  private def working(
      coordinator: ActorRef[AccountRangeCoordinator.Command],
      networkPeerManager: ActorRef[com.chipprbots.ethereum.network.NetworkPeerManagerActor.Command],
      requestTracker: SNAPRequestTracker,
      currentTask: Option[(AccountTask, Peer, BigInt, ByteString)]
  ): Behavior[Command] =
    def goIdle: Behavior[Command] = idle(coordinator, networkPeerManager, requestTracker, currentTask = None)

    Behaviors
      .receive[Command] { (context, msg) =>
        msg match
          case AccountRangeResponseMsg(response) =>
            currentTask match
              case Some((task, _, reqId, expectedRoot)) if response.requestId == reqId =>
                context.log.debug(
                  s"Received AccountRange: reqId=$reqId range=${task.rangeString} " +
                    s"start=${task.next.take(4).toHex} limit=${task.last.take(4).toHex} " +
                    s"accounts=${response.accounts.size} proofNodes=${response.proof.size}"
                )

                val accountCount = response.accounts.size

                // Validate basic response invariants (monotonic ordering, correct tracked type)
                // while the request is still pending in the tracker.
                val validated = requestTracker.validateAccountRange(response)

                // Complete the request in tracker (cancel timeout) regardless of validation outcome.
                // A proof-only empty range is still a served response, not a timeout/failure.
                val responseItemsForRate =
                  if accountCount > 0 then accountCount
                  else if response.proof.nonEmpty then 1
                  else 0
                requestTracker.completeRequest(reqId, responseItemsForRate)

                // Verify Merkle proof against the snapshotted pivot state root (expectedRoot),
                // not task.rootHash — the coordinator may have mutated it during a pivot refresh.
                val proofVerifier = MerkleProofVerifier(expectedRoot)
                // Guard: proof verification can throw (e.g., malformed proof, unexpected node type).
                // Without this try/catch the worker crashes silently — the coordinator never gets
                // TaskFailed/TaskComplete and the task stays stuck in activeTasks forever (BUG-R03-001).
                val proofOk =
                  try
                    validated.flatMap { validResponse =>
                      val endHash = validResponse.accounts.lastOption.map(_._1).getOrElse(task.last)
                      proofVerifier.verifyAccountRange(
                        accounts = validResponse.accounts,
                        proof = validResponse.proof,
                        startHash = task.next,
                        endHash = endHash
                      )
                    }
                  catch
                    case ex: Throwable =>
                      // Catch Throwable (not just Exception) so that StackOverflowError and other JVM
                      // Errors don't escape to the actor system. Under Pekko's Resume supervisor strategy
                      // an uncaught Error leaves the actor alive but stuck — the coordinator never gets
                      // TaskFailed/TaskComplete and the task is lost.
                      context.log.warn(
                        s"[WORKER] Proof verification threw for reqId=$reqId range=${task.rangeString}: ${ex.getClass.getSimpleName}: ${ex.getMessage}"
                      )
                      Left(s"proof verification exception: ${ex.getClass.getSimpleName}: ${ex.getMessage}")

                proofOk match
                  case Left(error) =>
                    val errorStr = error.toString
                    // Root mismatch during a pivot transition is expected — the peer is serving the new
                    // root while this worker was dispatched against the old one. Demote to debug since
                    // TaskFailed is still sent and the coordinator re-queues normally.
                    if errorStr.contains("root mismatch") || errorStr.contains("Proof root") then
                      context.log.debug(
                        s"AccountRange proof skipped (pivot transition) reqId=$reqId range=${task.rangeString}: $error"
                      )
                    else
                      context.log.warn(
                        s"AccountRange validation/proof failed for reqId=$reqId range=${task.rangeString}: $error"
                      )
                    coordinator ! TaskFailed(reqId, error)

                  case Right(_) =>
                    context.log.debug(s"Successfully received $accountCount accounts")
                    coordinator ! TaskComplete(reqId, Right((accountCount, response.accounts, response.proof)))

                // Return to idle state for potential reuse
                goIdle

              case _ =>
                context.log.warn(s"Received response for wrong request ID: ${response.requestId}")
                Behaviors.same

          case RequestTimeout(reqId) =>
            currentTask match
              case Some((_, _, currentReqId, _)) if currentReqId == reqId =>
                context.log.warn(s"Request $reqId timed out")
                coordinator ! TaskFailed(reqId, "Request timeout")
                goIdle

              case _ =>
                context.log.debug(s"Timeout for old or unknown request $reqId")
                Behaviors.same

          case WorkerPeerDisconnected(peerId) =>
            currentTask match
              case Some((_, peer, reqId, _)) if peer.id.value == peerId =>
                context.log.debug(s"Peer $peerId disconnected — re-queuing task immediately (reqId=$reqId)")
                requestTracker.completeRequest(reqId, 0)
                coordinator ! TaskFailed(reqId, "Peer disconnected")
                goIdle
              case _ => Behaviors.same // Different peer or no task; ignore

          case WorkerRequestCancelled(reqId) =>
            // #1184: coordinator drained `activeTasks` and is owning the re-queue itself —
            // we just clear local state. Do NOT send TaskFailed (coordinator already re-queued).
            // Match existing tracker-ownership contract: worker owns its tracker entry.
            // Idempotent: SNAPRequestTracker.completeRequest is safe on already-removed ids.
            currentTask match
              case Some((_, _, currentReqId, _)) if currentReqId == reqId =>
                context.log.debug(s"Worker request $reqId cancelled by coordinator — clearing state")
                requestTracker.completeRequest(reqId, 0)
                goIdle
              case _ => Behaviors.same // Different reqId or no current task; ignore

          case _: FetchAccountRange =>
            context.log.warn("Worker is busy, cannot accept new task")
            coordinator ! TaskFailed(0, "Worker busy")
            Behaviors.same
      }
      .receiveSignal(postStopSignal(currentTask))

  private def postStopSignal(
      currentTask: Option[(AccountTask, Peer, BigInt, ByteString)]
  ): PartialFunction[(ActorContext[Command], org.apache.pekko.actor.typed.Signal), Behavior[Command]] = {
    case (context, PostStop) =>
      // INFO so worker lifecycle is visible in production logs — essential for detecting
      // unexpected worker deaths (BUG-R03-001: silent crash leaves task stuck in activeTasks).
      val taskDesc =
        currentTask.map { case (t, _, reqId, _) => s" (mid-task ${t.rangeString} reqId=$reqId)" }.getOrElse("")
      context.log.info(s"[WORKER] AccountRangeWorker ${context.self.path.name} stopped$taskDesc")
      Behaviors.same
  }
