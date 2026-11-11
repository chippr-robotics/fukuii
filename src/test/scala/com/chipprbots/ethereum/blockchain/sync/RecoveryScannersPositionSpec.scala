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
  private final class Case(val name: String, val state: TwoWays, val codes: EvmCodeStorage):
    private val accountInner = state.stored(None).last
    // a child of the root: the walk for healing looks no deeper, since a node that is there has its subtree
    private val accountChild = state.stored(None).find(position => state.path(position).length == 1)
    private val someStorage = state.positions.keys.flatten.toSeq.sortBy(hex).head
    private val storageInner = state.stored(Some(someStorage)).last
    private val storageRoot = state.stored(Some(someStorage)).head

    /** The holes of each scenario: none; a child of the account trie's root; a node deep in it; a node deep in a storage
      * trie; a storage trie's root; three of them; the root of the state.
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
      val held = state.positions(None).values.toSeq
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
      new Case("chosen-keys", new TwoWays(chosen.root, chosen.nodes, chosen.nodes.load), chosen.codes),
      new Case("one-account", new TwoWays(single, chosen.nodes, chosen.nodes.load), chosen.codes)
    )

  /** What every walk answers for the state at `root` on `store`, each by its name. */
  private def verdicts(root: ByteString, store: MptStorage, codes: EvmCodeStorage): Seq[(String, String)] =
    val validator = new StateValidator(store)
    def scan(run: CombinedRecoveryScan => Unit): String = outcome {
      val scanning = new CombinedRecoveryScan(store, codes)
      run(scanning)
      s"code ${hexes(scanning.missingBytecodes)} storage ${pairs(scanning.missingStorageTries)} holes ${scanning.holes}"
    }
    def shards(depth: Int): Try[Vector[ShardEnumerator.Shard]] = Try(ShardEnumerator.enumShards(root, store, depth))
    def shardScan(depth: Int): String = outcome {
      val scanning = new CombinedRecoveryScan(store, codes)
      shards(depth).get.foreach(shard => scanning.scanShard(shard.root, shard.pathPrefix))
      s"code ${hexes(scanning.missingBytecodes)} storage ${pairs(scanning.missingStorageTries)} holes ${scanning.holes}"
    }
    Seq(
      "validateAccountTrie" -> either(validator.validateAccountTrie(root))(hexes),
      "validateAllStorageTries" -> either(validator.validateAllStorageTries(root))(hexes),
      "findMissingNodesWithPaths" -> either(validator.findMissingNodesWithPaths(root))(
        _.map((paths, hash) => s"${paths.map(hex).mkString("/")}=${hex(hash)}").mkString(",")
      ),
      "scanFrom" -> scan(_.scanFrom(root)),
      "shards-1" -> outcome(shards(1).get.map(shard => s"${hex(shard.pathPrefix)}:${nodeHash(shard.root)}").mkString(",")),
      "shards-2" -> outcome(shards(2).get.map(shard => s"${hex(shard.pathPrefix)}:${nodeHash(shard.root)}").mkString(",")),
      "scanShards-1" -> shardScan(1),
      "scanShards-2" -> shardScan(2),
      "legacy-storage" -> outcome(pairs(StorageRecoveryActor.scanForMissingStorage(root, store, quiet))),
      "legacy-bytecode" -> outcome(hexes(BytecodeRecoveryActor.scanForMissingBytecodes(root, store, codes, quiet)))
    )

  "Each walk, on a hash store" should "answer as it did before it read by position, in the same order" taggedAs UnitTest in {
    val actual: Seq[(String, String)] =
      for
        c <- cases
        (scenario, holes) <- c.scenarios
        codes <- Seq("codes" -> c.codes, "codes-lacking-one" -> c.codesLackingOne)
        (walk, verdict) <- verdicts(c.state.root, c.state.hashesWithout(holes*), codes._2)
      yield s"${c.name}/$scenario/${codes._1}/$walk" -> digest(verdict)
    actual should have size frozen.size.toLong
    val changed = actual.filterNot((key, value) => frozen.get(key).contains(value))
    withClue(changed.map((key, value) => s"$key $value (frozen: ${frozen.getOrElse(key, "none")})").mkString("\n", "\n", "\n")) {
      changed shouldBe empty
    }
  }

object RecoveryScannersPositionSpec:

  /** The log of a scan that is not looked at: the logger is off in the test configuration. */
  val quiet: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("RecoveryScannersPositionSpec.scan")

  def hex(bytes: ByteString): String = bytes.map("%02x".format(_)).mkString
  def hexes(all: Seq[ByteString]): String = all.map(hex).mkString("[", ",", "]")
  def pairs(all: Seq[(ByteString, ByteString)]): String = all.map((a, b) => s"${hex(a)}>${hex(b)}").mkString("[", ",", "]")

  def either[A](result: Either[String, A])(show: A => String): String =
    result.fold(error => s"Left($error)", value => s"Right(${show(value)})")

  /** What `run` answers, or the class of what it throws: a walk that ends in an exception is a verdict too. */
  def outcome(run: => String): String =
    try run
    catch case e: Throwable => s"threw ${e.getClass.getSimpleName}"

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
        case RLPList(_, _, _, RLPValue(code)) if code.length == 32 && ByteString(code) != emptyCode => Seq(ByteString(code))
        case _                                                                                      => Nil
      }
    def in(decoded: rlp.RLPEncodeable): Seq[ByteString] = decoded match
      case RLPList(items*) if items.size == 17 =>
        items.take(16).flatMap {
          case embedded: RLPList => in(embedded)
          case _                 => Nil
        }
      case RLPList(RLPValue(compact), RLPValue(value)) if HexPrefix.decode(compact)._2 => account(value)
      case RLPList(RLPValue(_), embedded: RLPList)                                      => in(embedded)
      case _                                                                            => Nil
    in(rlp.rawDecode(node.toArray))

  /** The first eight bytes of the SHA-256 of each walk's answer, taken from the code before it read by position
    * (#1797, 2026-11-11): `src/test/resources/spec018/walker-verdicts-hash-store.txt`.
    */
  lazy val frozen: Map[String, String] =
    val source = scala.io.Source.fromResource("spec018/walker-verdicts-hash-store.txt")
    try
      source.getLines().filterNot(line => line.startsWith("#") || line.isBlank).map { line =>
        val Array(key, value) = line.split(' '): @unchecked
        key -> value
      }.toMap
    finally source.close()
