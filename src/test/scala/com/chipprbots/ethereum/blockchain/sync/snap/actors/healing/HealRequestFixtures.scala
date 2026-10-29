package com.chipprbots.ethereum.blockchain.sync.snap.actors.healing

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString

import scala.jdk.CollectionConverters.*

import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Placed
import com.chipprbots.ethereum.blockchain.sync.snap.actors.PathHealPresenceFixtures.Trie
import com.chipprbots.ethereum.blockchain.sync.snap.actors.TrieNodeHealingCoordinator
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdate
import com.chipprbots.ethereum.db.dataSource.DataSourceUpdateOptimized
import com.chipprbots.ethereum.db.dataSource.DataUpdate
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.TrieRoot
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NullNode
import com.chipprbots.ethereum.network.NetworkPeerManagerActor
import com.chipprbots.ethereum.network.p2p.messages.SNAP

/** Fixtures for the spec 022 slice 1 tests (`HealAliasSpec`, `PathDanglingDeleteSpec`, `HealRequestModelSpec`): fixed
  * synthetic tries, a data source that records every write batch, and a fake peer that the test answers by hand.
  *
  * Every node is built from fixed bytes (`kec256` of a fixed seed), so hashes and request sets are the same on every
  * run. Leaves encode to at least 32 bytes and branches hold hash references, so a parent always references its child
  * by hash, never inline.
  */
