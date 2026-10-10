package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.duration.*

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.SyncPhase.*
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncMetrics
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

private[snap] trait StateValidationState:
  var validationInProgress: Boolean
  var validationGeneration: Long
  var healingValidatedRoot: Option[TrieRoot]

private[snap] trait ValidationApi:
  def validateState(): Unit

private[snap] trait LifecycleApi:
  def recordCriticalFailure(reason: String): Boolean
  def enterDormantMode(reason: String): Behavior[Command]
  def restartSnapSync(reason: String): Behavior[Command]

/** State validation (spec 016 M2, research.md R6 "Validation"): the post-heal account and storage trie walks
  * (`validateState`, `spawnAccountValidation`, `spawnStorageValidation`, run on `snapValidationEc` through
  * `validatorFactory`) and the `StateValidation` arms of `syncing` (`stateValidationArms`: the stale-generation drops,
  * then the result handlers). The core's dispatcher consults `stateValidationArms` through
  * `phaseArms(StateValidation)`, and `staleValidationDropArms` from `commonSyncingArms` in every other phase.
  */
private[snap] trait StateValidationModule extends ValidationApi:
  self: StateValidationState & SnapSharedState & SnapControllerEnv & ResumeApi & HealingApi & LifecycleApi =>

  // Retry counter for validation failures to prevent infinite loops
  private var validationRetryCount: Int = 0
  private lazy val MaxValidationRetries = 3
  private lazy val ValidationRetryDelay = 500.millis

  /** The stale-generation drops for the validation results (#60-#62). They carry no phase guard, so they run in every
    * phase: first in `stateValidationArms`, and from their original place in `commonSyncingArms` for the other phases
    * (research.md R4b note 1).
    */
  private[snap] lazy val staleValidationDropArms: PartialFunction[Command, Behavior[Command]] = {
    // Stale-generation drop. Anything that bumps `validationGeneration`
    // (restartSnapSync, completePivotRefreshWithStateRoot, fresh validateState
    // spawn) means an in-flight Future's result is no longer applicable —
    // ignore quietly without mutating state.
    case ValidateAccountTrieResult(gen, _, _) if gen != validationGeneration =>
      ctx.log.debug(s"Dropping stale ValidateAccountTrieResult (gen=$gen, current=$validationGeneration)")
      Behaviors.same

    case ValidateStorageTriesResult(gen, _, _) if gen != validationGeneration =>
      ctx.log.debug(s"Dropping stale ValidateStorageTriesResult (gen=$gen, current=$validationGeneration)")
      Behaviors.same

    case ValidationRetry(retryGen) if retryGen != validationGeneration =>
      ctx.log.debug(s"Dropping stale ValidationRetry (gen=$retryGen, current=$validationGeneration)")
      Behaviors.same
  }

  /** The `syncing` arms that were guarded on `currentPhase == StateValidation` (P4d, guards dropped). They are reached
    * only through `phaseArms(StateValidation)`, after `staleValidationDropArms`.
    */
  private lazy val stateValidationResultArms: PartialFunction[Command, Behavior[Command]] = {
    // Account trie validation result handlers. Reached only in StateValidation (phaseArms) and only for the current
    // generation (staleValidationDropArms runs first). Any state mutation lives only in these handlers (never inside
    // the Future).
    case ValidateAccountTrieResult(_, Right(missing), elapsedMs) =>
      if missing.isEmpty then
        ctx.log.info(s"Account trie validation successful - no missing nodes (${elapsedMs}ms)")
        validationRetryCount = 0
        // Spawn the storage pass on the same generation; result handlers below.
        for root <- stateRoot; pivot <- pivotBlock do spawnStorageValidation(validationGeneration, root.value, pivot)
      else
        ctx.log.warn(
          s"Account trie validation found ${missing.size} missing nodes — triggering healing"
        )
        SNAPSyncMetrics.setMissingNodesDetected(missing.size.toLong)
        validationInProgress = false
        triggerHealingForMissingNodes(missing)
      Behaviors.same

    case ValidateAccountTrieResult(_, Left(error), _) =>
      SNAPSyncMetrics.incrementValidationFailure()
      ctx.log.error(s"Account trie validation failed: $error")
      var earlyBehavior: Option[Behavior[Command]] = None
      if error.contains("Missing root node") then
        validationRetryCount += 1
        // Clear the in-progress flag *before* scheduling the retry. If we left
        // it set, ValidationRetry would refuse to spawn and the path
        // deadlocks silently. The retry handler kicks `validateState()` which
        // bumps the generation again and sets the flag fresh.
        validationInProgress = false
        if validationRetryCount > MaxValidationRetries then
          val retryMsg = s"root node missing after $validationRetryCount validation retries"
          if recordCriticalFailure(retryMsg) then
            ctx.log.error("Too many critical SNAP failures — entering dormant mode")
            enterDormantMode(s"validation retry exhausted: $retryMsg")
          else
            ctx.log.warn(
              s"Root node missing after $validationRetryCount validation attempts. " +
                "Restarting SNAP sync with a fresh pivot to rebuild the state trie."
            )
            validationRetryCount = 0
            earlyBehavior = Some(restartSnapSync(retryMsg))
        else
          ctx.log.error(s"Root node is missing (retry attempt $validationRetryCount of $MaxValidationRetries)")
          val gen = validationGeneration
          // Schedule on context.dispatcher (the actor's own scheduler), not
          // snapValidationEc — the retry message is cheap and shouldn't be
          // tied to the long-running pool's lifecycle.
          timers.startSingleTimer("validation-retry", ValidationRetry(gen), ValidationRetryDelay)
      else
        ctx.log.error("Recovering through healing phase")
        validationInProgress = false
        currentPhase = StateHealing
        startStateHealing()
      earlyBehavior.getOrElse(Behaviors.same)

    // Storage trie validation result handlers.
    case ValidateStorageTriesResult(_, Right(missing), elapsedMs) =>
      validationInProgress = false
      SNAPSyncMetrics.setMissingNodesDetected(missing.size.toLong)
      if missing.isEmpty then
        ctx.log.info(s"Storage trie validation successful - no missing nodes (${elapsedMs}ms)")
        ctx.log.info("✅ State validation COMPLETE - all tries are intact")
        ctx.self ! StateValidationComplete
      else
        ctx.log.warn(
          s"Storage trie validation found ${missing.size} missing nodes — triggering healing"
        )
        triggerHealingForMissingNodes(missing)
      Behaviors.same

    case ValidateStorageTriesResult(_, Left(error), _) =>
      SNAPSyncMetrics.incrementValidationFailure()
      ctx.log.error(s"Storage trie validation failed: $error. Recovering through healing phase")
      validationInProgress = false
      currentPhase = StateHealing
      startStateHealing()
      Behaviors.same

    case ValidationRetry(_) =>
      // Generation was already verified above by the stale-drop handler.
      // `validateState()` bumps the generation again and spawns a fresh pass.
      validateState()
      Behaviors.same
  }

  /** The `StateValidation` phase function: the stale-generation drops first, as in `syncing` before P4d, then the
    * `StateValidation` arms.
    */
  private[snap] lazy val stateValidationArms: PartialFunction[Command, Behavior[Command]] =
    staleValidationDropArms.orElse(stateValidationResultArms)

  /** Spawn the account-trie validation walk on the dedicated dispatcher.
    *
    * Async: results come back as `ValidateAccountTrieResult(generation, ...)` self-messages so the actor mailbox stays
    * responsive during the multi-minute walk. `onComplete` (not `.foreach`) ensures every Future outcome — including a
    * throw inside `validatorFactory(storage)` — produces a message; otherwise the in-progress flag could stick.
    */
  private def spawnAccountValidation(generation: Long, expectedRoot: ByteString, pivot: BigInt): Unit =
    val storage = getOrCreateMptStorage(pivot)
    val selfRef = ctx.self
    val start = System.currentTimeMillis()
    scala.concurrent
      .Future {
        val v = validatorFactory(storage)
        (v.validateAccountTrie(expectedRoot), System.currentTimeMillis() - start)
      }(snapValidationEc)
      .onComplete {
        case scala.util.Success((result, elapsed)) =>
          selfRef ! ValidateAccountTrieResult(generation, result, elapsed)
        case scala.util.Failure(e) =>
          selfRef ! ValidateAccountTrieResult(generation, Left(e.getMessage), -1L)
      }(snapValidationEc)

  private def spawnStorageValidation(generation: Long, expectedRoot: ByteString, pivot: BigInt): Unit =
    val storage = getOrCreateMptStorage(pivot)
    val selfRef = ctx.self
    val start = System.currentTimeMillis()
    scala.concurrent
      .Future {
        val v = validatorFactory(storage)
        (v.validateAllStorageTries(expectedRoot), System.currentTimeMillis() - start)
      }(snapValidationEc)
      .onComplete {
        case scala.util.Success((result, elapsed)) =>
          selfRef ! ValidateStorageTriesResult(generation, result, elapsed)
        case scala.util.Failure(e) =>
          selfRef ! ValidateStorageTriesResult(generation, Left(e.getMessage), -1L)
      }(snapValidationEc)

  def validateState(): Unit =
    if !snapSyncConfig.stateValidationEnabled then
      ctx.log.info("State validation disabled, skipping...")
      ctx.self ! StateValidationComplete
    else if validationInProgress then
      ctx.log.info("validateState called while validation is already in progress; ignoring")
    else
      (stateRoot, pivotBlock) match
        case (Some(expectedRoot), Some(pivot)) =>
          // #1188: short-circuit when the round-2 healing trie walk just verified the same root.
          // The walk visits every node in the account trie + every storage trie via DFS — same
          // work `validateAccountTrie + validateAllStorageTries` would redo. Belt-and-suspenders:
          // only honoured when captured root *equals* current `stateRoot`, so any pivot refresh
          // or restart naturally invalidates the signal (root changes → no match → full validation).
          if healingValidatedRoot.contains(expectedRoot) then
            ctx.log.info(
              s"Skipping redundant state validation — healing trie walk verified the entire " +
                s"account+storage trie against ${expectedRoot.value.take(8).toHex} (clean signal)"
            )
            // Consume the signal so a re-entry (e.g. after a future healing-recovery cycle that
            // didn't finish with a clean walk) doesn't reuse a stale positive.
            healingValidatedRoot = None
            ctx.self ! StateValidationComplete
          else
            validationInProgress = true
            validationGeneration += 1
            val gen = validationGeneration
            ctx.log.info(s"Validating state against expected root: ${expectedRoot.value.take(8).toHex} (gen=$gen)")
            spawnAccountValidation(gen, expectedRoot.value, pivot)
        case _ =>
          ctx.log.error("Missing state root or pivot block for validation — cannot complete sync")
          validationInProgress = false
  // end else (stateValidationEnabled && !validationInProgress)
