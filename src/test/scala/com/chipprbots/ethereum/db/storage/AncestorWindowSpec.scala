package com.chipprbots.ethereum.db.storage

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.testing.Tags.*

/** The window below a block that a hand-off needs (spec 018 C5a part 2; T198's check): the 256 headers `BLOCKHASH`
  * walks, by the hash each child names, and on a proof-of-work chain the six bodies the ommers are checked against.
  */
class AncestorWindowSpec extends AnyFlatSpec with Matchers:

  // a chain of 300 blocks above the genesis
  private val chain: Vector[Block] =
    (BlockHelpers.genesis +: BlockHelpers.generateChain(300, BlockHelpers.genesis)).toVector

  /** What a node holds of the chain: the headers and the bodies, each by hash. */
  private class Held(blocks: Seq[Block]):
    var headers: Map[BlockHash, BlockHeader] = blocks.map(b => b.header.hash -> b.header).toMap
    var bodies: Map[BlockHash, BlockBody] = blocks.map(b => b.header.hash -> b.body).toMap
    def check(at: Int, proofOfWork: Boolean): AncestorWindow.Missing =
      AncestorWindow.check(chain(at).header, proofOfWork, headers.get, bodies.get)
    def dropHeader(at: Int): Unit = headers -= chain(at).header.hash
    def dropBody(at: Int): Unit = bodies -= chain(at).header.hash

  "The window below a block" should "be whole when the 256 headers below it and its six bodies are stored" taggedAs
    UnitTest in {
      val held = new Held(chain)
      held.check(300, proofOfWork = true).isEmpty shouldBe true
      held.check(300, proofOfWork = false).isEmpty shouldBe true
      // what is below the window is not read: block 300 reaches down to block 44
      (0 to 43).foreach(held.dropHeader)
      (0 to 294).foreach(held.dropBody)
      held.check(300, proofOfWork = true).isEmpty shouldBe true
    }

  it should "name the highest header that is not stored, and look no further down" taggedAs UnitTest in {
    val held = new Held(chain)
    held.dropHeader(44) // the lowest the window reaches
    held.check(300, proofOfWork = false) shouldBe
      AncestorWindow.Missing(Some((BigInt(44), chain(44).header.hash)), Nil)
    held.dropHeader(200)
    held.dropHeader(100)
    held.check(300, proofOfWork = false).header shouldBe Some((BigInt(200), chain(200).header.hash))
    // the parent itself
    held.dropHeader(299)
    held.check(300, proofOfWork = false).header shouldBe Some((BigInt(299), chain(299).header.hash))
  }

  it should "take a header for its child's parent only by the hash the child names: the index of another chain's block is no ancestor" taggedAs
    UnitTest in {
      val held = new Held(chain)
      // a header is stored at the parent's hash that is not of the parent's height: a row that is not what it is named
      held.headers += chain(299).header.hash -> chain(150).header
      held.check(300, proofOfWork = false).header shouldBe Some((BigInt(299), chain(299).header.hash))
    }

  it should "name each of the six bodies that is missing on a proof-of-work chain, the block's own among them, and none after the merge" taggedAs
    UnitTest in {
      val held = new Held(chain)
      Seq(300, 297, 295, 294).foreach(held.dropBody)
      // 300 down to 295 are read; 294 is the seventh
      held.check(300, proofOfWork = true) shouldBe
        AncestorWindow.Missing(None, List(chain(300).header, chain(297).header, chain(295).header))
      held.check(300, proofOfWork = false).isEmpty shouldBe true
      held.check(300, proofOfWork = true).describe should include("the bodies of blocks 300, 297, 295")
    }

  it should "ask of a block near the genesis only the ancestors there are" taggedAs UnitTest in {
    val held = new Held(chain)
    // block 3: the headers of 2, 1 and 0, and the bodies of 3, 2, 1 and 0
    held.check(3, proofOfWork = true).isEmpty shouldBe true
    held.dropBody(0)
    held.check(3, proofOfWork = true).bodies shouldBe List(chain(0).header)
    held.dropHeader(0)
    held.check(3, proofOfWork = true) shouldBe AncestorWindow.Missing(Some((BigInt(0), chain(0).header.hash)), Nil)
    // the genesis has nothing below it
    new Held(chain).check(0, proofOfWork = true).isEmpty shouldBe true
  }
