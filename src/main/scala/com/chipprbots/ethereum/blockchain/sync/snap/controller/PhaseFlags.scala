package com.chipprbots.ethereum.blockchain.sync.snap.controller

/** The SNAP controller's phase-complete and force-complete flags (spec 016 P2, FR-011), and the one place that resets
  * them per reset kind.
  *
  * The controller core mixes this trait in. Every flag keeps its name and initial value, so each existing use compiles
  * unchanged. Phase modules name this trait in their self-type to read or write the flags.
  *
  * `reset(kind)` clears exactly the flags each reset path cleared before P2 (research.md R3b). The kinds clear
  * different sets on purpose (spec Edge Cases, "Partial resets are deliberate"); do not unify them here. A difference
  * that looks like a bug goes to CHASE-QUEUE and is fixed in its own PR. Resets of anything that is not one of these
  * nine flags (probe and refresh state, validation, the stagnation clocks, the child refs) stay at their call sites.
  */
private[snap] trait PhaseFlags:

  // Set true whenever account-range cursors were resumed from a prior session (resumeProgress
  // non-empty). Forces StateHealing to run at completion (overriding the deferred-merkleization
  // skip-healing fast path) so a delta downloaded against a drifted root is never handed off
  // unwalked. This is the single load-bearing anti-corruption guard for cursor resume.
  var resumedStaleCursors: Boolean = false

  // Geth-aligned: all 3 coordinators run concurrently from first account response.
  // accountsComplete is set when AccountRangeSyncComplete arrives and NoMore sentinels are sent.
  var accountsComplete: Boolean = false

  // Concurrent phase completion tracking (all 3 coordinators run in parallel)
  var bytecodePhaseComplete: Boolean = false
  var storagePhaseComplete: Boolean = false
  var storagePhaseForceCompleted: Boolean = false

  // The healed-code finalisation hold (see `healedCodeHashes` in the controller core): set while SNAP waits for the
  // bytecode of accounts that arrived only through healing, and once that wait has run out.
  var awaitingHealedCode: Boolean = false
  var healedCodeWaitExhausted: Boolean = false
  // The bytecode phase was force-completed with tasks abandoned (stagnation): some bytecode was never fetched.
  var bytecodeForceCompleted: Boolean = false

  // Prevent sending ForceCompleteStorage more than once per coordinator lifecycle.
  // SNAPSyncController can queue 10+ StorageCoordinatorProgress responses before the first
  // StorageRangeSyncForceCompleted reply arrives, causing storagePhaseComplete to still be false
  // for all of them. Without this guard, each one sends a duplicate ForceCompleteStorage.
  var forceCompleteStorageSent: Boolean = false

  /** Clear the flags that the reset path `kind` clears, in the order that path always cleared them.
    *
    *   - `Start` (`startSnapSync`, fresh-pivot recovery branch): `storagePhaseForceCompleted` only. That branch sets
    *     the other phase flags from disk, and clears `forceCompleteStorageSent` itself only when it spawns a storage
    *     coordinator.
    *   - `Restart` (`restartSnapSync`) and `Wake` (`wakeFromDormant`): the three phase-complete flags,
    *     `storagePhaseForceCompleted` and `forceCompleteStorageSent`. Both sites clear the three healed-code flags just
    *     before, through the core's `resetHealedCodeHold()`, which also clears the healed codeHashes and cancels their
    *     timer. The two sets are equal today, but they are separate kinds and stay separate.
    *   - `Dormant` (`enterDormantMode`): nothing. Dormancy leaves every phase flag as it is.
    *
    * `resumedStaleCursors` is cleared by no kind.
    */
  def reset(kind: PhaseFlags.ResetKind): Unit =
    kind match
      case PhaseFlags.ResetKind.Start =>
        storagePhaseForceCompleted = false
      case PhaseFlags.ResetKind.Restart =>
        accountsComplete = false
        bytecodePhaseComplete = false
        storagePhaseComplete = false
        storagePhaseForceCompleted = false
        forceCompleteStorageSent = false
      case PhaseFlags.ResetKind.Wake =>
        accountsComplete = false
        bytecodePhaseComplete = false
        storagePhaseComplete = false
        storagePhaseForceCompleted = false
        forceCompleteStorageSent = false
      case PhaseFlags.ResetKind.Dormant =>
        ()

private[snap] object PhaseFlags:

  /** The four reset paths of the controller (spec 016 FR-011). */
  enum ResetKind:
    case Start, Restart, Wake, Dormant
