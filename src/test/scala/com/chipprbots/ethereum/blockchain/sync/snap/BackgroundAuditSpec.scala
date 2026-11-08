package com.chipprbots.ethereum.blockchain.sync.snap

import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import org.apache.pekko.actor.testkit.typed.scaladsl.ManualTime
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.slf4j.LoggerFactory

import com.chipprbots.ethereum.blockchain.sync.BackgroundAudit
import com.chipprbots.ethereum.blockchain.sync.PeersClient
import com.chipprbots.ethereum.blockchain.sync.TestSyncConfig
import com.chipprbots.ethereum.blockchain.sync.regular.BlockFetcher.FetchedStateNode
import com.chipprbots.ethereum.blockchain.sync.regular.StateNodeFetcher
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditBadEntries
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditCounters
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditCursors
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditHead
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditPosition
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditReader
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditTestState
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditTestState.*
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditThrottle
import com.chipprbots.ethereum.blockchain.sync.snap.heal.AuditWalk
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.AppStateStorage
import com.chipprbots.ethereum.db.storage.BlockAccessListStorage
import com.chipprbots.ethereum.db.storage.BlockBodiesStorage
import com.chipprbots.ethereum.db.storage.BlockHeadersStorage
import com.chipprbots.ethereum.db.storage.BlockNumberMappingStorage
import com.chipprbots.ethereum.db.storage.ChainWeightStorage
import com.chipprbots.ethereum.db.storage.RawNodeRead
import com.chipprbots.ethereum.db.storage.ReceiptStorage
import com.chipprbots.ethereum.db.storage.ReferenceCountNodeStorage.RepairedNodeReferences
import com.chipprbots.ethereum.db.storage.StateStorage
import com.chipprbots.ethereum.db.storage.StoredNodeTestAccess
import com.chipprbots.ethereum.db.storage.pruning.InMemoryPruning
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.ChainWeight
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.defaultByteArraySerializable
import com.chipprbots.ethereum.mpt.byteStringSerializer
import com.chipprbots.ethereum.network.NetworkMetrics
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.PeerInfo
import com.chipprbots.ethereum.network.NetworkPeerManagerActor.RemoteStatus
import com.chipprbots.ethereum.network.Peer
import com.chipprbots.ethereum.network.PeerActor
import com.chipprbots.ethereum.network.PeerEventBusActor
import com.chipprbots.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import com.chipprbots.ethereum.network.PeerEventBusActor.PublishCmd
import com.chipprbots.ethereum.network.PeerId
import com.chipprbots.ethereum.network.p2p.messages.Capability
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.NodeData
import com.chipprbots.ethereum.network.p2p.messages.SNAP.GetTrieNodes
import com.chipprbots.ethereum.network.p2p.messages.SNAP.TrieNodes
import com.chipprbots.ethereum.testing.Tags.*

/** The background audit as an actor (spec 022 T049, T053, decisions 1, 2, 6-10, 14, 15). A real basic-pruning store
  * over an in-memory source, timers on a manual clock, and reads inline, with a real fetcher only where the wire
  * matters. Elsewhere a fetch lands on a probe, and a spec says what the peers answered.
  */
