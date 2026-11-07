# Pekko Typed API Protocol

Preferred patterns for Pekko Typed actors in the fukuii codebase. Applied when
writing new actors, migrating Classic actors (LOOM), and reviewing existing Typed
code (PRISM). Each preference has a grep pattern for regression detection.

Used by: LOOM (primary), PRISM, MITHRIL
Referenced by: loom.md, prism.md

---

## Migration state context

**The Classic-to-Typed migration of the actor classes is finished (checked on the tree, 2026-11-05).** The node is a
Typed codebase on a Classic `ActorSystem`: `NodeBuilder.classicSystem` hosts every actor
(`classicSystem.spawn(<Behavior>)`) and `given system: ActorSystem[Nothing] = classicSystem.toTyped`. The only Classic
actor classes left in `src/main` are three private TCP bridges, which must stay Classic because Pekko IO's `Tcp` API
is Classic-only: `ServerActor.TcpEventBridge` and `RLPxConnectionHandler`'s `OutboundTcpBridge` and `InboundTcpBridge`.
The Classic adapter use left is the handful of sites the last three greps below list.

```bash
grep -rnE "extends (ClassicActor|Actor|AbstractActor)\b" src/main --include="*.scala"
# 3 hits: the TCP bridges. (`extends Actor\b` alone finds 0: the Classic base class is imported as `ClassicActor`.)
grep -rn "PropsAdapter" src/main --include="*.scala" | grep -vE ':[0-9]+:\s*(//|\*|/\*)'
# 2 hits: network/PeerActor.scala and network/rlpx/RLPxConnectionHandler.scala (the Classic-callable factories)
grep -rn "\.toClassic" src/main --include="*.scala" | grep -vE ':[0-9]+:\s*(//|\*|/\*)'
# 5 hits: PeerManagerActor (2), ServerActor (2), RLPxConnectionHandler (1)
grep -rn "Behavior\[Any\]" src/main --include="*.scala"
# 2 hits, both in doc comments (BytecodeRecoveryActor, StorageRecoveryActor); no `Behavior[Any]` is left in code
```

So the old allowance ("Classic adapters are intentional scaffolding until CAPSTONE") no longer covers new code: a
`.toClassic`, `PropsAdapter` or `Behavior[Any]` outside the sites above is a violation, and the rules below apply
universally.

---

## P1 — `Behaviors.withTimers` over raw scheduler

**Status:** Enforced in all migrated actors.

```bash
grep -rn "context\.system\.scheduler\|system\.scheduler" src/main/ --include="*.scala" \
  | grep -v "//\|test\|Classic\|\.toClassic"
# Target: 0 hits in Typed actors
```

**Prefer:**
```scala
Behaviors.withTimers { timers =>
  timers.startTimerWithFixedDelay("key", Tick, 30.seconds)
  timers.startSingleTimer("key", CheckCompletion, 100.millis)
  // timers cancel automatically on behavior stop
}
```

**Avoid:**
```scala
val task = context.system.scheduler.scheduleAtFixedRate(...)  // manual cancellation required
task.cancel()  // in postStop — easy to forget
```

---

## P2 — `PostStop` signal over `preStop` lifecycle hook

**Status:** Enforced in migrated actors.

```bash
grep -rn "override def preStop\|override def postStop" src/main/ --include="*.scala" \
  | grep -v "//\|extends Actor"  # Classic actors legitimately use these
```

**Prefer:**
```scala
Behaviors.receiveMessage[Command] { ... }
  .receiveSignal {
    case (ctx, PostStop) =>
      cleanup()
      Behaviors.same
  }
```

---

## P3 — Explicit `replyTo` over `sender()`

**Status:** Enforced in migrated actors. Zero tolerance in new Typed code.

```bash
grep -rn "sender()" src/main/ --include="*.scala" | grep -v "extends Actor\|//\|test"
# Target: 0 hits in Typed actors
```

**Pattern:** Request carries its own reply address.
```scala
case class GetStatus(replyTo: ActorRef[StatusResponse]) extends Command
// Handler:
case GetStatus(replyTo) => replyTo ! StatusResponse(currentStatus)
```

