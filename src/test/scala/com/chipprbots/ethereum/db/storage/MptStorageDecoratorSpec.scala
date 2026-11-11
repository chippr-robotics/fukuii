package com.chipprbots.ethereum.db.storage

import java.lang.reflect.Method
import java.lang.reflect.Modifier

import org.apache.pekko.util.ByteString

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.MptStorageDecorator.Removals
import com.chipprbots.ethereum.mpt.LeafNode
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.NibblePath
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C1 (T024): [[MptStorageDecorator]] forwards every member of [[MptStorage]] (data-model 9), and no
  * read or update gets past a wrapper built on it.
  */
class MptStorageDecoratorSpec extends AnyFlatSpec with Matchers:

  private val node: MptNode = LeafNode(ByteString(1, 2), ByteString(3))
  private val hash: Array[Byte] = node.hash
  private val path: NibblePath = NibblePath(0xa, 0xb)
  private val owner: ByteString = ByteString(Array.fill[Byte](32)(7))

  /** Notes every call it receives, by member name. */
  private class Recording(scopedTo: Option[ByteString] = None) extends MptStorage:
    val calls: ArrayBuffer[String] = mutable.ArrayBuffer.empty[String]
    var removed: Seq[MptNode] = Nil
    var replacedAt: Seq[(NibblePath, MptNode)] = Nil
    var absorbedAt: Seq[(NibblePath, MptNode)] = Nil
    val scoped: scala.collection.mutable.Map[Option[ByteString], Recording] =
      mutable.Map.empty[Option[ByteString], Recording]
    def scope: Option[ByteString] = scopedTo

    override def get(nodeId: Array[Byte]): MptNode =
      calls += "get"; node
    override def getAt(at: NibblePath, nodeId: Array[Byte]): MptNode =
      calls += s"getAt(${at.show})"; node
    override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
      calls += "updateNodesInStorage"
      removed = toRemove
      newRoot
    override def updateNodesAt(
        newRoot: Option[MptNode],
        replaced: Seq[(NibblePath, MptNode)],
        absorbed: Seq[(NibblePath, MptNode)]
    ): Option[MptNode] =
      calls += "updateNodesAt"
      replacedAt = replaced
      absorbedAt = absorbed
      newRoot
    override def persist(): Unit = calls += "persist"
    override def multiGetNodes(hashes: Seq[Array[Byte]]): Seq[Option[MptNode]] =
      calls += "multiGetNodes"
      hashes.map(_ => Some(node))
    override def rawNodes(hashes: Seq[ByteString]): Seq[RawNodeRead] =
      calls += "rawNodes"
      hashes.map(_ => RawNodeRead.Absent)
    override def rawNodesAt(requests: Seq[(NibblePath, ByteString)]): Seq[RawNodeRead] =
      calls += "rawNodesAt"
      requests.map(_ => RawNodeRead.Absent)
    override def storeRawNodes(nodes: Seq[(ByteString, Array[Byte])]): Unit = calls += "storeRawNodes"
    override def forOwner(forWhom: Option[ByteString]): MptStorage =
      calls += "forOwner"
      scoped.getOrElseUpdate(forWhom, new Recording(forWhom))
    override def resetOwner(forWhom: ByteString): Unit = calls += "resetOwner"
    // not the default: a wrapper that answered for itself would say true
    override def nodesSharedAcrossTries: Boolean =
      calls += "nodesSharedAcrossTries"
      false

  /** A wrapper that adds nothing. */
  private class Plain(protected val underlying: MptStorage) extends MptStorageDecorator:
    protected def decorate(scoped: MptStorage): MptStorage = new Plain(scoped)

  /** A wrapper that notes what it sees, as a cache, a counter or a recorder would. */
  private class Watching(protected val underlying: MptStorage, val seen: mutable.ArrayBuffer[String])
      extends MptStorageDecorator:
    protected def decorate(scoped: MptStorage): MptStorage = new Watching(scoped, seen)
    override protected def read(nodeId: Array[Byte], at: NibblePath | Null): MptNode =
      seen += (if at == null then "read by hash" else s"read at ${at.nn.show}")
      super.read(nodeId, at)
    override protected def update(newRoot: Option[MptNode], removals: Removals): Option[MptNode] =
      seen += "update"
      super.update(newRoot, removals.none)

  /** The members a trait declares, as the JVM sees them: its public instance methods. The compiler adds static
    * forwarders and the bodies of lambdas beside them, all with a `$` in their name.
    */
  private def declared(owner: Class[?]): Seq[Method] =
    owner.getDeclaredMethods.toSeq.filter { method =>
      Modifier.isPublic(method.getModifiers) && !Modifier.isStatic(method.getModifiers) &&
      !method.isSynthetic && !method.getName.contains("$")
    }

  private def signature(method: Method): String =
    s"${method.getName}(${method.getParameterTypes.map(_.getName).mkString(", ")})"

  // Data-model 9: "the decorator base makes it a compile-time fact". This is the test that keeps it one: a member added
  // to MptStorage with a default body compiles in every wrapper without being forwarded, and would bypass them all.
  "MptStorageDecorator" should "override every member of MptStorage" taggedAs (UnitTest, MPTTest) in {
    val members = declared(classOf[MptStorage]).map(signature).toSet
    val forwarded = declared(classOf[MptStorageDecorator]).map(signature).toSet
    members should not be empty
    withClue("members of MptStorage the decorator does not forward: ") {
      (members -- forwarded) shouldBe empty
    }
  }

  it should "pass every call on to the storage it wraps" taggedAs (UnitTest, MPTTest) in {
    val wrapped = new Recording
    val plain = new Plain(wrapped)

    plain.get(hash) shouldBe node
    plain.getAt(path, hash) shouldBe node
    plain.updateNodesInStorage(Some(node), Seq(node)) shouldBe Some(node)
    wrapped.removed shouldBe Seq(node)
    plain.updateNodesAt(Some(node), Seq(path -> node), Seq(NibblePath(0x1) -> node)) shouldBe Some(node)
    wrapped.replacedAt.map(_._1.show) shouldBe Seq("ab")
    wrapped.absorbedAt.map(_._1.show) shouldBe Seq("1")
    plain.persist()
    plain.multiGetNodes(Seq(hash)) shouldBe Seq(Some(node))
    plain.rawNodes(Seq(ByteString(hash))) shouldBe Seq(RawNodeRead.Absent)
    plain.rawNodesAt(Seq(path -> ByteString(hash))) shouldBe Seq(RawNodeRead.Absent)
    plain.storeRawNodes(Seq(ByteString(hash) -> node.encode))
    plain.resetOwner(owner)
    plain.nodesSharedAcrossTries shouldBe false

    wrapped.calls.toSeq shouldBe Seq(
      "get",
      "getAt(ab)",
      "updateNodesInStorage",
      "updateNodesAt",
      "persist",
      "multiGetNodes",
      "rawNodes",
      "rawNodesAt",
      "storeRawNodes",
      "resetOwner",
      "nodesSharedAcrossTries"
    )
  }

  // One name per member of MptStorage, so that the test above cannot go stale against the trait: a new member fails
  // the reflection test until the decorator forwards it, and fails here until this test calls it.
  it should "be exercised here for every member of MptStorage" taggedAs (UnitTest, MPTTest) in {
    declared(classOf[MptStorage]).map(_.getName).toSet shouldBe Set(
      "get",
      "getAt",
      "updateNodesInStorage",
      "updateNodesAt",
      "persist",
      "multiGetNodes",
      "rawNodes",
      "rawNodesAt",
      "storeRawNodes",
      "forOwner",
      "resetOwner",
      "nodesSharedAcrossTries"
    )
  }

  it should "let no read past a wrapper, by hash or by position" taggedAs (UnitTest, MPTTest) in {
    val wrapped = new Recording
    val seen = mutable.ArrayBuffer.empty[String]
    val watching = new Watching(wrapped, seen)

    watching.get(hash)
    watching.getAt(path, hash)

    seen.toSeq shouldBe Seq("read by hash", "read at ab")
    wrapped.calls.toSeq shouldBe Seq("get", "getAt(ab)")
  }

  it should "let no update past a wrapper, with positions or without" taggedAs (UnitTest, MPTTest) in {
    val wrapped = new Recording
    val seen = mutable.ArrayBuffer.empty[String]
    val watching = new Watching(wrapped, seen)

    // this wrapper withholds removals from the storage it wraps, whichever member the update came through
    watching.updateNodesInStorage(Some(node), Seq(node))
    wrapped.removed shouldBe empty
    watching.updateNodesAt(Some(node), Seq(path -> node), Seq(path -> node))
    wrapped.replacedAt shouldBe empty
    wrapped.absorbedAt shouldBe empty

    seen.toSeq shouldBe Seq("update", "update")
    wrapped.calls.toSeq shouldBe Seq("updateNodesInStorage", "updateNodesAt")
  }

  it should "wrap the view of one trie in the same wrapper" taggedAs (UnitTest, MPTTest) in {
    val wrapped = new Recording
    val seen = mutable.ArrayBuffer.empty[String]
    val watching = new Watching(wrapped, seen)

    val scoped = watching.forOwner(Some(owner))
    scoped should not be theSameInstanceAs(watching)
    scoped shouldBe a[Watching]

    scoped.getAt(path, hash)
    seen.toSeq shouldBe Seq("read at ab")
    wrapped.calls.toSeq shouldBe Seq("forOwner")
    wrapped.scoped(Some(owner)).calls.toSeq shouldBe Seq("getAt(ab)")
    wrapped.scoped(Some(owner)).scope shouldBe Some(owner)
  }

  // A hash-keyed storage answers forOwner with itself (data-model 8): the wrapper stays as it is.
  it should "stay itself over a storage that keeps no tries apart" taggedAs (UnitTest, MPTTest) in {
    val hashKeyed = new MptStorage:
      override def get(nodeId: Array[Byte]): MptNode = node
      override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] = newRoot
      override def persist(): Unit = ()
    val plain = new Plain(hashKeyed)
    (plain.forOwner(Some(owner)) should be).theSameInstanceAs(plain)
    (plain.forOwner(None) should be).theSameInstanceAs(plain)
  }

  "Removals" should "keep their kind when emptied" taggedAs (UnitTest, MPTTest) in {
    Removals.Unpositioned(Seq(node)).none shouldBe Removals.Unpositioned(Nil)
    Removals.Positioned(Seq(path -> node), Seq(path -> node)).none shouldBe Removals.Positioned(Nil, Nil)
  }

  // The defaults are what keeps a hash-keyed storage, which overrides none of the new members, behaving as before.
  "MptStorage's defaults" should "turn the calls with positions into the calls without" taggedAs (
    UnitTest,
    MPTTest
  ) in {
    val calls = mutable.ArrayBuffer.empty[String]
    var removed: Seq[MptNode] = Nil
    val hashKeyed = new MptStorage:
      override def get(nodeId: Array[Byte]): MptNode =
        calls += "get"; node
      override def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] =
        calls += "updateNodesInStorage"
        removed = toRemove
        newRoot
      override def persist(): Unit = ()
      override def rawNodes(hashes: Seq[ByteString]): Seq[RawNodeRead] =
        calls += "rawNodes"
        hashes.map(_ => RawNodeRead.Absent)

    val other: MptNode = LeafNode(ByteString(4), ByteString(5))
    hashKeyed.getAt(path, hash) shouldBe node
    // the absorbed node is NOT among the removals: a hash-keyed storage has never been told of one
    hashKeyed.updateNodesAt(Some(node), Seq(path -> node), Seq(NibblePath(0x1) -> other)) shouldBe Some(node)
    removed shouldBe Seq(node)
    hashKeyed.rawNodesAt(Seq(path -> ByteString(hash))) shouldBe Seq(RawNodeRead.Absent)
    (hashKeyed.forOwner(Some(owner)) should be).theSameInstanceAs(hashKeyed)
    hashKeyed.resetOwner(owner)

    calls.toSeq shouldBe Seq("get", "updateNodesInStorage", "rawNodes")
  }
