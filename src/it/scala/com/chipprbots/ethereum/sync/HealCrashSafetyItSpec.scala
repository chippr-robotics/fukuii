package com.chipprbots.ethereum.sync

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.util.Try

import cats.effect.unsafe.implicits.global

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.actors.HealCrashChild
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.db.dataSource.RocksDbConfig
import com.chipprbots.ethereum.db.dataSource.RocksDbDataSource
import com.chipprbots.ethereum.db.storage.ExcludedAccount
import com.chipprbots.ethereum.db.storage.FastSyncNodeStorage
import com.chipprbots.ethereum.db.storage.HeapEntry
import com.chipprbots.ethereum.db.storage.Namespaces
import com.chipprbots.ethereum.db.storage.NodeStorage
import com.chipprbots.ethereum.db.storage.PathNodeStorage
import com.chipprbots.ethereum.db.storage.SnapSyncProgressStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.mpt.BranchNode
import com.chipprbots.ethereum.mpt.ExtensionNode
import com.chipprbots.ethereum.mpt.HashNode
import com.chipprbots.ethereum.mpt.HexPrefix
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 022 slice 2, T021 (US3, FR-001 to FR-004): crash safety of healing under `kill -9`, on a real RocksDB.
  *
  * The invariant: whatever healing has put on disk, no present node has an absent child. Before slice 2 a served node
  * was written at once and its children requested after, so a crash between the two left exactly that: a present node
  * over a hole, which only a full-trie walk could find.
  *
  * Method: a child JVM (`HealCrashChild`) heals a synthetic trie with the real coordinator over a real RocksDB and
  * stops at a kill point, printing `KP:<point>`. This JVM then sends SIGKILL (`destroyForcibly`; the exit status 137
  * proves the signal), reopens the database and audits every node stored in it: each hash-referenced child must be
  * present (Path: at its own position, with the right hash; Hash: by hash, under the pruning mode's encoding), and an
  * account leaf's storage root likewise. Nothing waits on time: the child signals on stdout, which this JVM reads line
  * by line.
  *
  * Parameters: the storage scheme; the fixture (`account`: branches, an extension and leaves; `storage`: an account
  * leaf over a storage trie, so a Path chain commit spans both trie column families and the audit checks the storage
  * root; `twin`: two accounts with one storage trie, aliased positions); and `pruning.mode`, for the Hash scheme only
  * (the Path scheme writes the path store directly whatever the pruning mode, so a Path `inmemory` run would repeat the
  * `basic` one, vault V4 (a)).
  *
  * Kill points (see `HealCrashChild`): after a node is held, inside a commit write before and after it is applied, and
  * in the first commit write after a re-peg that writes a node, with nodes held (`repeg-partial`) or the old trie
  * healed (`repeg-full`); under Path also in the first write after a re-peg that only deletes (`repeg-delete`); and
  * mid-excluded-set write (T032-T034): inside the fsynced write of an entry, before and after it is applied
  * (`excluded-write`, `excluded-written`), and under Path right after a heal start's position deletes
  * (`excluded-delete`). Those three also check the set itself: an entry is there whole, or not at all.
  *
  * Under in-memory pruning (Hash) the invariant is NOT claimed (spec.md, after FR-004; vault V3): the node cache, two
  * entries here, writes what it evicts one node at a time, least recently used first, so a kill can leave a present
  * node over an absent child. Those runs check that the kill point is reached and that eviction put nodes on disk, and
  * report the audit's result without asserting it; the pruned verification is off under that mode, and the full
  * verification walk decides completion.
  */
class HealCrashSafetyItSpec extends AnyFlatSpec with Matchers:

  private val fixtures = Seq("account", "storage", "twin")
  private val basePoints = Seq("held", "chain-before", "chain-after", "repeg-partial", "repeg-full")
  private val excludedPoints = Seq("excluded-write", "excluded-written")

  private val cells: Seq[(String, String, String, String)] =
    for
      (scheme, prunings, points) <- Seq(
        ("path", Seq("basic"), (basePoints :+ "repeg-delete") ++ excludedPoints :+ "excluded-delete"),
        ("hash", Seq("basic"), basePoints ++ excludedPoints),
        ("hash", Seq("inmemory"), basePoints)
      )
      fixture <- fixtures
      pruning <- prunings
      point <- points
    yield (scheme, fixture, pruning, point)

  for (scheme, fixture, pruning, point) <- cells do
    val claim =
      if pruning == "inmemory" then "evict nodes to disk (the invariant is not claimed under in-memory pruning)"
      else "leave no present node with an absent child"
    "Healing under kill -9" should s"$claim: $scheme, $fixture trie, pruning $pruning, kill at $point" taggedAs (
      IntegrationTest,
      DatabaseTest,
      SlowTest
    ) in {
      val dir = Files.createTempDirectory(s"heal-crash-$scheme-$fixture-$pruning-$point")
      val (signal, output, exit) = runAndKill(dir, scheme, pruning, fixture, point)
      withClue(s"child output:\n${output.mkString("\n")}\n") {
        signal shouldBe s"KP:$point"
        exit shouldBe 137 // SIGKILL
      }
      val audit = auditStore(dir, scheme, pruning)
      val (oldTrie, newTrie) = HealCrashChild.fixture(fixture)
      if point.startsWith("excluded") then
        val entries = excludedEntries(dir)
        withClue(s"the excluded set after a kill at $point: ") {
          if point == "excluded-write" then entries shouldBe empty
          else entries.map(_.subject) shouldBe Seq(HealCrashChild.excludedSubject(fixture, oldTrie))
        }
      withClue(s"${audit.nodes} node(s) on disk; violations:\n${audit.violations.mkString("\n")}\n") {
        // Where the kill lands after a write that put nodes on disk, the audit had nodes to check (eye F10: the
        // re-peg-with-held-nodes point included, whose earlier subtree commit is on disk).
        if Set("chain-after", "repeg-partial", "repeg-full")(point) || point.startsWith("excluded") then
          audit.nodes should be > 0
        if point == "excluded-delete" then
          // Every position on the key went in one batch: the root with the rest, and the audit above holds without it.
          audit.present should not contain oldTrie.root.hash
        if pruning == "inmemory" then
          info(s"in-memory pruning: ${audit.violations.size} present node(s) over an absent child (not asserted)")
        else
          audit.violations shouldBe empty
          // vault V4 (e): repeg-full is killed right after the first write of new nodes; old nodes alone would satisfy
          // `nodes > 0`, so the new root itself must be on disk.
          if point == "repeg-full" then audit.present should contain(newTrie.root.hash)
      }
    }

  // ---- the child ------------------------------------------------------------------------------------------------------

  /** Start the child, wait for its kill-point line, SIGKILL it. Returns the line, all output, and the exit status. */
  private def runAndKill(
      dir: Path,
      scheme: String,
      pruning: String,
      fixture: String,
      point: String
  ): (String, Seq[String], Int) =
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val command = Seq(
      java,
      "-Xmx512m",
      "-XX:+IgnoreUnrecognizedVMOptions",
      "-XX:+UnlockDiagnosticVMOptions",
      "-XX:-ReduceAllocationMerges",
      "-cp",
      System.getProperty("java.class.path"),
      "com.chipprbots.ethereum.blockchain.sync.snap.actors.HealCrashChild",
      dir.toAbsolutePath.toString,
      scheme,
      pruning,
      fixture,
      point
    )
    val process = new ProcessBuilder(command*).redirectErrorStream(true).start()
    val lines = new LinkedBlockingQueue[String]()
    val EndOfOutput = "\u0000EOF"
    val reader = new Thread(() =>
      val in = new BufferedReader(new InputStreamReader(process.getInputStream))
      // The kill closes the stream under the reader: that is the end of the output, not an error (vault V4 (i)).
      try Iterator.continually(in.readLine()).takeWhile(_ != null).foreach(lines.put)
      catch case _: IOException => ()
      lines.put(EndOfOutput)
    )
    reader.setDaemon(true)
    reader.start()

    val output = mutable.ArrayBuffer.empty[String]
    var signal: Option[String] = None
    while signal.isEmpty do
      // A bound on a child that hangs, not a wait for anything to happen: the signal ends the wait.
      Option(lines.poll(120, TimeUnit.SECONDS)) match
        case None              => signal = Some("TIMEOUT")
        case Some(EndOfOutput) => signal = Some("EXITED")
        case Some(line) =>
          output += line
          if line.startsWith("KP:") || line.startsWith("ERR:") then signal = Some(line)
    process.destroyForcibly()
    process.waitFor()
    reader.join(TimeUnit.SECONDS.toMillis(10))
    lines.forEach(l => if l != EndOfOutput then output += l)
    (signal.get, output.toSeq, process.exitValue())

  // ---- the audit ------------------------------------------------------------------------------------------------------

  /** `present`: the hashes of the well-formed nodes on disk. */
  final private case class Audit(nodes: Int, violations: Seq[String], present: Set[ByteString])

  private def auditStore(dir: Path, scheme: String, pruning: String): Audit =
    val source = RocksDbDataSource(rocksConfig(dir.toAbsolutePath.toString), Namespaces.nsSeq)
    try if scheme == "path" then auditPath(new PathNodeStorage(source)) else auditHash(source, pruning)
    finally source.close()

  private def excludedEntries(dir: Path): Seq[ExcludedAccount] =
    val source = RocksDbDataSource(rocksConfig(dir.toAbsolutePath.toString), Namespaces.nsSeq)
    try new SnapSyncProgressStorage(source).excludedPage(None, 100)
    finally source.close()

  private def hex(b: ByteString): String = b.map("%02x".format(_)).mkString

  /** The hash-referenced children of `node` at `nibbles`, as (child nibbles, child hash), and its storage root if it is
    * an account leaf whose storage is not empty: `(account hash, storage root)`. With `positioned` false (the Hash
    * scheme, where a node has no position) any leaf whose value is an account counts, and the account hash is empty.
    */
  private def children(
      node: MptNode,
      nibbles: Array[Byte],
      positioned: Boolean = true
  ): (Seq[(Array[Byte], ByteString)], Option[(ByteString, ByteString)]) =
    node match
      case b: BranchNode =>
        (
          b.children.zipWithIndex.collect { case (h: HashNode, i) =>
            (nibbles :+ i.toByte, ByteString(h.hashNode))
          }.toSeq,
          None
        )
      case e: ExtensionNode =>
        e.next match
          case h: HashNode => (Seq((nibbles ++ e.sharedKey.toArray, ByteString(h.hashNode))), None)
          case _           => (Seq.empty, None)
      case l: LeafNode =>
        val all = nibbles ++ l.key.toArray
        val storage =
          if positioned && all.length != 64 then None
          else
            Try(Account(l.value)).toOption.flatMap(_.toOption).collect {
              case a if a.storageRoot != Account.EmptyStorageRootHash =>
                val accountHash =
                  if positioned then ByteString(all.grouped(2).map(g => ((g(0) << 4) | g(1)).toByte).toArray)
                  else ByteString.empty
                (accountHash, a.storageRoot.value)
            }
        (Seq.empty, storage)
      case _ => (Seq.empty, None)

  private def auditPath(pns: PathNodeStorage): Audit =
    val violations = mutable.ArrayBuffer.empty[String]
    val present = mutable.Set.empty[ByteString]
    var nodes = 0
    def presentAt(owner: Option[ByteString], nibbles: Array[Byte], hash: ByteString): Boolean =
      owner
        .fold(pns.readAccountNode(nibbles))(pns.readStorageNode(_, nibbles))
        .exists(rlp => ByteString(kec256(rlp)) == hash)
    def check(owner: Option[ByteString], key: Array[Byte], rlp: Array[Byte]): Unit =
      nodes += 1
      val nibbles = HexPrefix.decode(key)._1
      val where = s"${owner.fold("account")(o => s"storage ${hex(o.take(4))}")} ${hex(ByteString(key))}"
      Try(MptTraversals.decodeNode(rlp)).toOption match
        case None => violations += s"$where: does not decode"
        case Some(node) =>
          present += ByteString(kec256(rlp))
          val (kids, storageRoot) = children(node, nibbles)
          kids.foreach { case (childNibbles, hash) =>
            if !presentAt(owner, childNibbles, hash) then
              violations += s"$where: child ${hex(ByteString(childNibbles))} (${hex(hash.take(4))}) absent"
          }
          storageRoot.foreach { case (accountHash, root) =>
            if !presentAt(Some(accountHash), Array.empty, root) then
              violations += s"$where: storage root ${hex(root.take(4))} of ${hex(accountHash.take(4))} absent"
          }
    pns.foreachAccountNodeChunk(1000)(_.foreach { case (k, v) => check(None, k, v) })
    pns.foreachStorageNodeChunk(1000)(_.foreach { case (k, v) =>
      check(Some(ByteString(k.take(32))), k.drop(32), v)
    })
    Audit(nodes, violations.toSeq, present.toSet)

  private def auditHash(source: RocksDbDataSource, pruning: String): Audit =
    val nodeStorage = new NodeStorage(source)
    val counted = new FastSyncNodeStorage(nodeStorage, 0)
    // The node bytes under `hash`, unwrapped from the pruning mode's encoding, only when they hash to it (CF `n` also
    // holds reference-count snapshots and death rows, which are not nodes).
    def nodeBytes(hash: ByteString): Option[Array[Byte]] =
      val encoded =
        if pruning == "basic" then Try(counted.get(hash)).toOption.flatten
        else nodeStorage.get(hash).flatMap(raw => Try(HeapEntry.fromBytes(raw).nodeEncoded).toOption)
      encoded.filter(e => ByteString(kec256(e)) == hash)
    val keys = source
      .iterate(Namespaces.NodeNamespace)
      .compile
      .toList
      .unsafeRunSync()
      .collect { case Right((k, _)) => ByteString(k) }
      .filter(_.length == 32)
    val violations = mutable.ArrayBuffer.empty[String]
    val present = mutable.Set.empty[ByteString]
    var nodes = 0
    keys.foreach { key =>
      nodeBytes(key).foreach { rlp =>
        nodes += 1
        present += key
        Try(MptTraversals.decodeNode(rlp)).toOption.foreach { node =>
          val (kids, storageRoot) = children(node, Array.empty, positioned = false)
          (kids.map(_._2) ++ storageRoot.map(_._2)).foreach { hash =>
            if nodeBytes(hash).isEmpty then violations += s"node ${hex(key.take(4))}: child ${hex(hash.take(4))} absent"
          }
        }
      }
    }
    Audit(nodes, violations.toSeq, present.toSet)

  private def rocksConfig(dbPath: String): RocksDbConfig =
    new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = false
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = false
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 33554432