---

## P4 — Named child actors

**Status:** Recommended for new code.

```scala
// ✅ Named — visible in logs, dead letters, actor hierarchy
ctx.spawn(WorkerActor(config), s"worker-${task.id}")

// ❌ Anonymous — invisible in diagnostics
ctx.spawn(WorkerActor(config), "")
```

Name format: `<role>-<discriminator>` where discriminator is stable (task ID, address hash, index).
Do not use random UUIDs — logs become useless.

---

## P5 — AskPattern for request-response across Classic boundary

When a Classic actor needs a response from a Typed actor:

```scala
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
implicit val timeout: Timeout = 5.seconds

val result: Future[Response] =
  typedRef.ask(replyTo => TypedCommand(replyTo))(timeout, system.scheduler)
```

When a Typed actor needs a response from a Classic actor (avoid if possible — prefer migrating the Classic side):
```scala
context.ask(classicRef.toTyped[ClassicCommand])(replyTo => ClassicCommand(replyTo)) {
  case Success(r) => AdaptedResponse(r)
  case Failure(e) => RequestFailed(e)
}
```

---

## P6 — Two-behavior pattern for state machines

Classic `context.become` → return next behavior from message handler.

```scala
def receive(): Behavior[Command] =
  Behaviors.receiveMessage {
    case Start => working()     // transition by returning new behavior
    case other => Behaviors.unhandled
  }

def working(): Behavior[Command] =
  Behaviors.receiveMessage {
    case Complete => receive()  // back to initial
    case other => Behaviors.unhandled
  }
```

PostStop signal attaches to each behavior independently if cleanup differs per state.

---

## P7 — Non-sealed Command ADT when spanning files

When Command cases are defined in a shared `Messages.scala` across package boundaries,
Scala 3 cannot seal the trait. This is by design in the S3 coordinator group.

```scala
// In coordinator companion:
trait Command  // non-sealed — intentional, multi-file constraint

// In Messages.scala (different package):
case class GetProgress(replyTo: ActorRef[ProgressReport]) extends CoordinatorName.Command
```

Document with a comment: `// non-sealed: cases in Messages.scala span package boundary`
Post-CAPSTONE, consolidate into companion objects and seal (Part 7a).

---

## P8 — `Behaviors.setup` for initialization side effects

```scala
// ✅ Side effects in setup, not in apply()
def apply(config: Config): Behavior[Command] =
  Behaviors.setup { ctx =>
    ctx.log.info("Starting with config {}", config)
    val resource = openResource(config)
    new ActorImpl(ctx, resource).receive()
  }

// ❌ Side effects at object construction time
object MyActor {
  val resource = openResource(globalConfig)  // runs at class load, untestable
```

---

## P9 — `context.watchWith` over `context.watch` + `Terminated`

```scala
// ✅ Typed — message carries identity
ctx.watchWith(child, ChildStopped(child.path.name))

// ❌ Classic-style in Typed code
ctx.watch(child)  // then: case Terminated(ref) => ...  (weaker typing)
```

**Migration check — Classic peers that expected `Terminated`:**

When migrating `context.watch` → `context.watchWith`, grep for Classic callers that
relied on receiving `Terminated` from this actor's watched children as a lifecycle signal:

```bash
grep -rn "Terminated" src/main/ --include="*.scala" | grep -i "ActorName\|ChildName"
```

Classic actors watching a peer received `Terminated` automatically via the death watch
system. After migrating to `watchWith`, the Typed actor sends a specific Command
(e.g., `ChildStopped`) instead — `Terminated` is no longer propagated. Any Classic
peer that relied on `Terminated` for lifecycle coordination must be updated or given
an adapter. Add a CHASE-QUEUE entry for each such caller not in scope.

---

## P10 — Internal Commands for Future → actor-state writes

When a Future needs to communicate a decision that mutates actor state, create
dedicated internal Commands to marshal the result back to the actor thread. Do
not share mutable state via `@volatile` or other synchronization — that is the
Classic workaround, not the Typed pattern.

