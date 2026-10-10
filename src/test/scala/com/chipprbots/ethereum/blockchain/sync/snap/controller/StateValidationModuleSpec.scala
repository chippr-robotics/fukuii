package com.chipprbots.ethereum.blockchain.sync.snap.controller

import org.apache.pekko.actor.testkit.typed.Effect
import org.apache.pekko.actor.testkit.typed.scaladsl.BehaviorTestKit
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.TimerScheduler
import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.SNAPRequestTracker
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncConfig
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController
import com.chipprbots.ethereum.blockchain.sync.snap.SNAPSyncController.*
import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.blockchain.sync.snap.SyncProgressMonitor
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.FlatSlotStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.testing.Tags.UnitTest
import com.chipprbots.ethereum.utils.Config.SyncConfig

/** Everything `StateValidationModule` needs, as a stub (spec 016 FR-016 (b)): its `StateValidationState`, the hubs, the
  * environment and the three callee Api traits. State is plain vars; the Api calls the tests can reach are recorded;
  * the members these tests never reach throw.
  */
private[snap] class StubStateValidationState(
    val ctx: ActorContext[SNAPSyncController.Command],
    val timers: TimerScheduler[SNAPSyncController.Command]
) extends StateValidationState
    with SnapSharedState
    with SnapControllerEnv
    with ResumeApi
    with HealingApi
    with LifecycleApi:

  // StateValidationState
  var validationInProgress: Boolean = false
  var validationGeneration: Long = 0L
  var healingValidatedRoot: Option[TrieRoot] = None

  // SnapSharedState
  var pivotBlock: Option[BigInt] = None
  var stateRoot: Option[TrieRoot] = None
  var currentPhase: SyncPhase = SyncPhase.StateValidation
  def progressMonitor: SyncProgressMonitor = notUsed("progressMonitor")
  def requestTracker: SNAPRequestTracker = notUsed("requestTracker")

  // SnapControllerEnv
  val snapSyncConfig: SNAPSyncConfig = SNAPSyncConfig()
  def syncConfig: SyncConfig = notUsed("syncConfig")
  def asyncLog: org.slf4j.Logger = notUsed("asyncLog")
  def appStateStorage: AppStateStorage = notUsed("appStateStorage")
  def stateStorage: StateStorage = notUsed("stateStorage")
  def evmCodeStorage: EvmCodeStorage = notUsed("evmCodeStorage")
  def flatSlotStorage: FlatSlotStorage = notUsed("flatSlotStorage")
  def validatorFactory: MptStorage => StateValidator = notUsed("validatorFactory")
  def snapValidationEc: ExecutionContext = notUsed("snapValidationEc")

  // Callee Api traits
  val healingTriggeredFor: mutable.ArrayBuffer[Seq[ByteString]] = mutable.ArrayBuffer.empty
  var stateHealingStarts: Int = 0
  def getOrCreateMptStorage(pivotBlockNumber: BigInt): MptStorage = notUsed(s"getOrCreateMptStorage($pivotBlockNumber)")
  def triggerHealingForMissingNodes(missingNodes: Seq[ByteString]): Unit = healingTriggeredFor += missingNodes
  def startStateHealing(): Unit = stateHealingStarts += 1
  val criticalFailures: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  val dormantReasons: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  val restartReasons: mutable.ArrayBuffer[String] = mutable.ArrayBuffer.empty
  // What `recordCriticalFailure` answers ("the failure limit is reached"), and what the two lifecycle calls return.
  var criticalFailureLimitReached: Boolean = false
  var lifecycleBehavior: Behavior[Command] = Behaviors.same
  def recordCriticalFailure(reason: String): Boolean =
    criticalFailures += reason
    criticalFailureLimitReached
  def enterDormantMode(reason: String): Behavior[Command] =
    dormantReasons += reason
    lifecycleBehavior
  def restartSnapSync(reason: String): Behavior[Command] =
    restartReasons += reason
    lifecycleBehavior

  private def notUsed(what: String): Nothing =
    throw new UnsupportedOperationException(s"$what is not reached by StateValidationModuleSpec")

