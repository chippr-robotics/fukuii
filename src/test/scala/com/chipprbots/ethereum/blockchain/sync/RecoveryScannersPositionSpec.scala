package com.chipprbots.ethereum.blockchain.sync

import java.security.MessageDigest

import org.apache.pekko.util.ByteString

import scala.util.Try

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.snap.StateValidator
import com.chipprbots.ethereum.db.dataSource.EphemDataSource
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.db.storage.pathstate.PathStateTestKit
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.ShardEnumerator
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C2b (T055): the walks that decide what is healed and what is recovered, on a position store.
  *
  * Every walk here reaches each node by a descent, so it can say where each read is: the account trie under no owner, a
  * storage trie under its account's hash, and the path. Three things are held:
  *
  *   - '''On a hash store nothing changed.''' Each walk's result, in order, is frozen from the code as it was before it
  *     read by position ([[frozen]]).
  *   - '''A position store gives the same verdicts''' for the same state with the same nodes lost.
  *   - '''What only a position store can lose is found.''' Two accounts with one storage root hold two tries there, and
  *     the second can be missing while the first is whole (spec 022 V3). A hash store has that trie once.
  */
class RecoveryScannersPositionSpec extends AnyFlatSpec with Matchers:

  import PathStateTestKit.*
  import RecoveryScannersPositionSpec.*

  /** One state, with the holes each scenario punches in it. */
  final private class Case(val name: String, val state: TwoWays, val codes: EvmCodeStorage):
    private val accountInner = state.stored(None).last
    // a child of the root: the walk for healing looks no deeper, since a node that is there has its subtree
    private val accountChild = state.stored(None).find(position => state.path(position).length == 1)
    private val someStorage = state.positions.keys.flatten.toSeq.sortBy(hex).head
    private val storageInner = state.stored(Some(someStorage)).last
    private val storageRoot = state.stored(Some(someStorage)).head

    /** The holes of each scenario: none; a child of the account trie's root; a node deep in it; a node deep in a
      * storage trie; a storage trie's root; three of them; the root of the state.
      */
    val scenarios: Seq[(String, Seq[state.Position])] = Seq(
      "whole" -> Nil,
      "account-child" -> accountChild.toSeq,
      "account-inner" -> Seq(accountInner),
      "storage-inner" -> Seq(storageInner),
      "storage-root" -> Seq(storageRoot),
      "three-holes" -> Seq(accountInner, storageInner, storageRoot),
      "no-root" -> Seq(state.stored(None).head)
    )

    /** A code store that lacks one of the codes the state names. */
    val codesLackingOne: EvmCodeStorage =
      val lacking = new EvmCodeStorage(EphemDataSource())
      val held = state
        .positions(None)
        .values
        .toSeq
        .flatMap(node => codeHashesIn(node))
        .distinct
        .sortBy(hex)
      held.drop(1).foreach(hash => codes.get(hash).foreach(code => lacking.put(hash, code).commit()))
      lacking

  private def cases: Seq[Case] =
    val hashed = new HashedState
    // the state the world builds has no code: give three accounts some, two of them the same
    val withCode = hashed.change(hashed.root) { world =>
      world
        .saveCode(alice, ByteString("alice's code, which carol has too"))
        .saveCode(carol, ByteString("alice's code, which carol has too"))
        .saveCode(dave, ByteString("dave's code"))
    }
    val chosen = new ChosenState
    // one account, with storage: the root of the state is its leaf, the one shape in which the walk for healing reaches
    // a storage trie
    val single =
      import com.chipprbots.ethereum.domain.Account
      import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
      import com.chipprbots.ethereum.mpt.byteStringSerializer
      val trie = MerklePatriciaTrie[ByteString, Account](chosen.nodes)
        .put(chosen.holder, chosen.accounts.toMap.apply(chosen.holder))
      chosen.nodes.persist()
      ByteString(trie.getRootHash)
    Seq(
      new Case("hashed-keys", new TwoWays(withCode, hashed.nodes, hashed.nodes.load), hashed.codes),
      new Case("chosen-keys", new TwoWays(chosen.root, chosen.nodes, chosen.load), chosen.codes),
      new Case("one-account", new TwoWays(single, chosen.nodes, chosen.load), chosen.codes)
    )

  /** What a scan found: the codes and the storage tries that are missing, and the subtrees it could not read. Or what
    * it threw.
    */
  private type Scanned = Either[String, Found]
  final private case class Found(code: Seq[ByteString], storage: Seq[(ByteString, ByteString)], holes: Long):
    def show: String = s"code ${hexes(code)} storage ${pairs(storage)} holes $holes"

  /** Every walk of the state at `root` on `store`. */
  final private class Walks(root: ByteString, store: MptStorage, codes: EvmCodeStorage):
    private val validator = new StateValidator(store)
    private def scanned(run: CombinedRecoveryScan => Unit): Scanned = thrown {
      val scanning = new CombinedRecoveryScan(store, codes)
      run(scanning)
      Found(scanning.missingBytecodes, scanning.missingStorageTries, scanning.holes)
    }
    private def shards(depth: Int): Vector[ShardEnumerator.Shard] = ShardEnumerator.enumShards(root, store, depth)
    private def shown(depth: Int): String =
      outcome(shards(depth).map(shard => s"${hex(shard.pathPrefix)}:${nodeHash(shard.root)}").mkString(","))

    /** The walks whose answer is the same on either store, by name. */
    val plain: Seq[(String, String)] = Seq(
      "validateAccountTrie" -> either(validator.validateAccountTrie(root))(hexes),
      "validateAllStorageTries" -> either(validator.validateAllStorageTries(root))(hexes),
      "findMissingNodesWithPaths" -> either(validator.findMissingNodesWithPaths(root))(
        _.map((paths, hash) => s"${paths.map(hex).mkString("/")}=${hex(hash)}").mkString(",")
      ),
      "shards-1" -> shown(1),
      "shards-2" -> shown(2)
    )

    /** The scans, which tell storage tries apart by the store they read. */
    val scans: Seq[(String, Scanned)] = Seq(
      "scanFrom" -> scanned(_.scanFrom(root)),
      "scanShards-1" -> scanned(scan => shards(1).foreach(shard => scan.scanShard(shard.root, shard.pathPrefix))),
      "scanShards-2" -> scanned(scan => shards(2).foreach(shard => scan.scanShard(shard.root, shard.pathPrefix))),
      "legacy-storage" ->
        thrown(Found(Nil, StorageRecoveryActor.scanForMissingStorage(root, store, quiet), 0)),
      "legacy-bytecode" ->
        thrown(Found(BytecodeRecoveryActor.scanForMissingBytecodes(root, store, codes, quiet), Nil, 0))
    )

    def scan(name: String): Scanned = scans.toMap.apply(name)

    /** Every answer by its name, as text, in the order and the form the frozen list has them. */
    def verdicts: Seq[(String, String)] =
      val scanText = scans.map {
        case ("legacy-storage", found)  => "legacy-storage" -> found.fold(identity, f => pairs(f.storage))
        case ("legacy-bytecode", found) => "legacy-bytecode" -> found.fold(identity, f => hexes(f.code))
        case (name, found)              => name -> found.fold(identity, _.show)
      }.toMap
      val text = plain.toMap ++ scanText
      Seq(
        "validateAccountTrie",
        "validateAllStorageTries",
        "findMissingNodesWithPaths",
        "scanFrom",
        "shards-1",
        "shards-2",
        "scanShards-1",
        "scanShards-2",
        "legacy-storage",
        "legacy-bytecode"
      ).map(name => name -> text(name))

  "Each walk, on a hash store" should "answer as it did before it read by position, in the same order" taggedAs UnitTest in {
    val actual: Seq[(String, String)] =
      for
        c <- cases
        (scenario, holes) <- c.scenarios
        codes <- Seq("codes" -> c.codes, "codes-lacking-one" -> c.codesLackingOne)
        (walk, verdict) <- new Walks(c.state.root, c.state.hashesWithout(holes*), codes._2).verdicts
      yield s"${c.name}/$scenario/${codes._1}/$walk" -> digest(verdict)
    actual should have size frozen.size.toLong
    val changed = actual.filterNot((key, value) => frozen.get(key).contains(value))
    withClue(
      changed.map((key, value) => s"$key $value (frozen: ${frozen.getOrElse(key, "none")})").mkString("\n", "\n", "\n")
    ) {
      changed shouldBe empty
    }
  }

  // The same state, the same nodes lost, on the position store. A hash store that loses a node loses it for every trie
  // that holds it, so the rows are deleted wherever that node is. The validator's walks and the shards then answer the
  // same, word for word. The scans answer the same but for what they must: a storage trie two accounts hold is one trie
  // on a hash store, missing once and fetched once, and two tries on a position store, each missing and each to fetch.
  "Each walk, on a position store with the same nodes lost" should
    "answer what it answers on the hash store, and name every account whose storage trie is missing" taggedAs UnitTest in {
      for
        c <- cases
        (scenario, holes) <- c.scenarios
        (codeStore, codes) <- Seq("codes" -> c.codes, "codes-lacking-one" -> c.codesLackingOne)
      do
        withClue(s"${c.name}/$scenario/$codeStore: ") {
          val onHashes = new Walks(c.state.root, c.state.hashesWithout(holes*), codes)
          val onPositions = new Walks(c.state.root, c.state.positionsWithout(holes.flatMap(c.state.everywhere)*), codes)
          onPositions.plain shouldBe onHashes.plain
          onPositions.scans.zip(onHashes.scans).foreach { case ((name, byPosition), (_, byHash)) =>
            withClue(s"$name: ") {
              // one entry for each account on the position store; the first account of each root is the hash store's
              byPosition.map(found => found.copy(storage = firstOfEachRoot(found.storage))) shouldBe byHash
              byPosition.foreach { found =>
                found.storage.map(_._1).distinct shouldBe found.storage.map(_._1)
              }
            }
          }
        }
    }

  // Spec 022 V3, as a scan meets it. Two accounts hold the same storage. On a position store each has rows of its own,
  // and here the second account's are gone while the first's are whole. A scan that had seen the root under the first
  // account, and skipped the second for it, would never find this: nor would anything else, until a block read it.
  "A storage trie that only one of two accounts with its root has lost" should
    "be found by every scan and by the validator, under the account that lost it" taggedAs UnitTest in {
      val chosen = new ChosenState
      val state = new TwoWays(chosen.root, chosen.nodes, chosen.load)
      val whole = new Walks(chosen.root, state.onPositions, chosen.codes)
      whole.scans.foreach((name, found) => withClue(s"$name, nothing lost: ")(found.map(_.storage) shouldBe Right(Nil)))

      for (loser, keeper) <- Seq(chosen.twin -> chosen.holder, chosen.holder -> chosen.twin) do
        withClue(s"${hex(loser).take(6)} lost its storage: ") {
          // the root, so the scans see it (they look at roots); and a node below it, which the validator walks to
          val root = state.stored(Some(loser)).head
          val inner = state.stored(Some(loser)).last
          val rootHash = state.hashAt(root)

          val rootLost = new Walks(chosen.root, state.positionsWithout(root), chosen.codes)
          Seq("scanFrom", "scanShards-1", "scanShards-2", "legacy-storage").foreach { name =>
            withClue(s"$name: ")(rootLost.scan(name).map(_.storage) shouldBe Right(Seq(loser -> rootHash)))
          }
          rootLost.plain.toMap.apply("validateAllStorageTries") shouldBe s"Right([${hex(rootHash)}])"

          val innerLost = new Walks(chosen.root, state.positionsWithout(inner), chosen.codes)
          innerLost.plain.toMap.apply("validateAllStorageTries") shouldBe s"Right([${hex(state.hashAt(inner))}])"
          // the scans look at roots only: a node below one is the validator's to find
          innerLost.scan("scanFrom").map(_.storage) shouldBe Right(Nil)

          // the keeper's own trie is not what was read for the loser
          state.positions(Some(keeper)) shouldBe state.positions(Some(loser))
        }

      // and on a hash store the two are one trie: lost, it is missing once, under the first account that names it
      val onHashes = new Walks(chosen.root, state.hashesWithout(state.stored(Some(chosen.twin)).head), chosen.codes)
      onHashes.scan("scanFrom").map(_.storage.map(_._1)) shouldBe Right(Seq(chosen.holder))
    }

  // The parallel scan merges what its shards found, and tells storage tries apart again as it does: two accounts with
  // one root can sit in two shards. Here they do (the holder and its twin part below the second branch), and both have
  // lost their storage. A hash store has one trie to fetch. A position store has two.
  "The parallel scan, merging its shards" should
    "keep both accounts of a storage root on a position store, and one on a hash store" taggedAs UnitTest in {
      import com.chipprbots.ethereum.db.storage.AppStateStorage
      val chosen = new ChosenState
      val state = new TwoWays(chosen.root, chosen.nodes, chosen.load)
      val root = state.stored(Some(chosen.holder)).head
      def scan(store: MptStorage): Seq[(ByteString, ByteString)] =
        new CombinedRecoveryScanner(
          chosen.root,
          () => store,
          chosen.codes,
          new AppStateStorage(EphemDataSource()),
          concurrency = 2,
          shardDepth = 2
        ).run().missingStorageTries

      // two shards: the two accounts are not under one
      val shards = ShardEnumerator.enumShards(chosen.root, state.onPositions, 2).map(_.pathPrefix)
      def shardOf(account: ByteString) =
        shards.filter(prefix =>
          com.chipprbots.ethereum.mpt.HexPrefix.bytesToNibbles(account.toArray).startsWith(prefix.toArray)
        )
      shardOf(chosen.holder) should not be shardOf(chosen.twin)

      val rootHash = state.hashAt(root)
      scan(state.positionsWithout(state.everywhere(root)*)).sortBy((account, _) => hex(account)) shouldBe
        Seq(chosen.holder -> rootHash, chosen.twin -> rootHash)
      scan(state.hashesWithout(root)).map(_._2) shouldBe Seq(rootHash)
    }

  // One trie can hold the same subtree at two places: not in a state, whose keys are hashes, but nothing in a trie
  // forbids it. A hash store has those nodes once, and a walk that has seen one need not look again. A position store
  // has a row for each place, and the second can be gone. The validator skips a node it has seen only where a node is
  // its hash.
  "The validator, on a trie that holds one subtree at two positions" should
    "find the row that is missing at the second, which a walk that skipped what it had seen would not" taggedAs UnitTest in {
      import com.chipprbots.ethereum.domain.Account
      import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
      import com.chipprbots.ethereum.mpt.PositionTestKit.ReferenceStore
      import com.chipprbots.ethereum.mpt.byteStringSerializer
      val nodes = new ReferenceStore
      // 7a…1, 7a…2 and 7b…1, 7b…2, the same two accounts under each: the subtrees at 7a and 7b are node for node the same
      val pair = Seq(1 -> Account(nonce = 1, balance = 10), 2 -> Account(nonce = 2, balance = 20))
      val keyed = for (prefix <- Seq("7a", "7b"); (last, account) <- pair) yield chosenKey(prefix, last) -> account
      val trie = keyed.foldLeft(MerklePatriciaTrie[ByteString, Account](nodes)) { case (t, (k, a)) => t.put(k, a) }
      nodes.persist()
      val root = ByteString(trie.getRootHash)
      val state = new TwoWays(root, nodes, hash => nodes.load(hash))

      def at(nibbles: Byte*) = state.stored(None).find(position => state.path(position).toSeq == nibbles).get
      val first = at(7, 0xa)
      val second = at(7, 0xb)
      state.hashAt(first) shouldBe state.hashAt(second)

      new StateValidator(state.onPositions).validateAccountTrie(root) shouldBe Right(Nil)
      for lost <- Seq(first, second) do
        withClue(s"the row at ${hex(ByteString(state.path(lost)))} is gone: ") {
          new StateValidator(state.positionsWithout(lost)).validateAccountTrie(root) shouldBe
            Right(Seq(state.hashAt(lost)))
        }
      // a hash store that lost the node lost it at both places, and the walk says so at each, as it always has: what it
      // skips is a node it has READ
      new StateValidator(state.hashesWithout(first)).validateAccountTrie(root) shouldBe
        Right(Seq(state.hashAt(first), state.hashAt(first)))
    }

  // An account's storage trie is found, on a position store, by the hash the account is kept under. A key of a state
  // is 32 bytes. One that is not names no trie: the account trie is corrupt. It was about to be read as a storage trie
  // that is missing, and queued for healing, which fetches nothing that mends it.
  "An account kept under a key that is not 32 bytes" should
    "be a corrupt trie to the validator on a position store, and walked as before on a hash store" taggedAs UnitTest in {
      import com.chipprbots.ethereum.domain.Account
      import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
      import com.chipprbots.ethereum.mpt.PositionTestKit.ReferenceStore
      import com.chipprbots.ethereum.mpt.byteStringSerializer
      val chosen = new ChosenState
      val nodes = chosen.nodes.asInstanceOf[ReferenceStore]
      // the holder's account, with its storage, under a key one byte short
      val short = chosenKey("ab", 1).take(31)
      val trie = MerklePatriciaTrie[ByteString, Account](nodes)
        .put(short, chosen.accounts.toMap.apply(chosen.holder))
        .put(chosenKey("cd", 2), Account(nonce = 1, balance = 1))
      nodes.persist()
      val root = ByteString(trie.getRootHash)

      // a hash store reads the storage trie by its root, whatever the account is called
      new StateValidator(nodes).validateAllStorageTries(root) shouldBe Right(Nil)
      new StateValidator(nodes).findMissingNodesWithPaths(root) shouldBe Right(Nil)

      // the account trie alone as rows: the storage trie has no owner to be written under
      val source = EphemDataSource()
      val anchor = block(1, root)
      seedPositions(
        source,
        Map(
          Option.empty[ByteString] -> com.chipprbots.ethereum.mpt.PositionTestKit.RawTrieWalk
            .storedPositions(root.toArray, nodes.load)
        ),
        anchor
      )
      val onPositions = new com.chipprbots.ethereum.db.storage.pathstate.PathStateStorage(
        new com.chipprbots.ethereum.db.storage.PathNodeStorage(source),
        new com.chipprbots.ethereum.db.storage.AppStateStorage(source)
      ).readOnly(anchor)
      val verdict = new StateValidator(onPositions).validateAllStorageTries(root)
      verdict.left.toOption.getOrElse(fail(s"not a failure: $verdict")) should
        (startWith("Corrupt trie node, not a hole").and(include("62 nibbles")))
      // the account trie itself is whole
      new StateValidator(onPositions).validateAccountTrie(root) shouldBe Right(Nil)
    }

  // A walk holds one view across every read it makes. When the disk moves away from that view, a read says so. That is
  // not a node that is missing: taken as one, a node that is there would be queued for healing, or a storage trie that
  // is whole fetched again. The validator answers that the walk failed; a scan ends, and its caller settles nothing.
  "A state that moves under a walk" should "end the walk as a failure, wherever it falls, and never as a hole" taggedAs
    UnitTest in {
      val chosen = new ChosenState
      val state = new TwoWays(chosen.root, chosen.nodes, chosen.load)
      val names = new Walks(chosen.root, state.onPositions, chosen.codes).verdicts.map(_._1)
      val staleFault = "Left(State fault, not a hole: "

      for name <- names do
        // how many reads this one walk makes, then the state moved after each count of them
        def verdictOn(store: MptStorage): String =
          new Walks(chosen.root, store, chosen.codes).verdicts.toMap.apply(name)
        val whole = verdictOn(state.onPositions)
        // every walk of `Walks` reads the same store, so the count is of them all: more than this one needs
        val counted = new StaleAfter(state.onPositions, healthy = Int.MaxValue)
        new Walks(chosen.root, counted, chosen.codes).verdicts
        val reads = counted.readsAsked
        reads should be > 20
        val verdictsSeen =
          (0 until reads).map(healthy => verdictOn(new StaleAfter(state.onPositions, healthy))).distinct
        withClue(s"$name: ${verdictsSeen.map(_.take(60))} ") {
          // the whole answer (the move fell after this walk's last read), or a failure: nothing in between
          all(verdictsSeen) should (
            be(whole).or(startWith(staleFault)).or(be("threw StaleStateViewException"))
          )
          verdictsSeen.exists(v => v.startsWith(staleFault) || v == "threw StaleStateViewException") shouldBe true
        }
    }