**Pattern (from TNHC `verificationBFSRunning`):**

```scala
// ❌ Classic workaround — @volatile on shared actor state
@volatile var verificationRunning: Boolean = false  // race with Future writes

// ✅ Typed — Future sends a Command, actor writes state on-thread
private case object RestartResumeVerification extends Command
private case object RestartFullRebuild        extends Command

// Before launching Future, capture selfRef:
val selfRef = context.self
crashRecoveryFuture.onComplete {
  case Success(canResume) =>
    if (canResume) selfRef ! RestartResumeVerification
    else           selfRef ! RestartFullRebuild
  case Failure(_) => selfRef ! RestartFullRebuild
}(recoveryEc)

// In behavior — state written only on actor thread:
case RestartResumeVerification =>
  verificationRunning = true   // safe: actor thread, plain var
  resumeVerification()
  Behaviors.same
```

**Pre-migration checklist addition:** Before migrating any Classic actor, grep for
`@volatile` fields. Each site requires internal Command pairs in the Typed version —
one per logical decision the Future can make.

```bash
grep -n "@volatile" src/main/scala/com/chipprbots/ethereum/...ActorFile.scala
```

**CAPSTONE sweep:**
```bash
grep -rn "@volatile" src/main/ --include="*.scala" | grep -v "extends Actor\b"
# Target: 0 hits in Typed actors post-migration
```

---

## P11 — `asyncLog` (plain SLF4J) for off-thread code

`context.log` is only safe on the actor thread. Calling it from inside a Future,
CE IO computation, BFS walk, or worker closure violates Pekko's thread-safety contract
on `ActorContext`. The violation is often silent — code compiles, but tests fail
selectively depending on which code paths trigger the log call and under what thread
scheduling. The error manifests as a runtime exception from inside `ActorContext`,
not a compile error.

**S4 migration bug (io-compute thread variant):** `processNodes` was a `private def`
containing `ctx.log.debug(...)`. The method was invoked from within a CE IO computation
running on `io-compute-2`. Tests for `FullResponse` and `PartialResponse` passed because
those paths never hit the `ctx.log` call. Only the `RequestFailed` branch triggered it,
and only then did the actor context access blow up — making the bug appear specific to one
message type when the root cause was the CE thread boundary.

**Rule:** Any code that executes on a non-actor thread must use `asyncLog`. This includes:
- `Future { }` bodies and `.map`, `.flatMap`, `.recover`, `.onComplete` callbacks
- `IO { }` bodies and any Cats Effect `.flatMap`, `.map`, `.evalMap` chain
- BFS / traversal callbacks passed to a non-actor `ExecutionContext`
- Any `private def` that is called from within any of the above (indirect violation)

The indirect case is the most dangerous: a `private def` using `ctx.log` looks fine at its
definition site but becomes a violation the moment any caller invokes it from a CE fiber or
Future callback. Defaulting `private def` helpers that do logging to `asyncLog` avoids this
entirely.

**Pattern (from TNHC BFS + S4 CE IO fix):**

```scala
// Declare alongside ctx — safe from any thread:
private val asyncLog = LoggerFactory.getLogger(getClass)

// ❌ private def with ctx.log — unsafe if called from CE IO or Future:
private def processNodes(nodes: List[Node]): Unit =
  nodes.foreach { n =>
    ctx.log.debug("Processing node: {}", n.hash)  // blows up on io-compute thread
  }

// ✅ Use asyncLog in any private def that may run off the actor thread:
private def processNodes(nodes: List[Node]): Unit =
  nodes.foreach { n =>
    asyncLog.debug("Processing node: {}", n.hash)  // safe from any thread
  }

// ✅ Actor-thread message handlers: ctx.log is fine here
case StartPhase(pivot) =>
  ctx.log.info("Phase started: pivot={}", pivot)
```

**Sweeps — run during pre-migration checklist and after each migration:**

