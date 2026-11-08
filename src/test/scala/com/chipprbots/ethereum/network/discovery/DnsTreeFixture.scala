package com.chipprbots.ethereum.network.discovery

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

import com.chipprbots.ethereum.network.discovery.DnsDiscovery.TxtLookup
import com.chipprbots.ethereum.network.discovery.DnsDiscovery.TxtResult
import com.chipprbots.ethereum.rlp.*

/** An in-memory EIP-1459 tree for tests: no DNS, no network. The hashes are plain labels (`ROOT`, `B0`, `L0_3`) because
  * the walk does not check them, and the ENRs are unsigned for the same reason; only their `ip`, `tcp` and `secp256k1`
  * fields matter. Every leaf has its own IP, so every leaf is a distinct enode.
  *
  * Layout: `<domain>` is the root record, `ROOT` is a branch of `B0..B{branches-1}`, each `B<b>` is a branch of its
  * `L<b>_<l>` leaves, and each leaf is an ENR.
  */
object DnsTreeFixture:

  // secp256k1 generator point G, compressed. A canonical valid point, so no key generation is needed.
  private val compressedKey: Array[Byte] = Array(
    0x02, 0x79, 0xbe, 0x66, 0x7e, 0xf9, 0xdc, 0xbb, 0xac, 0x55, 0xa0, 0x62, 0x95, 0xce, 0x87, 0x0b, 0x07, 0x02, 0x9b,
    0xfc, 0xdb, 0x2d, 0xce, 0x28, 0xd9, 0x59, 0xf2, 0x81, 0x5b, 0x16, 0xf8, 0x17, 0x98
  ).map(_.toByte)

  /** An ENR record for the `i`-th test node: 10.<i / 256>.<i % 256>.1, TCP 30303. */
  def enr(i: Int): String =
    val fields: Seq[RLPEncodeable] = Seq(
      RLPValue(Array.fill(64)(0.toByte)),
      RLPValue(Array(0.toByte)),
      RLPValue("ip".getBytes("UTF-8")),
      RLPValue(Array(10, (i >> 8) & 0xff, i & 0xff, 1).map(_.toByte)),
      RLPValue("tcp".getBytes("UTF-8")),
      RLPValue(Array(0x76.toByte, 0x5f.toByte)),
      RLPValue("secp256k1".getBytes("UTF-8")),
      RLPValue(compressedKey)
    )
    "enr:" + Base64.getUrlEncoder.withoutPadding.encodeToString(encode(RLPList(fields*)))

  /** The enode URL [[enr]] resolves to. */
  def enode(i: Int): String =
    DnsDiscovery
      .parseEnrToEnode(enr(i))
      .fold(reason => throw new IllegalStateException(s"fixture ENR $i does not parse: $reason"), identity)

  def leafName(domain: String, branch: Int, leaf: Int): String = s"L${branch}_$leaf.$domain"

  def branchName(domain: String, branch: Int): String = s"B$branch.$domain"

  def isLeaf(name: String): Boolean = name.startsWith("L")

  /** TXT records for a tree with `branches * leavesPerBranch` nodes, numbered from `firstNode`. */
  def records(domain: String, branches: Int, leavesPerBranch: Int, firstNode: Int = 0): Map[String, TxtResult] =
    def found(record: String): TxtResult = TxtResult.Found(record)
    val branchRecords = (0 until branches).map { b =>
      branchName(domain, b) -> found(
        (0 until leavesPerBranch).map(l => s"L${b}_$l").mkString("enrtree-branch:", ",", "")
      )
    }
    val leafRecords = for
      b <- 0 until branches
      l <- 0 until leavesPerBranch
    yield leafName(domain, b, l) -> found(enr(firstNode + b * leavesPerBranch + l))
    Map(
      domain -> found("enrtree-root:v1 e=ROOT l=LINKS seq=1 sig=AAAA"),
      s"ROOT.$domain" -> found((0 until branches).map(b => s"B$b").mkString("enrtree-branch:", ",", ""))
    ) ++ branchRecords ++ leafRecords

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
