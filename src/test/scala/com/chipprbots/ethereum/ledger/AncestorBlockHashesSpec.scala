package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.testing.Tags.*

/** BLOCKHASH source semantics — core-geth `GetHashFn` (core/evm.go:93-129).
  *
  * Pure: headers come from an in-memory map, and every header read is counted, so the cost claims in
  * [[AncestorBlockHashes]]'s scaladoc are checked, not assumed.
  */
class AncestorBlockHashesSpec extends AnyFlatSpec with Matchers:

  // 0..300 on one chain; a competing chain forks after block 250.
  private val chain: Vector[Block] =
    (BlockHelpers.genesis +: BlockHelpers.generateChain(300, BlockHelpers.genesis)).toVector
  private val sideChain: Vector[Block] = BlockHelpers.generateChain(40, chain(250)).toVector

  private class Harness(headers: Map[BlockHash, BlockHeader], canonical: BigInt => Option[ByteString]):
    var headerReads = 0
    var canonicalReads = 0
    def lookupFor(executing: BlockHeader): AncestorBlockHashes =
      new AncestorBlockHashes(executing.number.value, executing.parentHash, readHeader, readCanonical)
    private def readHeader(hash: BlockHash): Option[BlockHeader] =
      headerReads += 1
      headers.get(hash)
    private def readCanonical(number: BigInt): Option[ByteString] =
      canonicalReads += 1
      canonical(number)

  private def allHeaders: Map[BlockHash, BlockHeader] =
    (chain ++ sideChain).map(b => b.hash -> b.header).toMap

  // The canonical index names the SIDE chain above 250 — the state a partial or reversed reorg leaves behind.
  private def indexNamingSideChain(n: BigInt): Option[ByteString] =
    if n > 250 && n <= 290 then Some(sideChain((n - 251).toInt).hash.value) else chain.lift(n.toInt).map(_.hash.value)

  "AncestorBlockHashes" should "answer the parent without reading any header" taggedAs (UnitTest, VMTest) in {
    val h = new Harness(allHeaders, indexNamingSideChain)
    val lookup = h.lookupFor(chain(300).header)
    lookup(299) shouldBe Some(chain(299).hash.value)
    h.headerReads shouldBe 0
    h.canonicalReads shouldBe 0
  }

  it should "resolve by ancestry where the canonical index names another chain" taggedAs (UnitTest, VMTest) in {
    val h = new Harness(allHeaders, indexNamingSideChain)
    val lookup = h.lookupFor(chain(300).header)
    (251 to 290).foreach { n =>
      lookup(n) shouldBe Some(chain(n).hash.value)
      lookup(n) should not be Some(sideChain(n - 251).hash.value)
    }
    h.canonicalReads shouldBe 0
  }

  it should "resolve a side-chain block by ITS ancestry, below and above the fork point" taggedAs (
    UnitTest,
    VMTest
  ) in {
    // Executing side block 290 while the index names the main chain everywhere.
    val h = new Harness(allHeaders, n => chain.lift(n.toInt).map(_.hash.value))
    val lookup = h.lookupFor(sideChain(39).header)
    lookup(289) shouldBe Some(sideChain(38).hash.value)
    lookup(251) shouldBe Some(sideChain(0).hash.value)
    lookup(250) shouldBe Some(chain(250).hash.value)
    lookup(100) shouldBe Some(chain(100).hash.value)
    h.canonicalReads shouldBe 0
  }

  it should "read each ancestor header at most once per block and cache the walk" taggedAs (UnitTest, VMTest) in {
    val h = new Harness(allHeaders, indexNamingSideChain)
    val lookup = h.lookupFor(chain(300).header)
    // Deepest in-window height for block 300 is 44 (300 - 256); reaching it walks 255 headers (300-1 .. 45).
    lookup(44) shouldBe Some(chain(44).hash.value)
    h.headerReads shouldBe 255
    (44 to 299).foreach(n => lookup(n) shouldBe Some(chain(n).hash.value))
    h.headerReads shouldBe 255
  }

  it should "reach genesis and never look past it" taggedAs (UnitTest, VMTest) in {
    val h = new Harness(allHeaders, _ => fail("canonical index must not be consulted"))
    val lookup = h.lookupFor(chain(3).header)
    lookup(0) shouldBe Some(BlockHelpers.genesis.hash.value)
    h.headerReads shouldBe 2
    lookup(-1) shouldBe None
    h.headerReads shouldBe 2
  }

  it should "answer None for the executing block and anything above it (core-geth: empty hash)" taggedAs (
    UnitTest,
    VMTest
  ) in {
    val h = new Harness(allHeaders, indexNamingSideChain)
    val lookup = h.lookupFor(chain(300).header)
    lookup(300) shouldBe None
    lookup(301) shouldBe None
    h.headerReads shouldBe 0
    h.canonicalReads shouldBe 0
  }

  it should "fall back to the canonical index only beyond a missing ancestor header" taggedAs (UnitTest, VMTest) in {
    val missing = chain(280).hash
    val h = new Harness(allHeaders - missing, n => Some(ByteString(s"index-$n")))
    val lookup = h.lookupFor(chain(300).header)
    // Heights reachable before the gap still come from ancestry: 280's own hash is the parentHash of 281.
    lookup(280) shouldBe Some(chain(280).hash.value)
    h.canonicalReads shouldBe 0
    // Below it the walk cannot continue: the pre-existing index answer is returned, not a zero.
    lookup(279) shouldBe Some(ByteString("index-279"))
    lookup(200) shouldBe Some(ByteString("index-200"))
    // The walk is not retried on every call.
    val readsAfterBreak = h.headerReads
    lookup(150)
    h.headerReads shouldBe readsAfterBreak
  }

  it should "treat a stored header with the wrong number as a broken walk" taggedAs (UnitTest, VMTest) in {
    val corrupt = chain(290).header.copy(number = BlockNumber(7))
    val headers = allHeaders + (chain(290).hash -> corrupt)
    val h = new Harness(headers, n => Some(ByteString(s"index-$n")))
    val lookup = h.lookupFor(chain(300).header)
    lookup(290) shouldBe Some(chain(290).hash.value)
    lookup(289) shouldBe Some(ByteString("index-289"))
  }