```bash
# Direct violation: ctx.log inside a CE IO block
grep -rn "IO\s*{" src/main/ --include="*.scala" -A30 | grep "ctx\.log\|context\.log"

# Direct violation: ctx.log inside Future/callback closures
grep -rn "ctx\.log\|context\.log" src/main/ --include="*.scala" -B5 \
  | grep -B5 "Future\|onComplete\|\.map\|\.flatMap\|\.recover"

# Indirect risk: private defs in Typed actor files that use ctx.log
# Manual review required — check if any are called from IO / Future chains
grep -rn "private def" src/main/ --include="*.scala" -A20 \
  | grep "ctx\.log\|context\.log"
```

---

## P12 — `mapMaterializedValue` over `preMaterialize` for subscription setup

**Status:** Enforced after CAPSTONE. `preMaterialize()` in streaming subscription setup is forbidden.

**Root cause (CAPSTONE bug, `bc2a7a2fc`):** `Source.fromMaterializer { (mat, _) => val (ref, src) = Source.actorRef(...).preMaterialize()(mat); ... src }` creates a Reactive Streams Publisher/Subscriber async boundary via `Sink.asPublisher(false)`. Under Pekko 1.6.0, elements pushed by the `actorRef` source don't reach downstream consumers through this boundary. `take(N).runWith(Sink.seq).futureValue` hangs indefinitely.

**Anti-pattern — async boundary, never use for subscription setup:**
```scala
// ❌ preMaterialize() = hidden Publisher/Subscriber boundary = elements silently dropped
def messageSource(pea: TypedActorRef[Command], mc: MessageClassifier): Source[Msg, NotUsed] =
  Source
    .fromMaterializer { (mat, _) =>
      val (actorRef, src) = Source.actorRef[Msg](...).watch(pea.toClassic).preMaterialize()(mat)
      pea ! SubscribeCmd(mc, actorRef)
      src
    }
    .mapMaterializedValue(_ => NotUsed)
```

**Pattern — single graph materialization:**
```scala
// ✅ mapMaterializedValue fires at graph materialization — no async boundary
def messageSource(pea: TypedActorRef[Command], mc: MessageClassifier): Source[Msg, NotUsed] =
  Source
    .actorRef[Msg](PartialFunction.empty, PartialFunction.empty, 64, OverflowStrategy.dropHead)
    .watch(pea.toClassic)
    .mapMaterializedValue { actorRef =>
      pea ! SubscribeCmd(mc, actorRef)
      NotUsed
    }
```

**Sweep:**
```bash
grep -rn "preMaterialize\|fromMaterializer" src/ --include="*.scala"
# Legitimate uses of fromMaterializer: acquiring the Materializer for downstream ops.
# Illegitimate use: calling preMaterialize() inside fromMaterializer to "eagerly" subscribe.
# When in doubt: ask whether a simpler mapMaterializedValue achieves the same result.
```

---

## P13 — Stream test synchronization: syncProbe barrier + `take(N)`

**Status:** Established pattern after CAPSTONE. Do not use `Await.result` + `PoisonPill` for stream termination in tests.

**The problem with PoisonPill termination:**
1. `pea.toClassic ! PoisonPill` sends a system message with priority over regular mailbox messages.
2. Pekko delivers system messages (including `Terminated`) before pending regular messages.
3. Stream actors may have buffered elements that haven't been forwarded downstream when termination arrives.
4. `Await.result(stream, timeout)` succeeds but the seq is incomplete or never arrived.

**The problem with `Await.result` + fixed timeout:**
- Brittle under load (NUC at full testEssential load) — 5s timeout can expire.
- Hides ordering bugs: if elements arrived but the future completed before assertion, test passes incorrectly.