/** Spec 016 M2 stub test (FR-016 (b), T043): `StateValidationModule` mixed into a stub of its state interface,
  * capabilities and callee Api traits, driven through `stateValidationArms` (the module's `syncing` arms) inside a
  * `BehaviorTestKit` so that `ctx.self` and `ctx.log` are real. The cases read `StateValidationState`, and all but the
  * stale-generation drop write it.
  */
class StateValidationModuleSpec extends AnyFlatSpec with Matchers:

  private val root = TrieRoot(ByteString(Array.fill[Byte](32)(7)))

  /** A behaviour that hands every message to the module's `stateValidationArms`, and the stub it runs on. */
  private def newModule(): (StubStateValidationState & StateValidationModule, BehaviorTestKit[Command]) =
    var captured: StubStateValidationState & StateValidationModule = null
    val behavior = Behaviors.setup[Command] { ctx =>
      Behaviors.withTimers { timers =>
        val module = new StubStateValidationState(ctx, timers) with StateValidationModule
        captured = module
        Behaviors.receiveMessage(msg =>
          module.stateValidationArms.applyOrElse(msg, (_: Command) => Behaviors.unhandled[Command])
        )
      }
    }
    val kit = BehaviorTestKit(behavior)
    (captured, kit)

  /** The timers started since the last look, as (key, message, delay). */
  private def timersStarted(kit: BehaviorTestKit[Command]): Seq[(Any, Any, FiniteDuration)] =
    kit.retrieveAllEffects().collect { case t: Effect.TimerScheduled[?] => (t.key, t.msg, t.delay) }

  private val missingRoot = Left("Missing root node: 0707070707070707")

  /** One current-generation account pass that failed on the missing root, as `validateState()` would have left it. */
  private def missRoot(module: StubStateValidationState, kit: BehaviorTestKit[Command]): Unit =
    module.validationInProgress = true
    kit.run(ValidateAccountTrieResult(module.validationGeneration, missingRoot, 10L))

  "StateValidationModule" should "drop stale-generation validation results without touching its state" taggedAs UnitTest in {
    val (module, kit) = newModule()
    module.validationGeneration = 5L
    module.validationInProgress = true

    kit.run(ValidateAccountTrieResult(4L, Right(Seq(ByteString(Array[Byte](1)))), 10L))
    kit.run(ValidateStorageTriesResult(4L, Right(Seq.empty), 10L))
    kit.run(ValidationRetry(4L))

    module.validationInProgress shouldBe true
    module.validationGeneration shouldBe 5L
    module.healingTriggeredFor shouldBe empty
    kit.selfInbox().hasMessages shouldBe false
  }

  it should "clear validationInProgress and report completion on a clean current-generation storage pass" taggedAs UnitTest in {
    val (module, kit) = newModule()
    module.validationGeneration = 5L
    module.validationInProgress = true

    kit.run(ValidateStorageTriesResult(5L, Right(Seq.empty), 10L))

    module.validationInProgress shouldBe false
    module.validationGeneration shouldBe 5L
    kit.selfInbox().receiveAll() shouldBe Seq(StateValidationComplete)
  }

  it should "clear validationInProgress and hand missing account nodes to healing" taggedAs UnitTest in {
    val (module, kit) = newModule()
    module.validationGeneration = 3L
    module.validationInProgress = true
    val missing = Seq(ByteString(Array[Byte](1, 2)), ByteString(Array[Byte](3, 4)))

    kit.run(ValidateAccountTrieResult(3L, Right(missing), 10L))

    module.validationInProgress shouldBe false
    module.healingTriggeredFor.toSeq shouldBe Seq(missing)
    kit.selfInbox().hasMessages shouldBe false
  }

  it should "consume the healing clean signal for the current root instead of walking the trie again" taggedAs UnitTest in {
    val (module, kit) = newModule()
    module.validationGeneration = 7L
    module.stateRoot = Some(root)
    module.pivotBlock = Some(BigInt(100))
    module.healingValidatedRoot = Some(root)

    // A current-generation retry runs validateState(), which honours the clean signal: no walk, no generation bump.
    kit.run(ValidationRetry(7L))

    module.healingValidatedRoot shouldBe None
    module.validationInProgress shouldBe false
    module.validationGeneration shouldBe 7L
    kit.selfInbox().receiveAll() shouldBe Seq(StateValidationComplete)
  }

  it should "retry a missing root after 500 ms, up to three times, without escalating" taggedAs UnitTest in {
    val (module, kit) = newModule()
    module.validationGeneration = 2L

    for _ <- 1 to 3 do
      missRoot(module, kit)
      module.validationInProgress shouldBe false
      timersStarted(kit) shouldBe Seq(("validation-retry", ValidationRetry(2L), 500.millis))

    module.criticalFailures shouldBe empty
    module.restartReasons shouldBe empty
    module.dormantReasons shouldBe empty
    module.validationGeneration shouldBe 2L
  }

  it should "restart SNAP when the fourth miss is below the critical-failure limit, then count retries from zero" taggedAs UnitTest in {
    val (module, kit) = newModule()
    module.validationGeneration = 2L
    for _ <- 1 to 3 do missRoot(module, kit)
    timersStarted(kit) should have size 3

    missRoot(module, kit)

    val reason = "root node missing after 4 validation retries"
    module.criticalFailures.toSeq shouldBe Seq(reason)
    module.restartReasons.toSeq shouldBe Seq(reason)
    module.dormantReasons shouldBe empty
    module.validationInProgress shouldBe false
    timersStarted(kit) shouldBe empty

    // The counter was reset: the next miss is retry 1 of 3 again.
    missRoot(module, kit)
    timersStarted(kit) shouldBe Seq(("validation-retry", ValidationRetry(2L), 500.millis))
    module.criticalFailures should have size 1
  }

  it should "continue in the behaviour restartSnapSync returns" taggedAs UnitTest in {
    val (module, kit) = newModule()
    module.validationGeneration = 2L
    for _ <- 1 to 3 do missRoot(module, kit)

    module.lifecycleBehavior = Behaviors.stopped
    missRoot(module, kit)

    module.restartReasons should have size 1
    kit.isAlive shouldBe false
  }

  it should "enter dormant mode when the fourth miss reaches the critical-failure limit (today: keeps the current behaviour and the count)" taggedAs UnitTest in {
    val (module, kit) = newModule()
    module.validationGeneration = 2L
    module.criticalFailureLimitReached = true
    // If the arm returned enterDormantMode's behaviour, the kit would stop here.
    module.lifecycleBehavior = Behaviors.stopped
    for _ <- 1 to 3 do missRoot(module, kit)
    timersStarted(kit) should have size 3

    missRoot(module, kit)

    module.criticalFailures.toSeq shouldBe Seq("root node missing after 4 validation retries")
    module.dormantReasons.toSeq shouldBe Seq("validation retry exhausted: root node missing after 4 validation retries")
    module.restartReasons shouldBe empty
    timersStarted(kit) shouldBe empty
    // Today the arm returns Behaviors.same, not enterDormantMode's behaviour, and does not reset the retry count.
    kit.isAlive shouldBe true
    missRoot(module, kit)
    module.criticalFailures.toSeq shouldBe Seq(
      "root node missing after 4 validation retries",
      "root node missing after 5 validation retries"
    )
  }

  it should "recover through healing on any other account-trie failure, without a retry" taggedAs UnitTest in {
    val (module, kit) = newModule()
    module.validationGeneration = 2L
    module.validationInProgress = true

    kit.run(ValidateAccountTrieResult(2L, Left("trie walk failed: corrupted node"), 10L))

    module.validationInProgress shouldBe false
    module.currentPhase shouldBe SyncPhase.StateHealing
    module.stateHealingStarts shouldBe 1
    module.criticalFailures shouldBe empty
    timersStarted(kit) shouldBe empty
  }