class BackgroundAuditSpec
    extends ScalaTestWithActorTestKit(
      ManualTime.config.withFallback(
        com.typesafe.config.ConfigFactory
          .parseString("sync-dispatcher { type = Dispatcher, executor = default-executor }")
      )
    )
    with AnyFlatSpecLike
    with Matchers
    with Eventually
    with TestSyncConfig:

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(10, Millis))

  private val manualTime: ManualTime = ManualTime()

  private val instant = BackgroundAudit.Settings(readFactor = 0)
  private val backoff: FiniteDuration = instant.passBackoff

  /** One SNAP-finished node, with pivot 5 unless a spec says otherwise, and everything the actor takes. */
  private class Node(pivot: Option[Int] = Some(5)):
    val s = new AuditTestState
    val now = new AtomicLong(1_000_000_000_000L)
    val fetches: TestProbe[StateNodeFetcher.FetchStateNode] = createTestProbe[StateNodeFetcher.FetchStateNode]()

    pivot.foreach(p => s.app.putSnapSyncPivotBlock(p).commit())
    s.app.snapSyncDone().commit()

    private val fakeFetcher: BackgroundAudit.FetcherFactory = ctx =>
      ctx.spawn(
        Behaviors.receiveMessage[StateNodeFetcher.StateNodeFetcherCommand] {
          case fetch: StateNodeFetcher.FetchStateNode =>
            fetches.ref ! fetch
            Behaviors.same
          case _ => Behaviors.same
        },
        "fake-fetcher"
      )

    def nowSeconds: Long = now.get / 1000

    def head(number: Int, root: ByteString): AuditHead = s.setHead(number, root, nowSeconds)

    def spawn(
        settings: BackgroundAudit.Settings = instant,
        reader: AuditReader = s.reader,
        fetcher: BackgroundAudit.FetcherFactory = fakeFetcher,
        ec: ExecutionContext = ExecutionContext.parasitic,
        nanoTime: () => Long = () => System.nanoTime()
    ): ActorRef[BackgroundAudit.Command] =
      testKit.spawn(
        BackgroundAudit(
          BackgroundAudit
            .Deps(s.app, s.progress, s.state, s.code, reader, fetcher, () => now.get, Some(ec), settings, nanoTime)
        )
      )

    def status(audit: ActorRef[BackgroundAudit.Command]): BackgroundAudit.Status =
      val probe = createTestProbe[BackgroundAudit.Status]()
      audit ! BackgroundAudit.GetStatus(probe.ref)
      probe.receiveMessage()

    def ended(audit: ActorRef[BackgroundAudit.Command]): Unit =
      createTestProbe().expectTerminated(audit)

    private var lastEnded: Long = 0L

    /** The pass has ended and the next waits out its backoff. */
    def awaitBackoff(audit: ActorRef[BackgroundAudit.Command]): BackgroundAudit.Status =
      val st = eventually {
        val st = status(audit)
        st.waitingForNextPass shouldBe true
        st
      }
      lastEnded = st.pass
      st

    /** That backoff goes by, on the clock the actor reads and the one its timers run on, and the head stays fresh. */
    def nextPass(): Unit =
      val wait = AuditThrottle.passBackoff(backoff, lastEnded)
      now.addAndGet(wait.toMillis)
      s.bestHead = s.bestHead.map(_.copy(timestampSeconds = nowSeconds))
      manualTime.timePasses(wait)

    def owes: Boolean = s.app.isSnapAuditOwed()

    def book: AuditBadEntries =
      s.progress.readAuditBadEntries().flatMap(AuditBadEntries.decode).getOrElse(AuditBadEntries())

    /** `passes` exhausts on record for `hash`, the last long enough ago to count another. */
    def onRecord(hash: ByteString, passes: Int): Unit =
      val entry = AuditBadEntries.Entry(passes, lastPass = 0L, lastMillis = 0L)
      s.progress.writeAuditBadEntries(AuditBadEntries(s.app.snapAuditAnchor(), 0L, Map(hash -> entry)).encode)

  private def serve(fetch: StateNodeFetcher.FetchStateNode, bytes: Array[Byte]): Unit =
    fetch.originalSender ! FetchedStateNode(NodeData(Seq(ByteString(bytes))))

  private def exhaust(fetch: StateNodeFetcher.FetchStateNode): Unit =
    fetch.originalSender ! FetchedStateNode(NodeData(Seq.empty))

  /** A state of a few accounts, one holding a storage trie, with a storage node well below its root. */
  private def populate(n: Node): ByteString =
    val storage = n.s.update(None, 0, (0 until 30).map(j => trieKey(1000 + j) -> longValue(j)))
    n.s.update(None, 0, (0 until 20).map(i => trieKey(i) -> accountBytes(if i == 3 then Some(storage) else None)))

  private def deepStorageNode(n: Node, root: ByteString): (AuditPosition, ByteString) =
    n.s.allNodes(root).filter((position, _) => position.isStorage && position.nibbles.length >= 2).last

  private def unwrappedBytes(n: Node, hash: ByteString): Array[Byte] =
    n.s.reader.nodes(Seq(hash)).head.asInstanceOf[RawNodeRead.Bytes].encoded

  /** What the audit's logger says while `f` runs. */
  private def logsOf[T](f: => T): (T, Seq[ILoggingEvent]) =
    val logger = LoggerFactory.getLogger(BackgroundAudit.LoggerName).asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    val before = logger.getLevel
    logger.setLevel(Level.INFO)
    logger.addAppender(appender)
    try
      val result = f
      (result, appender.list.toArray(Array.empty[ILoggingEvent]).toSeq)
    finally
      logger.detachAppender(appender)
      logger.setLevel(before)

  private def lines(events: Seq[ILoggingEvent], level: Level): Seq[String] =
    events.filter(_.getLevel == level).map(_.getFormattedMessage)

  // ---- T049 -----------------------------------------------------------------------------------------------------------

  /** A peers client that hands each request to a probe, for a spec to answer as a peer would. */
  private class Wire:
    val requests: TestProbe[PeersClient.Request[?]] = createTestProbe[PeersClient.Request[?]]()
    val client: ActorRef[PeersClient.Command] = testKit.spawn(Behaviors.receiveMessage[PeersClient.Command] {
      case request: PeersClient.Request[?] =>
        requests.ref ! request
        Behaviors.same
      case _ => Behaviors.same
    })
    val peer: Peer = Peer(
      PeerId("t049-peer"),
      new InetSocketAddress("127.0.0.1", 30303),
      createTestProbe[PeerActor.Command]().ref,
      incomingConnection = false
    )
    val fetcher: BackgroundAudit.FetcherFactory = ctx =>
      ctx.spawn(StateNodeFetcher(client, syncConfig, ctx.system.ignoreRef), "audit-state-node-fetcher")

  "The background audit" should "find an injected hole, fetch it by position at its root, log it, repair it, and leave import going" taggedAs UnitTest in {
    val n = new Node
    val wire = new Wire
    val root = populate(n)
    val (position, hash) = deepStorageNode(n, root)
    val bytes = unwrappedBytes(n, hash)
    n.s.delete(hash)
    n.head(10, root)

    val (audit, found) = logsOf {
      val audit = n.spawn(fetcher = wire.fetcher)
      val request = wire.requests.receiveMessage()
      val asked = request.message.asInstanceOf[GetTrieNodes]
      withClue("pinned to the root it was resolved under, by position: ") {
        asked.rootHash shouldBe root
        asked.paths shouldBe Seq(position.pathset)
      }

      // The fetch hangs on a peer. Import commits the next block, through the lock a repair takes, and the walk goes on.
      val staged = n.s.state.stageBlock(11)
      val nextRoot = ByteString(
        MerklePatriciaTrie[ByteString, Array[Byte]](root.toArray, staged.storage)
          .put(trieKey(7), accountBytes(nonce = 11))
          .getRootHash
      )
      staged.commitWith(11, ByteString(Array.fill[Byte](32)(11)), None)
      n.head(11, nextRoot)
      val midway = eventually {
        val st = n.status(audit)
        st.stack shouldBe 0
        st
      }
      midway.fetching shouldBe Some(hash)
      midway.counters.holes shouldBe 1
      n.s.nodes.get(hash) shouldBe None

      val reply = TrieNodes(asked.requestId, Seq(ByteString(bytes)))
      request.replyTo ! PeersClient.Response(wire.peer, reply)

      n.awaitBackoff(audit)
      audit
    }

    withClue("FR-013, each hole at WARN with its position: ") {
      val warned = lines(found, Level.WARN).find(_.contains(position.describe)).get
      warned should include(AuditWalk.hexOf(hash))
      warned should include("#1692")
    }
    val stored = n.s.nodes.get(hash).get
    StoredNodeTestAccess.references(stored) shouldBe RepairedNodeReferences
    unwrappedBytes(n, hash).toSeq shouldBe bytes.toSeq
    // Import's exhaust count is out of the audit's reach: it belongs to a regular-sync session, not a global (#1705).

    withClue("a pass that repaired is not clean, and another comes after the backoff: ") {
      n.s.app.getSnapAuditCleanAnchor() shouldBe None
      n.nextPass()
      n.ended(audit)
      n.s.app.getSnapAuditCleanAnchor() shouldBe Some("5")
    }
  }

  it should "end a clean pass by recording its anchor and both recovery flags, and stop" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    n.head(10, root)
    val (_, said) = logsOf {
      val audit = n.spawn()
      n.ended(audit)
    }
    n.s.app.getSnapAuditCleanAnchor() shouldBe Some("5")
    n.s.app.isBytecodeRecoveryDone() shouldBe true
    n.s.app.isStorageRecoveryDone() shouldBe true
    n.owes shouldBe false
    n.s.progress.readAuditCursors() shouldBe None
    lines(said, Level.INFO).exists(_.contains("started")) shouldBe true
    lines(said, Level.INFO).exists(l => l.contains("ended clean") && l.contains("nodes=")) shouldBe true
    lines(said, Level.WARN) shouldBe empty
    n.fetches.expectNoMessage(50.millis)
  }

  it should "owe exactly one more pass to a datadir that already holds a clean anchor (#1692)" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    n.head(10, root)
    n.ended(n.spawn())
    n.s.app.getSnapAuditCleanAnchor() shouldBe Some("5")
    n.owes shouldBe false
    withClue("the record a finished pass leaves, which the forced one must not resume from: ") {
      n.s.progress.readAuditBadEntries() shouldBe defined
    }

    // The first start of a binary with the fix: one synced write takes the anchor, the audit's record and its place.
    n.s.app.forceAuditPassOnce() shouldBe true
    n.s.app.getSnapAuditCleanAnchor() shouldBe None
    n.s.progress.readAuditBadEntries() shouldBe None
    n.s.progress.readAuditCursors() shouldBe None
    n.owes shouldBe true
    BackgroundAudit.gate(enabled = true, n.s.state, n.s.app) shouldBe BackgroundAudit.Gate.Run

    // It runs a full pass and ends clean.
    n.ended(n.spawn())
    n.s.app.getSnapAuditCleanAnchor() shouldBe Some("5")
    n.owes shouldBe false

    withClue("and a later start owes nothing more: ") {
      n.s.app.forceAuditPassOnce() shouldBe false
      n.s.app.getSnapAuditCleanAnchor() shouldBe Some("5")
      n.owes shouldBe false
      BackgroundAudit.gate(enabled = true, n.s.state, n.s.app) shouldBe BackgroundAudit.Gate.NotOwed
    }
  }

  it should "not owe a pass to a datadir that never finished SNAP when the key is set" taggedAs UnitTest in {
    val n = new Node
    n.s.app.clearSnapSyncDone().commit()
    n.s.app.forceAuditPassOnce() shouldBe true
    n.owes shouldBe false
    n.s.app.forceAuditPassOnce() shouldBe false
  }

  it should "say where it has got to, in the numbers an operator needs" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    val (_, hash) = deepStorageNode(n, root)
    n.s.delete(hash)
    n.head(10, root)
    val (_, said) = logsOf {
      val audit = n.spawn()
      n.fetches.receiveMessage()
      eventually(n.status(audit).stack shouldBe 0)
      manualTime.timePasses(instant.progressEvery)
      n.status(audit) // the tick went into the mailbox as the time passed, so it has been handled when this answers
    }
    val progress = lines(said, Level.INFO).find(_.contains("Progress")).get
    Seq("nodes=", "accounts=", "moved=", "holes=1", "repaired=", "unresolved=", "badNodes=", "elapsed=", "stack=")
      .foreach(field => progress should include(field))
  }

  // ---- decision 14: what a pass pays -----------------------------------------------------------------------------------

  it should "set both recovery flags at the end of a pass that was not clean, with the anchor unmoved" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    val (_, hash) = deepStorageNode(n, root)
    n.s.delete(hash)
    n.head(10, root)
    val audit = n.spawn()
    exhaust(n.fetches.receiveMessage())
    n.awaitBackoff(audit)
    n.s.app.isBytecodeRecoveryDone() shouldBe true
    n.s.app.isStorageRecoveryDone() shouldBe true
    n.s.app.getSnapAuditCleanAnchor() shouldBe None
    withClue("the anchor still owes another pass: ")(n.owes shouldBe true)
  }

  it should "set neither flag when a pass is stopped part way" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    val (_, hash) = deepStorageNode(n, root)
    n.s.delete(hash)
    n.head(10, root)
    val audit = n.spawn()
    n.fetches.receiveMessage()
    testKit.stop(audit)
    n.s.app.isBytecodeRecoveryDone() shouldBe false
    n.s.app.isStorageRecoveryDone() shouldBe false
    n.s.app.getSnapAuditCleanAnchor() shouldBe None
    n.owes shouldBe true
  }

  // ---- what a pass found ----------------------------------------------------------------------------------------------

  it should "overwrite a node that fails its hash when a peer serves it, and walk what is below it" taggedAs UnitTest in {
    val n = new Node
    val under = n.s.put(storageLeaf(1))
    val inner = branch(2 -> under)
    val hash = n.s.put(inner)
    val root = n.s.put(branch(3 -> hash))
    n.s.corrupt(hash, storageLeaf(9).encode)
    n.head(10, root)
    val audit = n.spawn()

    val fetch = n.fetches.receiveMessage()
    fetch.hash shouldBe hash
    fetch.stateRoot shouldBe Some(root)
    serve(fetch, inner.encode)

    val st = n.awaitBackoff(audit)
    st.counters.badNodes shouldBe 1
    st.counters.repaired shouldBe 1
    withClue("root, the repaired node, and the leaf below it: ")(st.counters.nodes shouldBe 3)
    unwrappedBytes(n, hash).toSeq shouldBe inner.encode.toSeq
  }

  it should "overwrite an entry that does not unwrap when a peer serves it" taggedAs UnitTest in {
    val n = new Node
    val leaf = storageLeaf(1)
    val hash = n.s.put(leaf)
    val root = n.s.put(branch(3 -> hash))
    n.s.garble(hash)
    n.head(10, root)
    val audit = n.spawn()
    serve(n.fetches.receiveMessage(), leaf.encode)
    n.awaitBackoff(audit).counters.repaired shouldBe 1
    unwrappedBytes(n, hash).toSeq shouldBe leaf.encode.toSeq
  }

  it should "keep a pass not clean when no peer serves a bad node, trust nothing below it, and say so once" taggedAs UnitTest in {
    val n = new Node
    val under = ByteString(kec256(Array[Byte](1, 2, 3)))
    val first = n.s.put(branch(1 -> under))
    val second = n.s.put(branch(2 -> under))
    val root = n.s.put(branch(3 -> first, 4 -> second))
    n.s.corrupt(first, storageLeaf(8).encode)
    n.s.corrupt(second, storageLeaf(9).encode)
    n.head(10, root)
    val (st, said) = logsOf {
      val audit = n.spawn()
      exhaust(n.fetches.receiveMessage())
      exhaust(n.fetches.receiveMessage())
      n.awaitBackoff(audit)
    }
    st.counters.badNodes shouldBe 2
    st.counters.unresolved shouldBe 2
    st.counters.holes shouldBe 0
    st.counters.clean shouldBe false
    n.s.app.getSnapAuditCleanAnchor() shouldBe None
    withClue("the first of each pass goes to ERROR: ") {
      lines(said, Level.ERROR).count(_.contains("could not serve")) shouldBe 1
    }
  }

  it should "cut a node that hashes right and decodes to nothing at one repair per pass" taggedAs UnitTest in {
    val n = new Node
    val notANode = Array[Byte](0xc1.toByte, 0x80.toByte)
    val hash = ByteString(kec256(notANode))
    n.s.nodes.put(hash, StoredNodeTestAccess.wrapped(notANode, 1))
    val root = n.s.put(branch(3 -> hash))
    n.head(10, root)
    val audit = n.spawn()
    serve(n.fetches.receiveMessage(), notANode)
    val st = n.awaitBackoff(audit)
    st.counters.unfollowable shouldBe 1
    n.fetches.expectNoMessage(50.millis)
    n.s.nodes.get(hash) shouldBe defined
  }

  it should "fetch absent code by hash, and write it to the code store alone" taggedAs UnitTest in {
    val n = new Node
    val code = ByteString(Array.fill[Byte](64)(0x60))
    val codeHash = ByteString(kec256(code.toArray))
    val leaf = n.s.put(accountLeaf(1, accountBytes(codeHash = Some(codeHash))))
    val root = n.s.put(branch(1 -> leaf))
    n.head(10, root)
    val audit = n.spawn()

    val fetch = n.fetches.receiveMessage()
    fetch.hash shouldBe codeHash
    fetch.isByteCode shouldBe true
    serve(fetch, code.toArray)

    n.awaitBackoff(audit).counters.repaired shouldBe 1
    n.s.code.get(codeHash) shouldBe Some(code)
    n.s.nodes.get(codeHash) shouldBe None
  }

  it should "count the holes past its bound, leave them to the next pass, and not end clean" taggedAs UnitTest in {
    val n = new Node
    val absent = (1 to 3).map(i => ByteString(kec256(Array(i.toByte))))
    val root = n.s.put(branch(1 -> absent(0), 2 -> absent(1), 3 -> absent(2)))
    n.head(10, root)
    val audit = n.spawn(settings = instant.copy(maxQueuedHoles = 1))
    exhaust(n.fetches.receiveMessage())
    val st = n.awaitBackoff(audit)
    st.counters.holes shouldBe 3
    st.counters.droppedAtCap shouldBe 2
    st.counters.clean shouldBe false
  }

  /** Holes at slots 1 and 2 of a root, found at block 10. The first's fetch hangs on a peer, and the walk goes on to
    * slot 3, whose read moves the best block to `moved`, so slot 4's batch tells the actor the head has moved on.
    */
  private def holesThatWait(n: Node, moved: (ByteString, ByteString) => ByteString) =
    val first = ByteString(kec256(Array[Byte](1)))
    val second = ByteString(kec256(Array[Byte](2)))
    val third = n.s.put(storageLeaf(3))
    val fourth = n.s.put(storageLeaf(4))
    val rootA = n.s.put(branch(1 -> first, 2 -> second, 3 -> third, 4 -> fourth))
    n.head(10, rootA)
    val rootB = moved(first, second)
    val reader = new AuditReader:
      def nodes(hashes: Seq[ByteString]): Seq[RawNodeRead] =
        if hashes.contains(third) then n.head(50, rootB)
        n.s.reader.nodes(hashes)
      def codes(hashes: Seq[ByteString]): Seq[Option[ByteString]] = n.s.reader.codes(hashes)
      def head(): Option[AuditHead] = n.s.reader.head()
    val audit = n.spawn(settings = instant.copy(batchSize = 1), reader = reader)
    val fetch = n.fetches.receiveMessage()
    fetch.hash shouldBe first
    eventually {
      val st = n.status(audit)
      st.stack shouldBe 0
      st.reading shouldBe false
    }
    (audit, fetch, rootB)

  it should "look at a hole again before its turn, when its root has gone stale: gone is dropped" taggedAs UnitTest in {
    val n = new Node
    val present = n.s.put(storageLeaf(7))
    val (audit, fetch, _) = holesThatWait(n, (first, _) => n.s.put(branch(1 -> first, 2 -> present)))
    exhaust(fetch)
    val st = n.awaitBackoff(audit)
    withClue("the second is at block 50 a good node, so no fetch: ") {
      n.fetches.expectNoMessage(50.millis)
      st.counters.moved shouldBe 0 // it went back on the stack, and the walk read it
      st.counters.nodes should be >= 4L
    }
  }

  /** Slots 1 and 2 of a root at block 10; the first's fetch hangs and the walk ends, so no batch runs from here on. The
    * head then goes to `number`: nothing but the look before the second hole's fetch can see it.
    */
  private def headMovesAtThePassTail(number: Int) =
    val n = new Node
    val first = ByteString(kec256(Array[Byte](1)))
    val second = ByteString(kec256(Array[Byte](2)))
    n.head(10, n.s.put(branch(1 -> first, 2 -> second)))
    val audit = n.spawn(settings = instant.copy(batchSize = 1))
    val fetch = n.fetches.receiveMessage()
    fetch.hash shouldBe first
    eventually {
      val st = n.status(audit)
      st.stack shouldBe 0
      st.reading shouldBe false
    }
    val other = ByteString(kec256(Array[Byte](3)))
    val rootB = n.s.put(branch(1 -> first, 2 -> other))
    n.head(number, rootB)
    exhaust(fetch)
    val next = n.fetches.receiveMessage()
    next.hash shouldBe other
    next.stateRoot shouldBe Some(rootB)

  it should "ask the head as it is now before a fetch, though no batch has run to say it moved" taggedAs UnitTest in {
    headMovesAtThePassTail(11)
  }

  it should "ask it after a rewind too, to a head below the root the hole was resolved under" taggedAs UnitTest in {
    headMovesAtThePassTail(9)
  }

  it should "fetch a hole that waited at the root it is now resolved under" taggedAs UnitTest in {
    val n = new Node
    val other = ByteString(kec256(Array[Byte](3)))
    val (audit, fetch, rootB) = holesThatWait(n, (first, _) => n.s.put(branch(1 -> first, 2 -> other)))
    exhaust(fetch)
    val next = n.fetches.receiveMessage()
    next.hash shouldBe other
    next.stateRoot shouldBe Some(rootB)
    exhaust(next)
    n.awaitBackoff(audit)
  }

  // ---- decision 6: throttle -------------------------------------------------------------------------------------------

  it should "pause over 60 s behind, reading no nodes, and resume only under 36 s" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    n.s.setHead(10, root, n.nowSeconds - 61)
    val audit = n.spawn()

    eventually(n.status(audit).paused shouldBe true)
    n.s.batchSizes shouldBe empty

    withClue("36 s behind is not yet under 36 s: ") {
      n.s.setHead(10, root, n.nowSeconds - 36)
      manualTime.timePasses(instant.pausePoll)
      // The tick is in the mailbox, so the first answer means it was read and its result is queued; the second, handled.
      n.status(audit)
      n.status(audit).paused shouldBe true
      n.s.batchSizes shouldBe empty
    }

    n.s.setHead(10, root, n.nowSeconds - 35)
    manualTime.timePasses(instant.pausePoll)
    n.ended(audit)
    n.s.batchSizes should not be empty
  }

  it should "wait out an absent head root without counting a hole, and go on when the root is back" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    n.head(10, ByteString(kec256(Array[Byte](9))))
    val (st, said) = logsOf {
      val audit = n.spawn()
      val waiting = eventually {
        val status = n.status(audit)
        status.rootAbsent shouldBe true
        status
      }
      n.head(9, root) // fork recovery rewinds
      manualTime.timePasses(instant.pausePoll)
      n.ended(audit)
      waiting
    }
    st.counters.holes shouldBe 0
    n.fetches.expectNoMessage(50.millis)
    lines(said, Level.WARN).count(_.contains("not a hole")) shouldBe 1
    n.s.app.getSnapAuditCleanAnchor() shouldBe Some("5")
  }

  it should "try again after a failed read, with the frames it had, and say so" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    n.head(10, root)
    @volatile var failing = true
    @volatile var failed = false
    val flaky = new AuditReader:
      def nodes(hashes: Seq[ByteString]): Seq[RawNodeRead] =
        if failing then
          failed = true
          throw new IllegalStateException("the store fell over")
        n.s.reader.nodes(hashes)
      def codes(hashes: Seq[ByteString]): Seq[Option[ByteString]] = n.s.reader.codes(hashes)
      def head(): Option[AuditHead] = n.s.reader.head()
    val (_, said) = logsOf {
      val audit = n.spawn(reader = flaky)
      eventually {
        failed shouldBe true
        val st = n.status(audit)
        st.reading shouldBe false // its frames are back, and the retry is on the clock
        st.stack should be >= 1
      }
      failing = false
      manualTime.timePasses(instant.pausePoll)
      n.ended(audit)
    }
    lines(said, Level.ERROR).exists(_.contains("A read failed")) shouldBe true
    n.s.app.getSnapAuditCleanAnchor() shouldBe Some("5")
  }

  // ---- decision 2: isolation ------------------------------------------------------------------------------------------

  it should "send nothing towards import when its own fetch exhausts: not the counter, nor import's fetcher" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    val (_, hash) = deepStorageNode(n, root)
    n.s.delete(hash)
    n.head(10, root)

    val requests = createTestProbe[PeersClient.Request[?]]()
    val client = testKit.spawn(Behaviors.receiveMessage[PeersClient.Command] {
      case request: PeersClient.Request[?] =>
        requests.ref ! request
        request.replyTo ! PeersClient.NoSuitablePeer
        Behaviors.same
      case _ => Behaviors.same
    })
    val fast = syncConfig.copy(syncRetryInterval = 1.millis, maxRetryDelay = 1.millis)
    val audit = n.spawn(fetcher =
      ctx => ctx.spawn(StateNodeFetcher(client, fast, ctx.system.ignoreRef), "audit-state-node-fetcher")
    )

    // The real fetcher, to the end of its retry budget: every peer answers that it cannot help.
    requests.receiveMessage()
    (1 until StateNodeFetcher.MaxStateNodeFetchRetries).foreach { _ =>
      eventually {
        manualTime.timePasses(StateNodeFetcher.BackoffInterval)
        requests.receiveMessage(100.millis)
      }
    }

    val st = n.awaitBackoff(audit)
    st.counters.unresolved shouldBe 1
    // Not the counter: import's exhaust count belongs to a regular-sync session, which the audit holds no reference to
    // (#1705). Nor import's fetcher: the audit's fetch went to its own.
  }

  // Import's blacklist is a CacheBasedBlacklist, and every one of those sets a shared gauge and bumps shared counters. So
  // forNode reaching for import's, or for any other of that class, moves them, and only then does this go red.
  it should "keep a peer that serves a wrong node to itself: forNode's blacklist is neither import's nor its kind" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    val (_, hash) = deepStorageNode(n, root)
    n.s.delete(hash)

    // What forNode's own reader reads: the best block by its hash, stamped now, on the clock it reads.
    val header = com.chipprbots.ethereum.Fixtures.Blocks.Genesis.header.copy(
      number = com.chipprbots.ethereum.domain.BlockNumber(10),
      stateRoot = com.chipprbots.ethereum.domain.TrieRoot(root),
      unixTimestamp = com.chipprbots.ethereum.domain.Timestamp(System.currentTimeMillis() / 1000)
    )
    new BlockHeadersStorage(n.s.source).put(header.hash.value, header).commit()
    n.s.app.putBestBlockInfo(com.chipprbots.ethereum.domain.appstate.BlockInfo(header.hash.value, 10)).commit()
    val chain = new BlockchainReader(
      new BlockHeadersStorage(n.s.source),
      new BlockBodiesStorage(n.s.source),
      new BlockNumberMappingStorage(n.s.source),
      n.s.state,
      new ReceiptStorage(n.s.source),
      n.s.app,
      new ChainWeightStorage(n.s.source),
      new BlockAccessListStorage(n.s.source)
    )

    def peerNamed(name: String): Peer = Peer(
      PeerId(name),
      new InetSocketAddress("127.0.0.1", 30303),
      createTestProbe[PeerActor.Command]().ref,
      incomingConnection = false
    )
    val peers = Seq(peerNamed("bad-peer"), peerNamed("good-peer"))
    val info = PeerInfo(
      RemoteStatus(
        capability = Capability.ETH68,
        networkId = 1,
        chainWeight = ChainWeight.totalDifficultyOnly(BigInt(1000)),
        bestHash = ByteString("head"),
        genesisHash = ByteString("genesis"),
        supportsSnap = true
      ),
      ChainWeight.totalDifficultyOnly(BigInt(1000)),
      forkAccepted = true,
      maxBlockNumber = 42,
      bestBlockHash = ByteString("head")
    )
    val sent = createTestProbe[NetworkPeerManagerActor.SendMessageCmd]()
    val networkPeerManager = testKit.spawn(Behaviors.receiveMessage[NetworkPeerManagerActor.Command] {
      case NetworkPeerManagerActor.GetHandshakedPeersCmd(replyTo) =>
        replyTo ! NetworkPeerManagerActor.HandshakedPeers(peers.map(_ -> info).toMap)
        Behaviors.same
      case send: NetworkPeerManagerActor.SendMessageCmd =>
        sent.ref ! send
        Behaviors.same
      case _ => Behaviors.same
    })
    val bus = testKit.spawn(PeerEventBusActor.behavior())
    val config = syncConfig.copy(
      peersScanInterval = 1.millis,
      syncRetryInterval = 1.millis,
      maxRetryDelay = 1.millis,
      blacklistDuration = 1.hour
    )

    def trieNodesRequest(): NetworkPeerManagerActor.SendMessageCmd = eventually {
      manualTime.timePasses(StateNodeFetcher.BackoffInterval)
      val send = sent.receiveMessage(100.millis)
      send.message.underlyingMsg shouldBe a[GetTrieNodes]
      send
    }

    val gaugeBefore = NetworkMetrics.BlacklistedPeersSize.get
    NetworkMetrics.BlacklistedPeersSize.set(12345L)
    val countedBefore = NetworkMetrics.BlacklistedReasonsRegularSyncGroup.count()

    val audit = testKit.spawn(
      BackgroundAudit.forNode(chain, n.s.app, n.s.state, n.s.code, networkPeerManager, bus, config)
    )

    // Stopped whatever happens: its client scans every millisecond of a clock later specs here move by hours.
    try
      val request = trieNodesRequest()
      val requestId = request.message.underlyingMsg.asInstanceOf[GetTrieNodes].requestId
      bus ! PublishCmd(MessageFromPeer(TrieNodes(requestId, Seq(ByteString(Array.fill[Byte](40)(1)))), request.peerId))

      // The fetcher tells its client to blacklist and then asks again, so the client has handled the one by the other.
      val next = trieNodesRequest()
      next.peerId should not be request.peerId

      NetworkMetrics.BlacklistedPeersSize.get shouldBe 12345L
      NetworkMetrics.BlacklistedReasonsRegularSyncGroup.count() shouldBe countedBefore
    finally
      testKit.stop(audit)
      NetworkMetrics.BlacklistedPeersSize.set(gaugeBefore) // it is the JVM's, and later specs may read it
  }

  // ---- decision 7: cadence -------------------------------------------------------------------------------------------

  "BackgroundAudit.gate" should "owe a pass to a node with no pivot, as a checkpoint leaves it" taggedAs UnitTest in {
    val n = new Node(pivot = None)
    n.owes shouldBe true
    BackgroundAudit.gate(enabled = true, n.s.state, n.s.app) shouldBe BackgroundAudit.Gate.Run
  }

  it should "owe nothing to a node that never finished SNAP" taggedAs UnitTest in {
    val app = new AppStateStorage(EphemDataSource())
    val state = new AuditTestState().state
    BackgroundAudit.gate(enabled = true, state, app) shouldBe BackgroundAudit.Gate.NotOwed
  }

  it should "owe nothing after a clean pass of this pivot, and owe again at the next" taggedAs UnitTest in {
    val n = new Node
    n.s.app.recoveryDone().and(n.s.app.putSnapAuditCleanAnchor("5")).commit()
    BackgroundAudit.gate(enabled = true, n.s.state, n.s.app) shouldBe BackgroundAudit.Gate.NotOwed
    n.s.app.putSnapSyncPivotBlock(6).commit()
    BackgroundAudit.gate(enabled = true, n.s.state, n.s.app) shouldBe BackgroundAudit.Gate.Run
  }

  it should "owe a pass with the anchor matching and a recovery flag down: nothing else pays that, with the scan skipped" taggedAs UnitTest in {
    val n = new Node
    n.s.app.recoveryDone().and(n.s.app.putSnapAuditCleanAnchor("5")).commit()
    n.s.app.clearBytecodeRecoveryDone().commit()
    BackgroundAudit.gate(enabled = true, n.s.state, n.s.app) shouldBe BackgroundAudit.Gate.Run
  }

  it should "stay off when configured off, and under in-memory pruning" taggedAs UnitTest in {
    val n = new Node
    BackgroundAudit.gate(enabled = false, n.s.state, n.s.app) shouldBe BackgroundAudit.Gate.Disabled
    val (memory, _, _) = StateStorage.createTestStateStorage(EphemDataSource(), InMemoryPruning(10))
    BackgroundAudit.gate(enabled = true, memory, n.s.app) shouldBe BackgroundAudit.Gate.CannotRepair
  }

  "BackgroundAudit.enabledIn" should "read the key with hasPath, on unless it says false" taggedAs UnitTest in {
    import com.typesafe.config.ConfigFactory
    BackgroundAudit.enabledIn(ConfigFactory.empty()) shouldBe true
    BackgroundAudit.enabledIn(ConfigFactory.parseString("snap-sync.background-audit.enabled = true")) shouldBe true
    BackgroundAudit.enabledIn(ConfigFactory.parseString("snap-sync.background-audit.enabled = false")) shouldBe false
  }

  // Spec 022 slice 6d: the key ships, and the four healing settings slice 6c stopped reading are gone from the file.
  it should "be on in the shipped sync.conf, which names none of the healing settings spec 022 removed" taggedAs UnitTest in {
    val sync = com.typesafe.config.ConfigFactory.parseResources("conf/base/sync.conf").resolve().getConfig("sync")
    sync.hasPath(BackgroundAudit.EnabledPath) shouldBe true
    BackgroundAudit.enabledIn(sync) shouldBe true
    Seq("healing-frontier-persistence", "scoped-heal-verification", "scoped-heal-max-paths", "pruned-heal-verification")
      .filter(key => sync.hasPath(s"snap-sync.$key")) shouldBe empty
  }

  "A pass on a checkpoint-synced node" should "record the fixed value as its anchor" taggedAs UnitTest in {
    val n = new Node(pivot = None)
    n.head(10, populate(n))
    n.ended(n.spawn())
    n.s.app.getSnapAuditCleanAnchor() shouldBe Some(AppStateStorage.AuditNoPivot)
    n.owes shouldBe false
  }

  // ---- decision 8: resume --------------------------------------------------------------------------------------------

  "A restart" should "take its place from the cursors, and resolve each from the current root" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    n.head(10, root)
    val everything = n.s.allNodes(root)
    // Saved at some earlier root: one position that still exists, and one no trie here has.
    val (kept, _) = everything.filter(_._1.nibbles.length == 2).head
    val gone = AuditPosition(None, ByteString(Array.fill[Byte](40)(15)))
    val counters = AuditCounters(nodes = 100, accounts = 7)
    n.s.progress.writeAuditCursors(AuditCursors.encode(AuditCursors.Saved("5", 3L, counters, Vector(kept, gone))))

    val (_, said) = logsOf(n.ended(n.spawn()))

    n.s.app.getSnapAuditCleanAnchor() shouldBe Some("5")
    lines(said, Level.INFO).exists(_.contains("resumed")) shouldBe true
    withClue("the counts carry on, and it walked what is under its place, not the whole trie: ") {
      val end = lines(said, Level.INFO).find(_.contains("ended clean")).get
      val nodes = "nodes=(\\d+)".r.findFirstMatchIn(end).get.group(1).toLong
      nodes should be > 100L
      nodes should be < 100L + everything.size
    }
  }

  it should "resume after the state moved on and was pruned: a stale cursor makes no false hole" taggedAs UnitTest in {
    val n = new Node
    val rootA = populate(n)
    n.head(10, rootA)
    // A place saved under the first root.
    val positions = n.s.allNodes(rootA).map(_._1).filter(p => !p.isStorage && p.nibbles.length == 1)
    n.s.progress.writeAuditCursors(AuditCursors.encode(AuditCursors.Saved("5", 2L, AuditCounters(), positions)))
    // Every account changes, and the old nodes go.
    val onRootA = n.s.allNodes(rootA)
    val rootB = n.s.update(Some(rootA), 11, (0 until 20).map(i => trieKey(i) -> accountBytes(nonce = 11)))
    val onRootB = n.s.allNodes(rootB).map(_._2).toSet
    onRootA.map(_._2).filterNot(onRootB.contains).foreach(n.s.delete)
    n.head(11, rootB)

    n.ended(n.spawn())

    n.fetches.expectNoMessage(50.millis)
    n.s.app.getSnapAuditCleanAnchor() shouldBe Some("5")
  }

  it should "not end clean after a restart when the pass found a hole before it" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    val (position, hash) = deepStorageNode(n, root)
    val bytes = unwrappedBytes(n, hash)
    n.s.delete(hash)
    n.head(10, root)

    val first = n.spawn()
    n.fetches.receiveMessage()
    eventually(n.status(first).stack shouldBe 0)
    testKit.stop(first)
    createTestProbe().expectTerminated(first)

    val saved = AuditCursors.decode(n.s.progress.readAuditCursors().get).get
    withClue("a hole goes out at once: ") {
      saved.counters.holes shouldBe 1
      saved.positions should contain(position)
    }

    val second = n.spawn()
    val again = n.fetches.receiveMessage()
    again.hash shouldBe hash
    serve(again, bytes)

    n.awaitBackoff(second).counters.clean shouldBe false
    n.s.app.getSnapAuditCleanAnchor() shouldBe None
  }

  it should "start over when the cursors are of another anchor, or do not parse" taggedAs UnitTest in {
    Seq(
      AuditCursors.encode(
        AuditCursors.Saved("4", 1L, AuditCounters(holes = 9), Vector(AuditPosition(None, nibbles(3, 1))))
      ),
      Array[Byte](1, 2, 3)
    ).foreach { cursors =>
      val n = new Node
      n.head(10, populate(n))
      n.s.progress.writeAuditCursors(cursors)
      n.ended(n.spawn())
      withClue("the old counts are not this pass's: ")(n.s.app.getSnapAuditCleanAnchor() shouldBe Some("5"))
    }
  }

  // ---- decision 9: lifecycle ------------------------------------------------------------------------------------------

  "A Future that finishes after a stop" should "write nothing" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    n.head(10, root)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val finished = new CountDownLatch(1)
    val held = new AuditReader:
      def nodes(hashes: Seq[ByteString]): Seq[RawNodeRead] =
        entered.countDown()
        release.await(10, TimeUnit.SECONDS)
        try n.s.reader.nodes(hashes)
        finally finished.countDown()
      def codes(hashes: Seq[ByteString]): Seq[Option[ByteString]] = n.s.reader.codes(hashes)
      def head(): Option[AuditHead] = n.s.reader.head()

    val audit = n.spawn(reader = held, ec = ExecutionContext.global)
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    val cursorsAtStop = n.s.progress.readAuditCursors().map(_.toSeq)
    testKit.stop(audit)
    createTestProbe().expectTerminated(audit)

    release.countDown()
    finished.await(10, TimeUnit.SECONDS) shouldBe true
    // What a late result could have written goes through the actor, which is gone. Let the dispatcher drain, then look.
    val drained = new CountDownLatch(1)
    ExecutionContext.global.execute(() => drained.countDown())
    drained.await(10, TimeUnit.SECONDS) shouldBe true

    n.s.progress.readAuditCursors().map(_.toSeq) shouldBe cursorsAtStop
    n.s.app.getSnapAuditCleanAnchor() shouldBe None
    n.s.app.isBytecodeRecoveryDone() shouldBe false
    n.s.app.isStorageRecoveryDone() shouldBe false
  }

  // ---- decision 15: a bad entry no peer serves -------------------------------------------------------------------------

  /** A bad node at [3] of the root, and the head on it. */
  private def withBadNode(n: Node): (ByteString, ByteString) =
    val hash = n.s.put(storageLeaf(1))
    val root = n.s.put(branch(3 -> hash))
    n.s.corrupt(hash, storageLeaf(2).encode)
    n.head(10, root)
    (hash, root)

  "A bad entry" should "stay through two passes that cannot fetch it, and go at the third" taggedAs UnitTest in {
    val n = new Node
    val (hash, _) = withBadNode(n)
    val (_, said) = logsOf {
      val audit = n.spawn()

      exhaust(n.fetches.receiveMessage())
      n.awaitBackoff(audit).counters.deleted shouldBe 0
      n.s.nodes.get(hash) shouldBe defined
      n.book.entries(hash).passes shouldBe 1

      n.nextPass()
      exhaust(n.fetches.receiveMessage())
      n.awaitBackoff(audit).counters.deleted shouldBe 0
      withClue("two is not enough: ")(n.s.nodes.get(hash) shouldBe defined)
      n.book.entries(hash).passes shouldBe 2

      n.nextPass()
      exhaust(n.fetches.receiveMessage())
      n.awaitBackoff(audit).counters.deleted shouldBe 1
      n.s.nodes.get(hash) shouldBe None
      n.book.entries.get(hash) shouldBe None

      withClue("then it is a hole like any other, with nothing left to delete: ") {
        n.nextPass()
        val fetch = n.fetches.receiveMessage()
        fetch.hash shouldBe hash
        exhaust(fetch)
        val st = n.awaitBackoff(audit)
        st.counters.holes shouldBe 1
        st.counters.badNodes shouldBe 0
        st.counters.deleted shouldBe 0
      }
    }
    val deleted = lines(said, Level.ERROR).find(_.contains("Deleted")).get
    deleted should include(AuditWalk.hexOf(hash))
    deleted should include("account trie path 3")
    deleted should include("on-demand")
    deleted should include("SNAP re-sync")
  }

  it should "count only one exhaust per pass, however often the position comes up" taggedAs UnitTest in {
    val n = new Node
    val (hash, root) = withBadNode(n)
    n.s.progress.writeAuditCursors(
      AuditCursors.encode(AuditCursors.Saved("5", 7L, AuditCounters(), Vector(AuditPosition(None, nibbles(1, 3)))))
    )
    val onRecord = AuditBadEntries.Entry(2, lastPass = 7L, lastMillis = 0L)
    n.s.progress.writeAuditBadEntries(AuditBadEntries("5", 7L, Map(hash -> onRecord)).encode)
    val audit = n.spawn()
    exhaust(n.fetches.receiveMessage())
    n.awaitBackoff(audit).counters.deleted shouldBe 0
    n.s.nodes.get(hash) shouldBe defined
    n.book.entries(hash).passes shouldBe 2
    root should not be empty
  }

  it should "keep its count across restarts" taggedAs UnitTest in {
    val n = new Node
    val (hash, _) = withBadNode(n)

    (1 to 2).foreach { pass =>
      val audit = n.spawn()
      exhaust(n.fetches.receiveMessage())
      n.awaitBackoff(audit)
      testKit.stop(audit)
      createTestProbe().expectTerminated(audit)
      n.book.entries(hash).passes shouldBe pass
      n.s.nodes.get(hash) shouldBe defined
      n.now.addAndGet(backoff.toMillis)
      n.s.bestHead = n.s.bestHead.map(_.copy(timestampSeconds = n.nowSeconds))
    }

    val audit = n.spawn()
    exhaust(n.fetches.receiveMessage())
    n.awaitBackoff(audit).counters.deleted shouldBe 1
    n.s.nodes.get(hash) shouldBe None
  }

  it should "not count a restart inside the backoff as another pass" taggedAs UnitTest in {
    val n = new Node
    val (hash, _) = withBadNode(n)
    (1 to 2).foreach { _ =>
      val audit = n.spawn()
      exhaust(n.fetches.receiveMessage())
      n.awaitBackoff(audit)
      testKit.stop(audit)
      createTestProbe().expectTerminated(audit)
    }
    n.book.entries(hash).passes shouldBe 1
  }

  it should "lose its record when a peer serves it" taggedAs UnitTest in {
    val n = new Node
    val leaf = storageLeaf(1)
    val (hash, _) = withBadNode(n)
    n.onRecord(hash, 2)
    val audit = n.spawn()
    serve(n.fetches.receiveMessage(), leaf.encode)
    n.awaitBackoff(audit).counters.repaired shouldBe 1
    n.book.entries.get(hash) shouldBe None
    unwrappedBytes(n, hash).toSeq shouldBe leaf.encode.toSeq
  }

  it should "not be deleted when a second read finds it good: a transient read fault is not proof" taggedAs UnitTest in {
    val n = new Node
    val leaf = storageLeaf(1)
    val hash = n.s.put(leaf) // good on disk
    val root = n.s.put(branch(3 -> hash))
    n.head(10, root)
    n.onRecord(hash, 2)
    @volatile var faulty = true
    val reader = new AuditReader:
      def nodes(hashes: Seq[ByteString]): Seq[RawNodeRead] =
        if faulty && hashes.contains(hash) then hashes.map(_ => RawNodeRead.Bytes(storageLeaf(2).encode))
        else n.s.reader.nodes(hashes)
      def codes(hashes: Seq[ByteString]): Seq[Option[ByteString]] = n.s.reader.codes(hashes)
      def head(): Option[AuditHead] = n.s.reader.head()

    val audit = n.spawn(reader = reader)
    val fetch = n.fetches.receiveMessage()
    faulty = false
    exhaust(fetch)

    val st = n.awaitBackoff(audit)
    st.counters.deleted shouldBe 0
    n.s.nodes.get(hash) shouldBe defined
    unwrappedBytes(n, hash).toSeq shouldBe leaf.encode.toSeq
    n.book.entries.get(hash) shouldBe None
    withClue("what the good node holds is walked after all: ")(st.counters.nodes shouldBe 2)
  }

  it should "not be deleted when the current root no longer reaches it there" taggedAs UnitTest in {
    val n = new Node
    val (hash, _) = withBadNode(n)
    n.onRecord(hash, 2)
    val audit = n.spawn()
    val fetch = n.fetches.receiveMessage()

    n.head(11, n.s.put(branch(4 -> n.s.put(storageLeaf(5)))))
    exhaust(fetch)

    n.awaitBackoff(audit).counters.deleted shouldBe 0
    n.s.nodes.get(hash) shouldBe defined
    n.book.entries.get(hash) shouldBe None
  }

  it should "stay and be counted when the record is full" taggedAs UnitTest in {
    val n = new Node
    val (hash, _) = withBadNode(n)
    val full = (0 until AuditBadEntries.MaxEntries)
      .map(i => trieKey(i) -> AuditBadEntries.Entry(1, 0L, 0L))
      .toMap
    n.s.progress.writeAuditBadEntries(AuditBadEntries("5", 0L, full).encode)
    val audit = n.spawn()
    exhaust(n.fetches.receiveMessage())
    val st = n.awaitBackoff(audit)
    st.counters.badOverBound shouldBe 1
    st.counters.deleted shouldBe 0
    st.counters.clean shouldBe false
    n.s.nodes.get(hash) shouldBe defined
    n.book.entries should have size AuditBadEntries.MaxEntries
  }

  "Code that fails its hash" should "go at the third pass, and be a hole after that" taggedAs UnitTest in {
    val n = new Node
    val codeHash = ByteString(kec256(Array[Byte](7, 7)))
    n.s.code.put(codeHash, ByteString(Array[Byte](1, 2, 3))).commit()
    val leaf = n.s.put(accountLeaf(1, accountBytes(codeHash = Some(codeHash))))
    n.head(10, n.s.put(branch(1 -> leaf)))
    val audit = n.spawn()

    exhaust(n.fetches.receiveMessage())
    n.awaitBackoff(audit)
    n.nextPass()
    exhaust(n.fetches.receiveMessage())
    n.awaitBackoff(audit)
    n.s.code.get(codeHash) shouldBe defined

    n.nextPass()
    exhaust(n.fetches.receiveMessage())
    n.awaitBackoff(audit).counters.deleted shouldBe 1
    n.s.code.get(codeHash) shouldBe None

    n.nextPass()
    val fetch = n.fetches.receiveMessage()
    fetch.isByteCode shouldBe true
    exhaust(fetch)
    n.awaitBackoff(audit).counters.holes shouldBe 1
  }

  // ---- what it says, and where -----------------------------------------------------------------------------------------

  // `logging.logs-level` is ERROR, `blockchain.sync` has no entry, and resources do not change on a fix branch. A name the
  // shipped file leaves at ROOT would log nothing an operator can see.
  "The audit's logger" should "be kept at INFO by the shipped logback.xml" taggedAs UnitTest in {
    val file = new java.io.File("src/main/resources/logback.xml")
    val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    val loggers = document.getElementsByTagName("logger")
    val levels = (0 until loggers.getLength).map { i =>
      val element = loggers.item(i).asInstanceOf[org.w3c.dom.Element]
      element.getAttribute("name") -> element.getAttribute("level")
    }
    val nearest = levels
      .filter((name, _) => BackgroundAudit.LoggerName == name || BackgroundAudit.LoggerName.startsWith(name + "."))
      .maxBy(_._1.length)
    nearest shouldBe ("com.chipprbots.ethereum.blockchain.sync.SyncController" -> "INFO")
  }

  // ---- the review round -------------------------------------------------------------------------------------------------

  "An entry that does not unwrap and hashes to its key" should "never be deleted, however many passes no peer serves it" taggedAs UnitTest in {
    val n = new Node
    val leaf = storageLeaf(1)
    val hash = ByteString(kec256(leaf.encode))
    n.s.nodes.put(hash, leaf.encode) // what an archive store holds, read by a basic one
    n.head(10, n.s.put(branch(3 -> hash)))
    n.onRecord(hash, 2)
    val audit = n.spawn()
    exhaust(n.fetches.receiveMessage())
    val st = n.awaitBackoff(audit)
    st.counters.deleted shouldBe 0
    st.counters.unfollowable shouldBe 1
    st.counters.clean shouldBe false
    n.s.nodes.get(hash).get.toSeq shouldBe leaf.encode.toSeq
  }

  "A batch that fails at its code read" should "look at that code again: a pass must not end clean with it unchecked" taggedAs UnitTest in {
    val n = new Node
    val absentCode = ByteString(kec256(Array[Byte](5, 5)))
    val leaf = n.s.put(accountLeaf(1, accountBytes(codeHash = Some(absentCode))))
    n.head(10, n.s.put(branch(1 -> leaf)))
    @volatile var failing = true
    @volatile var failed = false
    val flaky = new AuditReader:
      def nodes(hashes: Seq[ByteString]): Seq[RawNodeRead] = n.s.reader.nodes(hashes)
      def codes(hashes: Seq[ByteString]): Seq[Option[ByteString]] =
        if failing then
          failed = true
          throw new IllegalStateException("the store fell over")
        n.s.reader.codes(hashes)
      def head(): Option[AuditHead] = n.s.reader.head()
    val audit = n.spawn(reader = flaky)
    eventually {
      failed shouldBe true
      val st = n.status(audit)
      st.reading shouldBe false
      st.stack shouldBe 1
    }
    failing = false
    manualTime.timePasses(instant.pausePoll)
    val fetch = n.fetches.receiveMessage()
    fetch.hash shouldBe absentCode
    fetch.isByteCode shouldBe true
    exhaust(fetch)
    n.awaitBackoff(audit).counters.holes shouldBe 1
  }

  "A hole looked at again before its turn" should "become the node it now lies inside, at that node's position" taggedAs UnitTest in {
    val n = new Node
    val under = n.s.put(branch(1 -> n.s.put(storageLeaf(7))))
    val (audit, fetch, _) = holesThatWait(n, (_, _) => n.s.put(extension(Seq(3, 5), under)))
    exhaust(fetch)
    val st = n.awaitBackoff(audit)
    n.fetches.expectNoMessage(50.millis)
    st.counters.moved shouldBe 1
    withClue("what the extension holds was read, after it stood in for the position: ") {
      n.s.reader.nodes(Seq(under)).head should not be RawNodeRead.Absent
      st.counters.nodes should be >= 6L
    }
  }

  "A fetch that times out" should "not count towards a delete: no peer may have been asked" taggedAs UnitTest in {
    val n = new Node
    val (hash, _) = withBadNode(n)
    n.onRecord(hash, 2)
    val audit = n.spawn()
    n.fetches.receiveMessage()
    manualTime.timePasses(instant.fetchTimeout)
    val st = n.awaitBackoff(audit)
    st.counters.deleted shouldBe 0
    withClue("the pass is still not clean: ")(st.counters.unresolved shouldBe 1)
    n.s.nodes.get(hash) shouldBe defined
    n.book.entries(hash).passes shouldBe 2
  }

  "The record of bad entries" should "start over when the anchor changes: a count for one SNAP's state is not the next's" taggedAs UnitTest in {
    val n = new Node
    val (hash, _) = withBadNode(n)
    val first = n.spawn()
    exhaust(n.fetches.receiveMessage())
    n.awaitBackoff(first)
    testKit.stop(first)
    createTestProbe().expectTerminated(first)
    n.book.entries(hash).passes shouldBe 1

    n.s.app.putSnapSyncPivotBlock(6).commit()
    n.now.addAndGet(backoff.toMillis)
    n.s.bestHead = n.s.bestHead.map(_.copy(timestampSeconds = n.nowSeconds))
    val second = n.spawn()
    exhaust(n.fetches.receiveMessage())
    n.awaitBackoff(second)
    n.book.entries(hash).passes shouldBe 1
  }

  it should "be cleared by a clean pass, which reached none of it" taggedAs UnitTest in {
    val n = new Node
    n.head(10, populate(n))
    n.onRecord(trieKey(999), 2)
    n.ended(n.spawn())
    n.s.app.getSnapAuditCleanAnchor() shouldBe Some("5")
    n.book.entries shouldBe empty
  }

  private def clockPasses(n: Node, d: FiniteDuration): Unit =
    n.now.addAndGet(d.toMillis)
    n.s.bestHead = n.s.bestHead.map(_.copy(timestampSeconds = n.nowSeconds))
    manualTime.timePasses(d)

  /** A node with a hole no peer will serve, so every pass of it ends not clean. */
  private def withUnservableHole(n: Node): Unit =
    val root = populate(n)
    val (_, hash) = deepStorageNode(n, root)
    n.s.delete(hash)
    n.head(10, root)

  "Passes that do not end clean" should "wait twice as long each time" taggedAs UnitTest in {
    val n = new Node
    withUnservableHole(n)
    val audit = n.spawn()
    exhaust(n.fetches.receiveMessage())
    n.awaitBackoff(audit)

    clockPasses(n, 1.hour)
    exhaust(n.fetches.receiveMessage())
    n.awaitBackoff(audit)

    clockPasses(n, 1.hour)
    withClue("2 h after the second: ")(n.status(audit).pass shouldBe 2L)
    clockPasses(n, 1.hour)
    exhaust(n.fetches.receiveMessage())
    n.awaitBackoff(audit)

    clockPasses(n, 3.hours)
    withClue("4 h after the third: ")(n.status(audit).pass shouldBe 3L)
    clockPasses(n, 1.hour)
    n.fetches.receiveMessage()
    testKit.stop(audit)
  }

  it should "go by the pass number on record, so a restart does not start the wait again" taggedAs UnitTest in {
    val n = new Node
    withUnservableHole(n)
    n.s.progress.writeAuditBadEntries(AuditBadEntries("5", 3L).encode)
    val audit = n.spawn()
    exhaust(n.fetches.receiveMessage())
    n.awaitBackoff(audit)

    clockPasses(n, 7.hours)
    withClue("the fourth waits 8 h: ")(n.status(audit).pass shouldBe 4L)
    clockPasses(n, 1.hour)
    n.fetches.receiveMessage()
    testKit.stop(audit)
  }

  it should "start from the base again after a clean pass" taggedAs UnitTest in {
    val n = new Node
    n.head(10, populate(n))
    n.s.progress.writeAuditBadEntries(AuditBadEntries("5", 6L).encode)
    n.ended(n.spawn())
    n.book.lastPass shouldBe 0L
  }

  "A delete's log" should "say what happens next for code, which is a bulk recovery first and not an on-demand fetch" taggedAs UnitTest in {
    val n = new Node
    val codeHash = ByteString(kec256(Array[Byte](7, 7)))
    n.s.code.put(codeHash, ByteString(Array[Byte](1, 2, 3))).commit()
    n.head(10, n.s.put(branch(1 -> n.s.put(accountLeaf(1, accountBytes(codeHash = Some(codeHash)))))))
    n.onRecord(codeHash, 2)
    val (_, said) = logsOf {
      val audit = n.spawn()
      exhaust(n.fetches.receiveMessage())
      n.awaitBackoff(audit).counters.deleted shouldBe 1
    }
    val deleted = lines(said, Level.ERROR).find(_.contains("Deleted")).get
    deleted should include(AuditWalk.hexOf(codeHash))
    deleted should include("bulk bytecode recovery")
    deleted should include("after that")
    deleted should include("on-demand")
  }

  "A head that stands still" should "not hold the audit off for good: over 5 minutes unmoved, it goes on" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    n.s.setHead(10, root, n.nowSeconds - 61)
    val audit = n.spawn()
    eventually(n.status(audit).paused shouldBe true)
    n.s.batchSizes shouldBe empty

    withClue("under 5 minutes it might yet be import catching up: ") {
      n.now.addAndGet(4.minutes.toMillis)
      manualTime.timePasses(instant.pausePoll)
      // The tick is in the mailbox, so the first answer means it was read and its result is queued; the second, handled.
      n.status(audit)
      n.status(audit).paused shouldBe true
      n.s.batchSizes shouldBe empty
    }

    n.now.addAndGet(1.minute.toMillis)
    manualTime.timePasses(instant.pausePoll)
    n.ended(audit)
    n.s.app.getSnapAuditCleanAnchor() shouldBe Some("5")
  }

  "A head that is old and moving" should "keep the audit paused, however long it takes: import is catching up" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    n.s.setHead(10, root, n.nowSeconds - 3600)
    val audit = n.spawn()
    eventually(n.status(audit).paused shouldBe true)

    (11 to 14).foreach { number =>
      n.now.addAndGet(4.minutes.toMillis)
      n.s.setHead(number, root, n.nowSeconds - 3600)
      manualTime.timePasses(instant.pausePoll)
      n.status(audit)
      withClue(s"at block $number, 4 minutes after the last: ")(n.status(audit).paused shouldBe true)
      n.s.batchSizes shouldBe empty
    }
    testKit.stop(audit)
  }

  private def pauseLines(said: Seq[ILoggingEvent]): Seq[String] =
    lines(said, Level.INFO).filter(l => l.contains("Paused") || l.contains("Resumed"))

  // Seen live: about 14 an hour at INFO on Mordor, from ordinary PoW gaps.
  "A short pause" should "say nothing at INFO, and show in Progress as a count" taggedAs UnitTest in {
    val n = new Node
    withUnservableHole(n)
    val root = n.s.bestHead.get.stateRoot
    n.s.setHead(10, root, n.nowSeconds - 61)
    val (_, said) = logsOf {
      val audit = n.spawn()
      eventually(n.status(audit).paused shouldBe true)
      n.s.setHead(10, root, n.nowSeconds)
      manualTime.timePasses(instant.pausePoll)
      n.fetches.receiveMessage() // it has resumed, and walked to the hole
      manualTime.timePasses(instant.progressEvery)
      n.status(audit)
      testKit.stop(audit)
    }
    pauseLines(said) shouldBe empty
    lines(said, Level.INFO).find(_.contains("Progress")).get should include("pauses=1")
  }

  "A long pause" should "say so at INFO, when it passes 5 minutes and when it ends" taggedAs UnitTest in {
    val n = new Node
    val root = populate(n)
    n.s.setHead(10, root, n.nowSeconds - 3600)
    val (_, said) = logsOf {
      val audit = n.spawn()
      eventually(n.status(audit).paused shouldBe true)
      (11 to 12).foreach { number =>
        n.now.addAndGet(3.minutes.toMillis)
        n.s.setHead(number, root, n.nowSeconds - 3600)
        manualTime.timePasses(instant.pausePoll)
        n.status(audit)
        n.status(audit).paused shouldBe true
      }
      n.s.setHead(13, root, n.nowSeconds)
      manualTime.timePasses(instant.pausePoll)
      n.ended(audit)
    }
    val said2 = pauseLines(said)
    said2 should have size 2
    said2.head should include("over 5 minutes")
    said2.head should include("best block 12")
    said2(1) should include("Resumed at best block 13")
    said2(1) should include("after 360s")
  }

  /** A reader whose every node read takes `took`, on the clock it hands the actor. */
  private class TimedReader(n: Node, took: FiniteDuration) extends AuditReader:
    val nanos = new AtomicLong(0L)
    val reads = new AtomicLong(0L)
    def nodes(hashes: Seq[ByteString]): Seq[RawNodeRead] =
      nanos.addAndGet(took.toNanos)
      reads.incrementAndGet()
      n.s.reader.nodes(hashes)
    def codes(hashes: Seq[ByteString]): Seq[Option[ByteString]] = n.s.reader.codes(hashes)
    def head(): Option[AuditHead] = n.s.reader.head()

  // Every other spec here has readFactor 0, so replacing delayAfter with zero stayed green.
  "The throttle, as wired" should "sleep three times what a batch took before it reads again" taggedAs UnitTest in {
    val n = new Node
    n.head(10, populate(n))
    val reader = new TimedReader(n, 10.millis)
    val audit =
      n.spawn(settings = instant.copy(readFactor = 3), reader = reader, nanoTime = () => reader.nanos.get)
    eventually {
      reader.reads.get shouldBe 1L
      n.status(audit).reading shouldBe false
    }
    manualTime.timePasses(29.millis)
    n.status(audit)
    reader.reads.get shouldBe 1L
    manualTime.timePasses(1.millis)
    eventually(reader.reads.get should be > 1L)
    testKit.stop(audit)
  }

  // multiGetOptimizedNoFill holds the read lock throughout, and a queued writer waits behind it.
  "A batch's size" should "come down to 16 on a slow disk, so a block commit waits less" taggedAs UnitTest in {
    val n = new Node
    n.head(10, populate(n))
    val reader = new TimedReader(n, 1.second)
    n.ended(n.spawn(reader = reader, nanoTime = () => reader.nanos.get))
    n.s.app.getSnapAuditCleanAnchor() shouldBe Some("5")
    n.s.batchSizes.max shouldBe 16
  }

  it should "go up when the reads are quick" taggedAs UnitTest in {
    val n = new Node
    n.head(10, populate(n))
    val reader = new TimedReader(n, 1.micro)
    n.ended(n.spawn(reader = reader, nanoTime = () => reader.nanos.get))
    n.s.batchSizes.max should be > 16
  }

  // A supervisor's restart keeps the mailbox, and a new incarnation starts its count again, so a BatchDone still queued
  // from the old one could match.
  "An audit's generation" should "not come round again in another incarnation" taggedAs UnitTest in {
    val first = new Node
    withUnservableHole(first)
    val second = new Node
    withUnservableHole(second)
    val one = first.spawn()
    first.fetches.receiveMessage()
    val other = second.spawn()
    second.fetches.receiveMessage()
    first.status(one).generation should not be second.status(other).generation
    testKit.stop(one)
    testKit.stop(other)
  }