**Pattern — demand-driven self-termination with FIFO barrier:**
```scala
// Subscribe streams FIRST (mapMaterializedValue fires synchronously at runWith):
val stream1 = PeerEventBusActor.messageSource(pea, classifier1).take(1).runWith(Sink.seq)
val stream2 = PeerEventBusActor.messageSource(pea, classifier2).take(2).runWith(Sink.seq)

// FIFO barrier: subscribe syncProbe AFTER stream subscriptions (same test thread).
// When syncProbe.expectMsg confirms receipt, all earlier PEA mailbox messages (the
// stream SubscribeCmds + PublishCmd) have been processed — FIFO guarantee.
val syncProbe = TestProbe()
pea ! SubscribeCmd(classifier2, syncProbe.ref)

pea ! PublishCmd(msg1)
syncProbe.expectMsg(msg1)      // ← barrier: confirms msg1 delivered to all classifier2 subs

pea ! PublishCmd(msg2)
syncProbe.expectMsg(msg2)      // ← barrier: confirms msg2 delivered

// Elements are buffered; Futures complete as soon as take(N) demand is satisfied:
stream1.futureValue shouldEqual Seq(msg1)
stream2.futureValue shouldEqual Seq(msg1, msg2)
```

**Why this works:**
- `take(N).runWith(Sink.seq)` is demand-driven — the Future completes the moment N elements are delivered, with no termination race.
- `syncProbe` subscribed last from the test thread → PEA processes SubscribeCmds in FIFO order → by the time `syncProbe.expectMsg` returns, all stream actors have their elements buffered.
- `ScalaFutures.futureValue` (from `with ScalaFutures with NormalPatience`) is non-timeout-race-sensitive because the elements are already in the buffer.

**NEVER in stream tests:**
```scala
// ❌ PoisonPill races with buffered elements
pea.toClassic ! PoisonPill
Await.result(stream, 5.seconds)  // may be incomplete

// ❌ Fixed sleep before assertion
Thread.sleep(500)
stream.futureValue  // timing-dependent

// ❌ Await without a synchronization barrier
Await.result(stream, 5.seconds)  // may time out under load
```

---

## Test-kit pitfalls (discovered §8a-retro batch 4, 2026-06-23)

**P14 — Missing `application-test.conf` causes `ConfigurationException` with bare ctor**

`ScalaTestWithActorTestKit()` (bare ctor) loads `application-test.conf` from the classpath. If it doesn't exist, Pekko falls back to jar-bundled `reference.conf` only — which does **not** include `src/test/resources/application.conf`. Any actor spawned with `withDispatcherFromConfig("sync-dispatcher")` throws `ConfigurationException: Dispatcher [sync-dispatcher] not configured`.

**Proper fix:** Create `src/test/resources/application-test.conf` (§8a-infra backlog item):
```hocon
include "application.conf"
pekko.actor.default-dispatcher.throughput = 1
```

**Fixed in `8b9bef67d` (2026-06-23):** `src/test/resources/application-test.conf` created; `ConfigFactory.load()` stripped from 25 specs. Bare ctor now works.

---

**P15 — `testKit.stop` is a silent no-op for classic-resolved worker children**

`testKit.stop(ref)` only terminates actors returned by `testKit.spawn(...)`. Coordinator workers are spawned internally via classic `context.actorOf` and resolved by tests via `actorSelection` — these hold classic `ActorRef` values. Calling `testKit.stop` on them does nothing, leaving workers running between tests.

**Proper fix:** Audit affected specs and add `testKit.system.classicSystem.stop(workerRef)` in teardown for classic-resolved refs, OR verify the coordinator's own `PostStop` handler stops workers (§8a-infra-b backlog item).

---

## Anti-patterns — flag in PRISM review

| Pattern | Problem | Correct |
|---------|---------|---------|
| `sender()` in Typed actor | Not available — compile error | Explicit `replyTo` in Command |
| `Behavior[Any]` in permanent code | Defeats type safety | Typed Command ADT |
| `.toClassic` outside migration scaffolding | Classic leak post-CAPSTONE | Full migration |
| `context.system.scheduler` in Typed actor | Manual lifecycle | `withTimers` |
| Unnamed child actors | Invisible in diagnostics | Named with stable discriminator |
| `preStop` / `postStop` override in Typed | Classic API | `PostStop` signal |
| `ScalaTestWithActorTestKit()` bare ctor without `application-test.conf` | `sync-dispatcher` not found → `ConfigurationException` | Create `application-test.conf` (P14 / §8a-infra) |
| `testKit.stop(classicWorkerRef)` | Silent no-op; worker resource leak | `testKit.system.classicSystem.stop(workerRef)` (P15 / §8a-infra-b) |