object HealRequestFixtures:

  // ---- node builders -----------------------------------------------------------------------------------------------

  /** A leaf whose value is not an RLP account (`Account(value)` fails), so healing finds no storage trie below it. */
  def plainLeaf(key: Array[Byte], seed: String): LeafNode =
    LeafNode(ByteString(key), ByteString(Array[Byte](0x01)) ++ kec256(ByteString(seed)))

  def branch(children: (Int, MptNode)*): BranchNode =
    val slots = Array.fill[MptNode](16)(NullNode)
    children.foreach { case (i, child) => slots(i) = HashNode(child.hash) }
    BranchNode(slots, None)

  /** Pack 64 nibbles into the 32-byte account hash, as the coordinator does (`(hi << 4) | lo`). */
  def packNibbles(nibbles: Array[Byte]): ByteString =
    ByteString(nibbles.grouped(2).map(g => ((g(0) << 4) | g(1)).toByte).toArray)

  /** HP/compact encoding of a nibble path, `isLeaf = false`: the encoding of pathsets and of Path-scheme keys. */
  def compact(nibbles: Array[Byte]): ByteString = ByteString(HexPrefix.encode(nibbles, isLeaf = false))

  def nibbles(ns: Int*): Array[Byte] = ns.map(_.toByte).toArray

  /** An account-trie pathset. */
  def accountPathset(ns: Int*): Seq[ByteString] = Seq(compact(nibbles(ns*)))

  /** The 63 nibbles that follow a depth-1 slot in an account key, fixed per `seed`. */
  private def rest63(seed: Int): Array[Byte] = (0 until 63).map(i => ((i * seed + 1) % 16).toByte).toArray

  // ---- twin storage tries --------------------------------------------------------------------------------------------

  /** Two accounts whose storage tries are identical, node for node: one root `S` (a branch) over two slot leaves.
    * {{{
    *   root (branch)                     []
    *     ├─[0] acct0  (storageRoot S)   [0]          account hash A0
    *     └─[1] acct1  (storageRoot S)   [1]          account hash A1
    *   S at (A0, []) and (A1, []):      branch
    *     ├─[0] slotA                    (A0, [0]) and (A1, [0])
    *     └─[1] slotB                    (A0, [1]) and (A1, [1])
    * }}}
    * Three node hashes (`S`, `slotA`, `slotB`) live at six storage positions. `nonce` selects the accounts' content:
    * two builds that differ only in it share every storage node but differ in both account leaves and the root.
    */
  def twinStorageTrie(nonce: Int): Trie =
    val slotA = plainLeaf(nibbles(0x0a), "s022/twin/slotA")
    val slotB = plainLeaf(nibbles(0x0b), "s022/twin/slotB")
    val s = branch(0 -> slotA, 1 -> slotB)
    val account =
      Account(nonce = UInt256(nonce.toLong), balance = UInt256(1000L), storageRoot = TrieRoot(ByteString(s.hash)))
    val accountRlp = ByteString(Account.accountSerializer.toBytes(account))
    val rest0 = rest63(5)
    val rest1 = rest63(3)
    val acct0 = LeafNode(ByteString(rest0), accountRlp)
    val acct1 = LeafNode(ByteString(rest1), accountRlp)
    val root = branch(0 -> acct0, 1 -> acct1)
    val a0 = packNibbles(0.toByte +: rest0)
    val a1 = packNibbles(1.toByte +: rest1)
    Trie(
      Seq(
        Placed("root", None, Array.empty[Byte], root),
        Placed("acct0", None, nibbles(0), acct0),
        Placed("acct1", None, nibbles(1), acct1),
        Placed("s0", Some(a0), Array.empty[Byte], s),
        Placed("s0A", Some(a0), nibbles(0), slotA),
        Placed("s0B", Some(a0), nibbles(1), slotB),
        Placed("s1", Some(a1), Array.empty[Byte], s),
        Placed("s1A", Some(a1), nibbles(0), slotA),
        Placed("s1B", Some(a1), nibbles(1), slotB)
      )
    )

  /** For every requested path, the hash of the node `trie` holds there: the hashes a run fetched, one entry per path
    * requested (a hash requested twice appears twice).
    */
  def requestedHashes(trie: Trie, requests: Seq[Seq[Seq[ByteString]]]): Seq[ByteString] =
    val byPathset = trie.nodes.map(n => n.pathset -> n.hash).toMap
    requests.flatten.map(byPathset)

  /** The node hashes fetched more than once, named by the labels of the positions that hold them, with their counts. */
  def fetchedMoreThanOnce(trie: Trie, requests: Seq[Seq[Seq[ByteString]]]): Map[String, Int] =
    def name(hash: ByteString): String = trie.nodes.filter(_.hash == hash).map(_.label).mkString("|")
    requestedHashes(trie, requests).groupBy(identity).collect { case (h, n) if n.size > 1 => name(h) -> n.size }

  // ---- a data source that records its write batches ------------------------------------------------------------------

  /** One key-level operation of a write batch. */
  final case class Op(namespace: IndexedSeq[Byte], delete: Boolean, key: ByteString):
    override def toString: String =
      s"${if delete then "del" else "put"}(${namespace.map("%02x".format(_)).mkString}:${key.map("%02x".format(_)).mkString})"

  object Op:
    def of(update: DataUpdate): Seq[Op] = update match
      case DataSourceUpdate(ns, toRemove, toUpsert) =>
        toRemove.map(k => Op(ns, delete = true, ByteString(k.toArray))) ++
          toUpsert.map { case (k, _) => Op(ns, delete = false, ByteString(k.toArray)) }
      case DataSourceUpdateOptimized(ns, toRemove, toUpsert) =>
        toRemove.map(k => Op(ns, delete = true, ByteString(k))) ++
          toUpsert.map { case (k, _) => Op(ns, delete = false, ByteString(k)) }

  /** An `EphemDataSource` that records each `update` call, i.e. each write batch (RocksDB applies one call as one
    * `WriteBatch`), as the list of its key-level operations.
    */
  final class RecordingDataSource extends EphemDataSource(Map.empty):
    private val calls = new ConcurrentLinkedQueue[Seq[Op]]()

    override def update(dataSourceUpdates: Seq[DataUpdate]): Unit =
      calls.add(dataSourceUpdates.flatMap(Op.of))
      super.update(dataSourceUpdates)

    def batches: Seq[Seq[Op]] = calls.asScala.toSeq

    /** Forget the batches recorded so far (e.g. a test's own seeding writes). */
    def forget(): Unit = calls.clear()

    /** The batches that write `key` in `namespace`. */
    def batchesPutting(namespace: IndexedSeq[Byte], key: ByteString): Seq[Seq[Op]] =
      batches.filter(_.contains(Op(namespace, delete = false, key)))

  val AccountNs: IndexedSeq[Byte] = Namespaces.StateTriePathNamespace
  val StorageNs: IndexedSeq[Byte] = Namespaces.StorageTriePathNamespace

  /** The Path-scheme key of an account-trie position (`PathNodeStorage` keys by the HP-encoded path). */
  def accountKey(ns: Array[Byte]): ByteString = compact(ns)

  /** The Path-scheme key of a storage-trie position: owner (account hash) followed by the HP-encoded path. */
  def storageKey(owner: ByteString, ns: Array[Byte]): ByteString = owner ++ compact(ns)

  // ---- a peer the test answers by hand -------------------------------------------------------------------------------

  /** A fake peer manager for a real coordinator. Every `GetTrieNodes` is recorded, in arrival order, with whether it
    * was answered automatically. While `serveFrom` holds a trie map the request is answered at once from it (each path
    * resolved by position, paths the map lacks omitted, as a real SNAP peer does); otherwise it goes to `manual` and
    * the test answers it with [[answer]] or [[answerFrom]], or leaves it unanswered.
    */
  final class ScriptedPeer(testKit: ActorTestKit):
    val manual: TestProbe[SNAP.GetTrieNodes] = testKit.createTestProbe[SNAP.GetTrieNodes]()
    val serveFrom: AtomicReference[Option[Map[Seq[ByteString], ByteString]]] = new AtomicReference(None)
    val coordinator: AtomicReference[ActorRef[TrieNodeHealingCoordinator.Command]] = new AtomicReference()
    private val recorded = new ConcurrentLinkedQueue[(Boolean, Seq[Seq[ByteString]])]()

    val ref: ActorRef[NetworkPeerManagerActor.Command] =
      testKit.spawn(Behaviors.receiveMessage[NetworkPeerManagerActor.Command] {
        case NetworkPeerManagerActor.SendMessageCmd(message, _) =>
          message.underlyingMsg match
            case req: SNAP.GetTrieNodes =>
              serveFrom.get() match
                case Some(served) =>
                  recorded.add((true, req.paths))
                  answerFrom(req, served)
                case None =>
                  recorded.add((false, req.paths))
                  manual.ref ! req
            case _ => ()
          Behaviors.same
        case _ => Behaviors.same
      })

    def answer(req: SNAP.GetTrieNodes, nodes: Seq[ByteString]): Unit =
      coordinator.get() ! TrieNodeHealingCoordinator.TrieNodesResponseMsg(
        SNAP.TrieNodes(requestId = req.requestId, nodes = nodes)
      )

    def answerFrom(req: SNAP.GetTrieNodes, served: Map[Seq[ByteString], ByteString]): Unit =
      answer(req, req.paths.flatMap(served.get))

    /** Every request so far, in arrival order. */
    def requests: Seq[Seq[Seq[ByteString]]] = recorded.asScala.toSeq.map(_._2)

    /** The requests answered automatically (those received while `serveFrom` was set). */
    def autoRequests: Seq[Seq[Seq[ByteString]]] = recorded.asScala.toSeq.collect { case (true, r) => r }
