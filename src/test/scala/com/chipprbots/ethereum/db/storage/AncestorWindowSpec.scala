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
  * walks, by the hash each child names, and on a proof-of-work chain the bodies the ommers are checked against: the
  * block's and the four below it, where their headers name ommers (B-302).
  */
class AncestorWindowSpec extends AnyFlatSpec with Matchers:

  /** An ommers hash that is not the empty list's: a header that names ommers. */
  private val SomeOmmers = BlockHash(org.apache.pekko.util.ByteString(Array.fill[Byte](32)(7)))

  // a chain of 300 blocks above the genesis, every block above the genesis naming ommers
  private val chain: Vector[Block] =
    (BlockHelpers.genesis +: BlockHelpers.generateChain(300, BlockHelpers.genesis)).toVector
      .foldLeft(Vector.empty[Block]) { (built, block) =>
        val header =
          if block.header.number.value == 0 then block.header
          else block.header.copy(parentHash = built.last.header.hash, ommersHash = SomeOmmers)
        built :+ block.copy(header = header)
      }

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

  it should "name each of the five bodies that is missing on a proof-of-work chain, the block's own among them, and none after the merge" taggedAs
    UnitTest in {
      val held = new Held(chain)
      Seq(300, 297, 296, 295).foreach(held.dropBody)
      // 300 down to 296 are read; 295 is the sixth, whose ommers no block above 300 could name (B-302)
      held.check(300, proofOfWork = true) shouldBe
        AncestorWindow.Missing(None, List(chain(300).header, chain(297).header, chain(296).header))
      held.check(300, proofOfWork = false).isEmpty shouldBe true
      held.check(300, proofOfWork = true).describe should include("the bodies of blocks 300, 297, 296")
    }

  // B-302 (core-geth VerifyUncles): the check of ommers reads a body only where its header names ommers
  it should "not ask for the body of a block whose header names no ommers" taggedAs UnitTest in {
    val plain = (BlockHelpers.genesis +: BlockHelpers.generateChain(20, BlockHelpers.genesis)).toVector
    plain.drop(1).forall(_.header.ommersHash.value == BlockHeader.EmptyOmmers) shouldBe true
    val held = new Held(plain):
      override def check(at: Int, proofOfWork: Boolean): AncestorWindow.Missing =
        AncestorWindow.check(plain(at).header, proofOfWork, headers.get, bodies.get)
    plain.indices.foreach(at => held.bodies -= plain(at).header.hash)
    held.check(20, proofOfWork = true).isEmpty shouldBe true
  }

  it should "ask of a block near the genesis only the ancestors there are" taggedAs UnitTest in {
    val held = new Held(chain)
    // block 3: the headers of 2, 1 and 0, and the bodies of 3, 2 and 1; the genesis names no ommers
    held.check(3, proofOfWork = true).isEmpty shouldBe true
    held.dropBody(0)
    held.check(3, proofOfWork = true).isEmpty shouldBe true
    held.dropBody(1)
    held.check(3, proofOfWork = true).bodies shouldBe List(chain(1).header)
    held.dropHeader(0)
    held.check(3, proofOfWork = true) shouldBe
      AncestorWindow.Missing(Some((BigInt(0), chain(0).header.hash)), List(chain(1).header))
    // the genesis has nothing below it
    new Held(chain).check(0, proofOfWork = true).isEmpty shouldBe true
  }