---

## P16 — Constructor params must use `ActorRef[T]`, not Classic `ActorRef`

**Status:** Enforced in all migrated actors. The single most common source of spawn-site `.toClassic` slippage.

When a Typed actor is spawned by another Typed actor, every constructor param that holds a ref to another
actor must use `typed.ActorRef[T]`, not Classic `ActorRef`. A Classic param forces the spawning caller to
write `typedRef.toClassic` at the spawn site — the bridge is invisible to the type checker but real tech debt.

```bash
# Find Typed actors with Classic ActorRef constructor params (grep the constructor line)
grep -rn "class.*\(.*ActorRef\b" src/main/ --include="*.scala" \
  | grep -v "typed\.ActorRef\|ActorRef\[" \
  | grep -v "//.*ActorRef"
# Target: 0 hits in fully-migrated Typed actors
```

**During a LOOM migration** (pre-CAPSTONE): updating constructor params is MANDATORY as part of the
migration commit — not deferred. For each Classic `ActorRef` param:
1. Change the param type to `ActorRef[T]` where T is the most specific message type the actor sends to it.
   Use `ActorRef[Any]` only when the actor sends multiple unrelated types through a message adapter.
2. Update internal usages — the `!` operator works identically on Classic and Typed refs; type changes only.
3. Update all spawn sites to drop `.toClassic` at the call site.
4. If a spawn site is in a file out of scope for this LOOM session, add a CHASE-QUEUE entry (type: CLASSIC).

**Do NOT close a LOOM migration with Classic `ActorRef` params remaining.** If a param truly cannot be
updated yet (e.g., because a shared command ADT is not owned by this actor), add the CHASE-QUEUE entry
and document the gate condition.

**Prefer:**
```scala
// Typed actor with typed refs — callers spawn cleanly, no .toClassic needed
class FastSync(
  syncController: ActorRef[SyncController.Command],  // ✅ typed
  peerManager: ActorRef[PeerManagerActor.Command],   // ✅ typed
  ...
)
```

**Avoid:**
```scala
// Classic params survive migration — force callers to write .toClassic forever
class FastSync(
  syncController: ActorRef,  // ❌ Classic — every spawn site adds .toClassic
  peerManager: ActorRef,     // ❌ Classic
  ...
)
```

---

---

## P17 — `messageAdapter` in `Behaviors.setup` only, never inside receive

**Status:** Violation confirmed — `PeersClient.scala:224` creates a new adapter per `RequestPeer` message.

Pekko docs: "one adapter per message class, last registration wins." Creating an adapter inside a receive handler silently replaces the previous one on every message. With multiple in-flight requests, the second registration discards the first adapter — responses for request A get misrouted to request B's handler.

```bash
grep -rn "messageAdapter\[" src/main/ --include="*.scala"
# All hits must be inside Behaviors.setup blocks, not case branches
```

When per-request reply routing is needed: use `context.ask` (designed for one-shot request/response, safe under concurrent in-flight calls) or embed a correlation ID into the result type and use a single setup-level adapter.

---

## P18 — `spawnAnonymous` only for truly identity-free workers; prefer named

**Status:** 6 violations — `ByteCodeCoordinator`, `AccountRangeCoordinator`, `SyncStateSchedulerActor`, `BlockImporter`, `FastSyncBranchResolverActor`.

`spawnAnonymous` is correct only when a worker has no stable identity and no parent needs to correlate its termination to specific work. Use a named actor with a discriminator when the parent `watchWith`'s the child or needs traceable log output.

```scala
// ✅ Named with stable counter
private var n = 0
def spawnWorker(): ActorRef[WorkerCmd] =
  context.spawn(Worker(config), s"bytecode-worker-${n += 1; n}")
```

```bash
grep -rn "spawnAnonymous" src/main/ --include="*.scala"
# Review each: does the parent watchWith it? If yes, name it.
```

---

## P19 — `PreRestart` signal handler required when `Behaviors.supervise` is present

**Status:** Zero `PreRestart` handlers in the codebase. Two supervised actors exist (`FastSync`, `FaucetSupervisor`).

