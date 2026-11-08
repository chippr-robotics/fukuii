package com.chipprbots.ethereum.network.discovery

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko.util.ByteString

import scala.collection.concurrent.TrieMap
import scala.util.Using

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.ECPublicKeyParameters

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.network.discovery.DnsDiscovery.TxtLookup
import com.chipprbots.ethereum.network.discovery.DnsDiscovery.TxtResult
import com.chipprbots.ethereum.network.discovery.Eip1459.DnsTree
import com.chipprbots.ethereum.rlp.*

/** An in-memory, properly signed EIP-1459 tree for tests: no DNS, no network. Everything a client verifies is real:
  * every entry is published under the label that is the hash of its text, every ENR carries a valid EIP-778 `v4`
  * signature of its own node key, and the root is signed by the tree's key. Keys are derived from small integer seeds,
  * so a run is reproducible.
  *
  * Layout: `<domain>` is the root record, `e=` is a branch of `branches` branches that each hold `leavesPerBranch`
  * ENRs, and `l=` is a branch of the tree's links (empty when there are none).
  */
object DnsTreeFixture:

  def keyPair(seed: Int): AsymmetricCipherKeyPair = crypto.keyPairFromPrvKey(BigInt(seed))

  def compressedKey(keyPair: AsymmetricCipherKeyPair): Array[Byte] =
    keyPair.getPublic.asInstanceOf[ECPublicKeyParameters].getQ.getEncoded(true)

  private def fixed32(n: BigInt): Array[Byte] =
    val raw = n.toByteArray.dropWhile(_ == 0)
    Array.fill(32 - raw.length)(0.toByte) ++ raw

  private def utf8(s: String): RLPValue = RLPValue(s.getBytes("UTF-8"))

  private def ipv4(i: Int): Array[Byte] = Array(10, (i >> 8) & 0xff, i & 0xff, 1).map(_.toByte)

  private def sign(hash: Array[Byte], keyPair: AsymmetricCipherKeyPair): ECDSASignature =
    ECDSASignature.sign(hash, keyPair)

  private def base64Url(bytes: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** The RLP content an ENR signature covers: `[seq, k1, v1, ...]`, keys in EIP-778's sorted order. */
  private def enrContent(i: Int, withScheme: Boolean): Seq[RLPEncodeable] =
    val scheme: Seq[RLPEncodeable] = if withScheme then Seq(utf8("id"), utf8("v4")) else Seq.empty
    Seq(RLPValue(Array(1.toByte))) ++ scheme ++ Seq(
      utf8("ip"),
      RLPValue(ipv4(i)),
      utf8("secp256k1"),
      RLPValue(compressedKey(keyPair(1000 + i))),
      utf8("tcp"),
      RLPValue(Array(0x76.toByte, 0x5f.toByte)) // 30303
    )

  private def enrText(signature: Array[Byte], content: Seq[RLPEncodeable]): String =
    "enr:" + base64Url(encode(RLPList((RLPValue(signature) +: content)*)))

  private val enrCache = TrieMap.empty[Int, String]

  /** A valid ENR for the `i`-th test node: 10.<i / 256>.<i % 256>.1, TCP 30303, signed by node key `1000 + i`. */
  def enr(i: Int): String = enrCache.getOrElseUpdate(i, signedEnr(i, withScheme = true, corrupt = false))

  /** An ENR for the `i`-th node that fails verification: a signature with one bit flipped. */
  def enrWithBadSignature(i: Int): String = signedEnr(i, withScheme = true, corrupt = true)

  /** An ENR for the `i`-th node that carries a valid signature but names no identity scheme. */
  def enrWithoutScheme(i: Int): String = signedEnr(i, withScheme = false, corrupt = false)

  private def signedEnr(i: Int, withScheme: Boolean, corrupt: Boolean): String =
    val content = enrContent(i, withScheme)
    val signature = sign(crypto.kec256(encode(RLPList(content*))), keyPair(1000 + i))
    val rs = fixed32(signature.r) ++ fixed32(signature.s)
    if corrupt then rs(5) = (rs(5) ^ 0x01).toByte
    enrText(rs, content)

  /** The enode URL [[enr]] resolves to. */
  def enode(i: Int): String =
    DnsDiscovery
      .parseEnrToEnode(enr(i))
      .fold(reason => throw new IllegalStateException(s"fixture ENR $i does not parse: $reason"), identity)

  /** A root record for the given subtree labels, signed by `keyPair` (the recovery id is appended as go-ethereum does).
    */
  def signedRoot(eroot: String, lroot: String, seq: Long, keyPair: AsymmetricCipherKeyPair): String =
    val text = s"${Eip1459.RootPrefix} e=$eroot l=$lroot seq=$seq"
    val signature = sign(crypto.kec256(text.getBytes("UTF-8")), keyPair)
    val recoveryId = (signature.v.toInt - ECDSASignature.negativePointSign).toByte
    s"$text sig=${base64Url(fixed32(signature.r) ++ fixed32(signature.s) :+ recoveryId)}"

  private def labelled(domain: String, text: String): (String, TxtResult) =
    s"${Eip1459.entryLabel(text)}.$domain" -> TxtResult.Found(text)

  /** The URL of the tree [[Tree]] would build for `domain` and `keySeed`, without building it (to link trees in a
    * ring).
    */
  def urlOf(domain: String, keySeed: Int): DnsTree =
    DnsTree(domain, ByteString(compressedKey(keyPair(keySeed))))

  /** A tree of `branches * leavesPerBranch` nodes numbered from `firstNode`, signed by key `keySeed` at sequence `seq`,
    * linking to `links`. `enrOverrides` replaces the ENR text of a node (by number) before the tree is hashed, so the
    * tree is consistent and only that ENR is off.
    */
  final class Tree(
      val domain: String,
      val branches: Int,
      val leavesPerBranch: Int,
      val firstNode: Int = 0,
      val seq: Long = 1L,
      val keySeed: Int = 1,
      val links: Seq[DnsTree] = Nil,
      enrOverrides: Map[Int, String] = Map.empty
  ):
    val key: AsymmetricCipherKeyPair = keyPair(keySeed)
    val url: DnsTree = DnsTree(domain, ByteString(compressedKey(key)))

    private val leafTexts: Vector[Vector[String]] = Vector.tabulate(branches, leavesPerBranch) { (b, l) =>
      val node = firstNode + b * leavesPerBranch + l
      enrOverrides.getOrElse(node, enr(node))
    }
    private val leafLabels: Vector[Vector[String]] = leafTexts.map(_.map(Eip1459.entryLabel))
    private val branchTexts: Vector[String] = leafLabels.map(_.mkString(Eip1459.BranchPrefix, ",", ""))

    /** Text of the `e=` subtree root: a branch of the per-group branches. */
    val eRootText: String = branchTexts.map(Eip1459.entryLabel).mkString(Eip1459.BranchPrefix, ",", "")

    /** Text of the `l=` subtree root: a branch of the link entries, empty without links. */
    val lRootText: String = links.map(link => Eip1459.entryLabel(link.url)).mkString(Eip1459.BranchPrefix, ",", "")

    private val entries: Seq[String] = leafTexts.flatten ++ branchTexts ++ Seq(eRootText, lRootText) ++ links.map(_.url)

    /** Every TXT record of the tree, root included. */
    val records: Map[String, TxtResult] =
      entries.map(labelled(domain, _)).toMap +
        (domain -> TxtResult.Found(
          signedRoot(Eip1459.entryLabel(eRootText), Eip1459.entryLabel(lRootText), seq, key)
        ))

    def rootRecord: String = records(domain) match
      case TxtResult.Found(record) => record
      case other                   => throw new IllegalStateException(s"no root: $other")

    def leafName(branch: Int, leaf: Int): String = s"${leafLabels(branch)(leaf)}.$domain"

    def branchName(branch: Int): String = labelled(domain, branchTexts(branch))._1

    def eRootName: String = labelled(domain, eRootText)._1

    def lRootName: String = labelled(domain, lRootText)._1

    private val leafNames: Set[String] =
      (for b <- 0 until branches; l <- 0 until leavesPerBranch yield leafName(b, l)).toSet

    def isLeaf(name: String): Boolean = leafNames.contains(name)

    /** The tree's records with different `e=` and `l=` subtree roots, the root re-signed (with the tree's key) over
      * them. `more` entries are added under their labels. For trees that are valid but not the shape [[Tree]] builds.
      */
    def withRoots(eText: String, lText: String = lRootText, more: Seq[String] = Nil): Map[String, TxtResult] =
      records ++ (Seq(eText, lText) ++ more).map(labelled(domain, _)) +
        (domain -> TxtResult.Found(signedRoot(Eip1459.entryLabel(eText), Eip1459.entryLabel(lText), seq, key)))

  /** A lookup over `records`. `beforeAnswer` runs on the querying thread first and may block, throw, or record. */
  final class Stub(
      val records: Map[String, TxtResult],
      beforeAnswer: String => Unit = _ => ()
  ) extends TxtLookup:
    val asked = new AtomicInteger(0)

    override def query(name: String): TxtResult =
      val _ = asked.incrementAndGet()
      beforeAnswer(name)
      records.getOrElse(name, TxtResult.NotFound)

  /** Several trees answered by one lookup, as DNS answers for many domains. */
  def stubOf(trees: Tree*): Stub = new Stub(trees.flatMap(_.records).toMap)

  /** The records of a tree captured from the live DNS, as `name<TAB>record` lines (see `src/test/resources/dns`). */
  def snapshot(resource: String): Map[String, TxtResult] =
    Using.resource(getClass.getResourceAsStream(resource))(in =>
      scala.io.Source
        .fromInputStream(in, "UTF-8")
        .getLines()
        .filter(_.nonEmpty)
        .map(_.split("\t", 2) match
          case Array(name, record) => name -> (TxtResult.Found(record): TxtResult)
          case other               => throw new IllegalStateException(s"bad snapshot line: ${other.mkString("|")}")
        )
        .toMap
    )