object RecoveryScannersPositionSpec:

  /** The log of a scan that is not looked at: the logger is off in the test configuration. */
  val quiet: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("RecoveryScannersPositionSpec.scan")

  def hex(bytes: ByteString): String = bytes.map("%02x".format(_)).mkString
  def hexes(all: Seq[ByteString]): String = all.map(hex).mkString("[", ",", "]")
  def pairs(all: Seq[(ByteString, ByteString)]): String =
    all.map((a, b) => s"${hex(a)}>${hex(b)}").mkString("[", ",", "]")

  def either[A](result: Either[String, A])(show: A => String): String =
    result.fold(error => s"Left($error)", value => s"Right(${show(value)})")

  /** What `run` finds, or the class of what it throws. */
  def thrown[A](run: => A): Either[String, A] =
    try Right(run)
    catch case e: Throwable => Left(s"threw ${named(e.getClass)}")

  /** The class of an exception, by the nearest name it has: a missing node that says where it was looked for is an
    * anonymous subclass of the missing-node exception.
    */
  def named(c: Class[?]): String = if c.getSimpleName.nonEmpty then c.getSimpleName else named(c.getSuperclass)

  /** The first account of each storage root, in order: how a store that has a trie once reports it missing. */
  def firstOfEachRoot(storage: Seq[(ByteString, ByteString)]): Seq[(ByteString, ByteString)] =
    val seen = scala.collection.mutable.HashSet.empty[ByteString]
    storage.filter((_, root) => seen.add(root))

  /** What `run` answers, or the class of what it throws: a walk that ends in an exception is a verdict too. */
  def outcome(run: => String): String =
    try run
    catch case e: Throwable => s"threw ${named(e.getClass)}"

  def nodeHash(node: MptNode): String = hex(ByteString(node.hash))

  def digest(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8")).take(8).map("%02x".format(_)).mkString

  /** The code hashes named by the account leaves a stored node of the account trie holds (embedded or not). */
  def codeHashesIn(node: ByteString): Seq[ByteString] =
    import com.chipprbots.ethereum.rlp
    import com.chipprbots.ethereum.rlp.RLPList
    import com.chipprbots.ethereum.rlp.RLPValue
    import com.chipprbots.ethereum.mpt.HexPrefix
    val emptyCode = ByteString(com.chipprbots.ethereum.domain.Account.EmptyCodeHash.value.toArray)
    def account(value: Array[Byte]): Seq[ByteString] =
      Try(rlp.rawDecode(value)).toOption.toSeq.flatMap {
        case RLPList(_, _, _, RLPValue(code)) if code.length == 32 && ByteString(code) != emptyCode =>
          Seq(ByteString(code))
        case _ => Nil
      }
    def in(decoded: rlp.RLPEncodeable): Seq[ByteString] = decoded match
      case RLPList(items*) if items.size == 17 =>
        items.take(16).flatMap {
          case embedded: RLPList => in(embedded)
          case _                 => Nil
        }
      case RLPList(RLPValue(compact), RLPValue(value)) if HexPrefix.decode(compact)._2 => account(value)
      case RLPList(RLPValue(_), embedded: RLPList)                                     => in(embedded)
      case _                                                                           => Nil
    in(rlp.rawDecode(node.toArray))

  /** The first eight bytes of the SHA-256 of each walk's answer, taken from the code before it read by position (#1797,
    * 2026-11-11): `src/test/resources/spec018/walker-verdicts-hash-store.txt`.
    */
  lazy val frozen: Map[String, String] =
    val source = scala.io.Source.fromResource("spec018/walker-verdicts-hash-store.txt")
    try
      source
        .getLines()
        .filterNot(line => line.startsWith("#") || line.isBlank)
        .map { line =>
          val Array(key, value) = line.split(' '): @unchecked
          key -> value
        }
        .toMap
    finally source.close()