`PreRestart` fires before the new behavior instance starts, giving the crashing behavior one chance to release resources (subscriptions, open connections, messageAdapter refs). `PostStop` fires on permanent termination only. An actor under `restartWithBackoff` that opens a subscription in `Behaviors.setup` leaks one subscription per crash if `PreRestart` does not unsubscribe.

```bash
grep -rn "Behaviors\.supervise\|\.onFailure\[" src/main/ --include="*.scala" -l
# Each file must also contain PreRestart:
grep -rn "PreRestart" src/main/ --include="*.scala"
```

---

## P20 — `Behaviors.supervise` restart strategy must include `.withLimit` or backoff

**Status:** `FastSync.scala:425` uses unlimited `SupervisorStrategy.restart` — no bound.

Unlimited restart causes infinite loops on persistent failures (codec bug, bad peer message). For I/O-bound actors prefer `restartWithBackoff`; for local workers use `.withLimit`.

```scala
// ✅ Bounded backoff — operational visibility via withCriticalLogLevel
Behaviors.supervise(behavior).onFailure[Exception](
  SupervisorStrategy
    .restartWithBackoff(100.millis, 10.seconds, randomFactor = 0.2)
    .withMaxRestarts(10)
    .withCriticalLogLevel(Level.ERROR, afterErrors = 3)
)
```

Exception: `SupervisorStrategy.stop` on a domain exception that means the actor must not continue — no limit needed.

```bash
grep -rn "SupervisorStrategy\.restart\b" src/main/ --include="*.scala" \
  | grep -v "withLimit\|restartWithBackoff\|WithLimit"
# Target: 0 hits
```

---

## P21 — `ChildFailed` signal to capture crash cause from direct children

**Status:** Zero uses in the codebase.

`ChildFailed <: Terminated` is emitted when a **direct unsupervised child** crashes. It carries the `cause: Throwable`. Use `watch(child)` + `ChildFailed` match in `receiveSignal` when the coordinator needs to distinguish crash from clean stop and log the exception.

**Important:** `ChildFailed` only arrives for direct children without a `Behaviors.supervise` wrapper. If the child has a supervisor, the parent sees `Terminated` after max-restarts is exceeded.

```bash
grep -rn "watchWith\|Terminated\b" src/main/ --include="*.scala" -l
# Review each: would knowing the crash cause aid debugging? If yes, use watch + ChildFailed.
```

---

## P22 — `Behaviors.withMdc` for per-actor structured logging context

**Status:** Not used. All MDC is manual (`Logger.scala:22–24`) — unsafe on actor threads.

`Behaviors.withMdc` injects MDC automatically before each message and clears it after. It integrates with `context.log` (actor-thread-safe). Manual `MDC.put`/`MDC.clear` pairs called from actor code are not thread-safe and silently produce incorrect MDC under load.

```scala
def apply(peerId: PeerId): Behavior[Command] =
  Behaviors.withMdc[Command](
    staticMdc = Map("peerId" -> peerId.value, "chainId" -> "61")
  )(Behaviors.setup { ctx => ... })
```

Highest-value targets: `PeerActor`, `PeerRequestHandler`, `SNAPSyncController` — adding `peerId` as static MDC makes peer-session log correlation trivial.

```bash
grep -rn "org\.slf4j\.MDC\|MDC\.put\|MDC\.clear" src/main/ --include="*.scala"
# Target: 0 hits in Typed actors — replace with Behaviors.withMdc
```

---

## P23 — `ManualTime` for all tests that exercise `withTimers` behavior

**Status:** Partially enforced — `PeerRequestHandlerSpec`, `FilterManagerSpec` use it. `SNAPSyncController`, `BlockchainHostActor` tests do not.

Tests using real wall-clock time for timer assertions are flaky on the NUC under `testEssential` load (24 min, high CPU contention). `ManualTime` provides deterministic timer control.

```bash
grep -rn "withTimers\|startTimerWithFixedDelay\|startSingleTimer" src/test/ --include="*.scala" -l \
  | xargs grep -L "ManualTime"
# Target: 0 hits — all timer specs must use ManualTime
```

