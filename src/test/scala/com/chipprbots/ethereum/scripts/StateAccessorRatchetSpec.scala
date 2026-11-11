package com.chipprbots.ethereum.scripts

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

import scala.jdk.CollectionConverters.*
import scala.util.Using

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, T054: the allow-list ratchet of the state's entry points, `scripts/check-state-accessors.sh`.
  *
  * The position store answers a read that names where the node is. A reader that has only a hash (a root handed to an
  * accessor, a batch read, a visitor that is given a hash node and no position) cannot be served by it. Until the
  * track's last slice both stores exist, and nothing in the type system stops a new caller from taking the old door.
  * The script counts every use of those doors in the main sources and compares the counts with a list in which each
  * line carries its reason: a use that is not listed fails, and so does a line that lists more than is there, so the
  * list can only get shorter.
  *
  * The script is driven here on made-up source trees, one per way it can fail, and then on this repository.
  */
class StateAccessorRatchetSpec extends AnyFlatSpec with Matchers:

  private val script: Path = Path.of("scripts/check-state-accessors.sh").toAbsolutePath
  private val allowlist: Path = Path.of("scripts/state-accessor-allowlist.txt").toAbsolutePath

  final private case class Outcome(exit: Int, output: String)

  private def run(args: String*): Outcome =
    val process = new ProcessBuilder(("bash" +: script.toString +: args)*).redirectErrorStream(true).start()
    val output = new String(process.getInputStream.readAllBytes(), UTF_8)
    Outcome(process.waitFor(), output)

  /** A source tree with `sources` under `src/main/scala` and `listed` as its allow-list, checked by the script. */
  private def check(sources: Map[String, String], listed: String, extra: Map[String, String] = Map.empty): Outcome =
    val root = Files.createTempDirectory("state-accessor-ratchet")
    try
      (sources.map((file, text) => s"src/main/scala/$file" -> text) ++ extra + ("allowlist.txt" -> listed)).foreach {
        (file, text) =>
          val path = root.resolve(file)
          Files.createDirectories(path.getParent)
          Files.writeString(path, text)
      }
      run("--root", root.toString, "--allowlist", root.resolve("allowlist.txt").toString)
    finally Using.resource(Files.walk(root))(_.iterator.asScala.toSeq.reverse.foreach(Files.delete))

  private val reader = "a/Reader.scala" ->
    """package a
      |class Reader(blockchain: Blockchain):
      |  def account(n: BigInt) = blockchain.getBackingMptStorage(n)
      |  def other(n: BigInt) = blockchain.getBackingMptStorage(n + 1)
      |""".stripMargin

  private val readerListed = "backing-mpt 2 src/main/scala/a/Reader.scala reads the account of a block by its root"

  "The state accessor ratchet" should "pass a tree whose every use is listed with its count and its reason" taggedAs
    UnitTest in {
      val outcome = check(Map(reader), s"# a comment, then a blank line\n\n$readerListed\n")
      withClue(outcome.output)(outcome.exit shouldBe 0)
    }

  it should "fail on a use in a file that is not listed, and name the file, the entry point and the line" taggedAs
    UnitTest in {
      val added = "b/Walker.scala" -> "package b\nclass Walker(s: StateStorage):\n  val nodes = s.getReadOnlyStorage\n"
      val outcome = check(Map(reader, added), readerListed)
      outcome.exit shouldBe 1
      outcome.output should include("NOT LISTED: readonly in src/main/scala/b/Walker.scala")
      // and the line the list would need, so that the list is what gets edited and not the check
      outcome.output should include("the line to add:  readonly 1 src/main/scala/b/Walker.scala")
      outcome.output should include("src/main/scala/b/Walker.scala:3:")
      (outcome.output should not).include("a/Reader.scala")
    }

  it should "fail on a listed file that uses another entry point, which its line does not cover" taggedAs UnitTest in {
    val more = "a/Reader.scala" -> (reader._2 + "  def nodes = blockchain.getReadOnlyMptStorage()\n")
    val outcome = check(Map(more), readerListed)
    outcome.exit shouldBe 1
    outcome.output should include("NOT LISTED: readonly-mpt in src/main/scala/a/Reader.scala")
    outcome.output should include("src/main/scala/a/Reader.scala:5:")
    (outcome.output should not).include("backing-mpt")
  }

  it should "fail on more uses than the line allows" taggedAs UnitTest in {
    val more = "a/Reader.scala" -> (reader._2 + "  def third(n: BigInt) = blockchain.getBackingMptStorage(n + 2)\n")
    val outcome = check(Map(more), readerListed)
    outcome.exit shouldBe 1
    outcome.output should include("MORE THAN LISTED: backing-mpt in src/main/scala/a/Reader.scala: 3 found, 2 allowed")
    outcome.output should include("the line's count becomes:  backing-mpt 3 src/main/scala/a/Reader.scala")
    // and it shows the uses, so that the new one can be found
    Seq(3, 4, 5).foreach(line => outcome.output should include(s"src/main/scala/a/Reader.scala:$line:"))
  }

  // The list only shrinks: a use that is gone takes its allowance with it, or the next one to arrive would be free.
  it should "fail on fewer uses than the line allows, and on a line whose file is gone" taggedAs UnitTest in {
    val fewer = check(Map(reader), readerListed.replace(" 2 ", " 3 "))
    fewer.exit shouldBe 1
    fewer.output should include("FEWER THAN LISTED: backing-mpt in src/main/scala/a/Reader.scala: 2 found, 3 allowed")
    fewer.output should include("lower the line's count:  backing-mpt 2 src/main/scala/a/Reader.scala")

    val gone = check(Map(reader), s"$readerListed\nreadonly 1 src/main/scala/b/Gone.scala it was read here once\n")
    gone.exit shouldBe 1
    gone.output should include("NO LONGER USED: readonly in src/main/scala/b/Gone.scala")
    gone.output should include("remove the line:  readonly 1 src/main/scala/b/Gone.scala")
  }

  it should "fail on a line with no reason" taggedAs UnitTest in {
    val outcome = check(Map(reader), "backing-mpt 2 src/main/scala/a/Reader.scala\n")
    outcome.exit shouldBe 1
    outcome.output should include("NO REASON")
  }

  it should "fail on a line it cannot read: an unknown entry point, a count that is no number, a file listed twice" taggedAs
    UnitTest in {
      val unknown = check(Map(reader), s"$readerListed\nby-magic 1 src/main/scala/a/Reader.scala a reason\n")
      unknown.exit shouldBe 1
      unknown.output should include("UNREADABLE: `by-magic` is not an entry point")

      val noNumber = check(Map(reader), "backing-mpt two src/main/scala/a/Reader.scala a reason\n")
      noNumber.exit shouldBe 1
      noNumber.output should include("UNREADABLE: `two` is not a count above zero")

      val zero = check(Map(reader), s"$readerListed\nreadonly 0 src/main/scala/a/Reader.scala a reason\n")
      zero.exit shouldBe 1
      zero.output should include("UNREADABLE: `0` is not a count above zero")

      val twice = check(Map(reader), s"$readerListed\n$readerListed\n")
      twice.exit shouldBe 1
      twice.output should include("LISTED TWICE: backing-mpt in src/main/scala/a/Reader.scala")
    }

  it should "count a line of code and not a line of comment, and each use on a line" taggedAs UnitTest in {
    val commented = "a/Reader.scala" ->
      """package a
        |/** Reads through [[Blockchain.getBackingMptStorage]], as the importer does.
        |  * See also getBackingMptStorage in the trait.
        |  */
        |class Reader(blockchain: Blockchain):
        |  // getBackingMptStorage is by root
        |  def both(n: BigInt) = (blockchain.getBackingMptStorage(n), blockchain.getBackingMptStorage(n + 1))
        |""".stripMargin
    val outcome = check(Map(commented), readerListed)
    withClue(outcome.output)(outcome.exit shouldBe 0)
  }

  // A map is often called `storage` too. A store's `get` is looked for where a store or a trie is named.
  it should "count a `get` on a name a store has only in a file that names a store or a trie" taggedAs UnitTest in {
    val map = "a/Memo.scala" -> "package a\nclass Memo(storage: java.util.HashMap[Key, Value]):\n  storage.get(root)\n"
    val unlistedMap = check(Map(map), "")
    withClue(unlistedMap.output)(unlistedMap.exit shouldBe 0)

    val store = "a/Walk.scala" -> "package a\nclass Walk(storage: MptStorage):\n  storage.get(root)\n"
    val unlistedStore = check(Map(store), "")
    unlistedStore.exit shouldBe 1
    unlistedStore.output should include("NOT LISTED: get-by-hash in src/main/scala/a/Walk.scala")
  }

  it should "look at the main sources only" taggedAs UnitTest in {
    val test =
      "src/test/scala/a/ReaderSpec.scala" -> "class ReaderSpec:\n  val s = blockchain.getBackingMptStorage(1)\n"
    val outcome = check(Map(reader), readerListed, extra = Map(test))
    withClue(outcome.output)(outcome.exit shouldBe 0)
  }

  // Each entry point by the name the list gives it, with a use it must count and a near miss it must not.
  private val entryPoints: Seq[(String, String, String)] = Seq(
    ("backing-mpt", "blockchain.getBackingMptStorage(number)", "blockchain.getBackingStorages(number)"),
    ("readonly-mpt", "blockchain.getReadOnlyMptStorage()", "blockchain.getReadOnlyMpt()"),
    ("backing", "stateStorage.getBackingStorage(number)", "stateStorage.getBackingStore(number)"),
    ("readonly", "stateStorage.getReadOnlyStorage", "stateStorage.getReadOnlyStore"),
    ("bulk-load", "stateStorage.getBulkLoadStorage(number)", "stateStorage.getBulkLoad(number)"),
    ("stage-block", "blockchain.stageBlockState(number)", "blockchain.stageBlocks(number)"),
    ("stage-block", "stateStorage.stageBlock(number)", "val stageBlock = 1"),
    ("prefetch-reader", "stateStorage.prefetchReader(budget, onLoaded)", "val prefetchReader = reader"),
    ("node-by-hash", "blockchainReader.getMptNodeByHash(hash)", "blockchainReader.getMptNode(hash)"),
    ("node-by-hash", "stateStorage.getNode(hash)", "stateStorage.getNodes(hash)"),
    ("get-by-hash", "mptStorage.get(stateRoot.toArray)", "evmCodeStorage.get(codeHash)"),
    ("get-by-hash", "(storage: MptStorage) => storage.get(hashNode.hash)", "blockHeadersStorage.get(hash)"),
    ("get-by-hash", "(hashStore: MptStorage) => hashStore.get(root.toArray)", "storage.getAt(path, hash)"),
    ("multi-get", "storage.multiGetNodes(hashes)", "storage.multiGet(hashes)"),
    ("raw-nodes", "storage.rawNodes(hashes)", "val rawNodes = Seq.empty"),
    ("raw-nodes", "storage.rawNodesAt(requests)", "storage.rawNodesOf(requests)"),
    ("raw-store", "nodes.storeRawNodes(batch)", "nodes.storeRawNodesOf(batch)"),
    ("raw-store", "stateStorage.saveNode(hash, bytes, number)", "stateStorage.saveNodes(batch)"),
    ("raw-store", "stateStorage.repairNode(hash, bytes, number)", "pathState.repairNodeAt(position, hash, bytes)"),
    ("raw-store", "stateStorage.deleteBadNode(hash, () => true)", "pathState.deleteBadNodeAt(position, hash)"),
    ("snap-store", "stateIngest.trieStore(pivot)", "val trieStore = store"),
    ("snap-store", "getOrCreateMptStorage(pivot)", "getOrCreateStorage(pivot)"),
    (
      "store-handed",
      "def storeOf(pivot: BigInt): MptStorage = stores(pivot)",
      "def check(store: MptStorage): Unit = ()"
    ),
    ("store-handed", "def nodes: MptStorage", "def validatorFactory: MptStorage => StateValidator"),
    ("storage-mpt", "domain.storageMpt(root, storage)", "val storageMpt = trie"),
    ("visit-hash", "override def visitHash(hashNode: HashNode): HashNodeResult[Unit] = ???", "visitor.visitHash(node)"),
    ("trie-of", "MerklePatriciaTrie[Array[Byte], Account](root, storage)", "val t: MerklePatriciaTrie[K, V] = make()"),
    (
      "trie-of",
      "MerklePatriciaTrie[Address, Account](root.toArray, nodes.forOwner(None))",
      "wrap[Address, Account, MerklePatriciaTrie[Address, Account]](trie)"
    ),
    ("trie-of", "MerklePatriciaTrie(source)", "MerklePatriciaTrie.EmptyRootHash"),
    ("store-built", "new SerializingMptStorage(nodeStorage)", "SerializingMptStorage.apply"),
    // Scala 3 builds a class without `new`
    ("store-built", "SerializingMptStorage(nodeStorage, cache)", "classOf[SerializingMptStorage]"),
    ("store-built", "new ReadOnlyNodeStorage(nodeStorage)", "val readOnlyNodeStorage = 1")
  )

  entryPoints.foreach { (name, use, nearMiss) =>
    it should s"count `$use` as $name, and not `$nearMiss`" taggedAs UnitTest in {
      val file = "a/Use.scala" -> s"package a\nobject Use:\n  $use\n  $nearMiss\n"
      val listed = check(Map(file), s"$name 1 src/main/scala/a/Use.scala a reason\n")
      withClue(listed.output)(listed.exit shouldBe 0)

      val unlisted = check(Map(file), "")
      unlisted.exit shouldBe 1
      unlisted.output should include(s"NOT LISTED: $name in src/main/scala/a/Use.scala")
      unlisted.output should include("src/main/scala/a/Use.scala:3:")
      (unlisted.output should not).include("Use.scala:4")
    }
  }

  it should "print what a tree's list has to hold, one line for each file and entry point" taggedAs UnitTest in {
    val root = Files.createTempDirectory("state-accessor-inventory")
    try
      val file = root.resolve("src/main/scala/a/Reader.scala")
      Files.createDirectories(file.getParent)
      Files.writeString(file, reader._2 + "  def nodes = blockchain.getReadOnlyMptStorage()\n")
      val outcome = run("--root", root.toString, "--inventory")
      outcome.exit shouldBe 0
      outcome.output.linesIterator.toSeq shouldBe Seq(
        "backing-mpt 2 src/main/scala/a/Reader.scala",
        "readonly-mpt 1 src/main/scala/a/Reader.scala"
      )
    finally Using.resource(Files.walk(root))(_.iterator.asScala.toSeq.reverse.foreach(Files.delete))
  }

  // A check that finds no sources would pass on nothing.
  it should "refuse a tree with no main sources, and an allow-list that is not there" taggedAs UnitTest in {
    val root = Files.createTempDirectory("state-accessor-empty")
    try
      run("--root", root.toString, "--allowlist", allowlist.toString).exit shouldBe 2
      Files.createDirectories(root.resolve("src/main/scala"))
      run("--root", root.toString, "--allowlist", root.resolve("none.txt").toString).exit shouldBe 2
      run("--no-such-option").exit shouldBe 2
    finally Using.resource(Files.walk(root))(_.iterator.asScala.toSeq.reverse.foreach(Files.delete))
  }

  "This repository" should "use the state's entry points only where its allow-list says, and as often" taggedAs
    UnitTest in {
      val outcome = run()
      withClue(outcome.output)(outcome.exit shouldBe 0)
    }

  it should "list the definitions themselves, so that the check is known to look where they are" taggedAs UnitTest in {
    val lines = Files.readAllLines(allowlist).asScala.filterNot(l => l.isBlank || l.startsWith("#"))
    val stateStorage = "src/main/scala/com/chipprbots/ethereum/db/storage/StateStorage.scala"
    lines.count(_.split("\\s+")(2) == stateStorage) should be >= 4
    lines.map(_.split("\\s+")(0)).toSet shouldBe entryPoints.map(_._1).toSet
  }