---

## P24 — `LoggingTestKit` assertions for supervised actor crash paths

**Status:** Only `PoWMiningCoordinatorSpec` uses it. `FastSync` and `FaucetSupervisor` have no log-level tests.

`LoggingTestKit` verifies log events are emitted at the expected level. Use it to assert ERROR logging on crash, WARN on recovery, and absence of unexpected errors in the happy path.

```bash
grep -rn "Behaviors\.supervise\|onFailure\[" src/main/ --include="*.scala" -l \
  | while read f; do
      base=$(basename "$f" .scala)
      grep -rn "LoggingTestKit" src/test/ --include="*${base}Spec*" &>/dev/null \
        || echo "MISSING: $base"
    done
# Target: each supervised actor spec contains at least one LoggingTestKit assertion
```

---

## P25 — `ActorRef.narrow` for interface segregation over `messageAdapter`

**Status:** Used once (`RegularSync.scala:73`). Should be standard for coordinators exposing sub-protocols to children.

`narrow[U]` (where `U <: T`) is a zero-cost cast — no wrapping, no new ActorRef. Prefer it over `messageAdapter` when the child's reply type is already a subtype of the coordinator's Command. Use `messageAdapter` only when transformation is needed.

```bash
grep -rn "messageAdapter\[" src/main/ --include="*.scala"
# For each: is the mapped type a subtype of Command? If yes, prefer .narrow[U] instead.
grep -rn "\.narrow\[" src/main/ --include="*.scala"
# Expect: one per coordinator actor that exposes a sub-protocol to children
```

---

## TL1 — `IORuntime.global` only at the composition root (`NodeApp`)

**Status:** 26 actor files use `given IORuntime = IORuntime.global`. Only `DiscoveryServiceBuilder` correctly accepts `IORuntime` as a constructor parameter.

Each actor reaching for the global runtime independently makes coordinated shutdown metrics impossible and creates hidden coupling. Wire `IORuntime` once at `NodeApp` and pass it down as a constructor implicit.

```bash
grep -rn "IORuntime\.global" src/main/ --include="*.scala" | grep -v "NodeApp\|DiscoveryService"
# Target: 0 hits — all non-root uses should accept IORuntime as a constructor param
```

---

## TL2 — `unsafeRunSync` only at composition root and test code

**Status:** 4 actionable violations in production storage and RPC code.

`unsafeRunSync` blocks the calling thread — in actor message handlers this starves the dispatcher; in storage methods called from actor handlers it deadlocks under backpressure. Known violations:
- `HealingFrontierStorage.scala:43`
- `PathNodeStorage.scala:146, 156`
- `JsonRpcIpcServer.scala:104`

Fix: actor callers use `pipeToSelf`; IPC server uses `Dispatcher.sequential` scoped to a `Resource`.

```bash
grep -rn "unsafeRunSync" src/main/ --include="*.scala" | grep -v "//\|Benchmark\|NodeApp\|Main"
# Each hit is a production thread-blocking risk — investigate before accepting
```

---

## CAPSTONE cleanup targets

After all actors are Typed, sweep for:
```bash
grep -rn "\.toClassic\b" src/main/ --include="*.scala"        # remove adapters
grep -rn "PropsAdapter\b" src/main/ --include="*.scala"       # remove adapters
grep -rn "Behavior\[Any\]" src/main/ --include="*.scala"      # narrow to real type
grep -rn "ActorSystem\b" src/main/ --include="*.scala"        # flip to ActorSystem[Nothing]
grep -rn "@volatile" src/main/ --include="*.scala"            # should be 0 in Typed actors (P10)
grep -rn "IO\s*{" src/main/ --include="*.scala" -A30 \
  | grep "ctx\.log\|context\.log"                             # P11: CE IO thread violation
grep -rn "ctx\.log\|context\.log" src/main/ --include="*.scala" -B5 \
  | grep -B5 "Future\|onComplete\|\.map\|flatMap\|\.recover"  # P11: Future thread violation
```
