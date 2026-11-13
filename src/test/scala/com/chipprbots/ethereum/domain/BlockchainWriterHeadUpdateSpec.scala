package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.BlockHelpers
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.testing.Tags.*

/** Spec 018 Track C, C4b part 2b: the batch that makes a block the canonical head is built apart from its commit
  * ([[BlockchainWriter.canonicalHeadUpdate]]), so that a move of the state can write it in its own batch. With the
  * guard off nothing changes: [[BlockchainWriter.promoteToCanonicalHead]] writes exactly what the builder builds, and
  * building writes nothing.
  */
class BlockchainWriterHeadUpdateSpec extends AnyFlatSpec with Matchers:

  /** A chain of six blocks, and a branch of three from its second, in one store. */
  final private class Chains(of: Option[Chains] = None) extends EphemBlockchainTestSetup:
    val main: List[Block] = of.fold(BlockHelpers.generateChain(6, BlockHelpers.genesis))(_.main)
    val branch: List[Block] = of.fold(BlockHelpers.generateChain(3, main(1)))(_.branch)
    // a block above the head, stored and not on the chain
    val above: Block = of.fold(BlockHelpers.generateBlock(main.last))(_.above)
    // two blocks above the head that the index names though the best block is below them: what an execution ahead of
    // the head leaves (engine_newPayload writes entries above the head)
    val leftovers: List[Block] = of.fold(BlockHelpers.generateChain(2, main.last))(_.leftovers)
    def store(): Unit =
      blockchainWriter.save(BlockHelpers.genesis, Nil, ChainWeight.zero, saveAsBestBlock = true)
      main.foreach(b => blockchainWriter.save(b, Nil, ChainWeight.totalDifficultyOnly(b.number.value), true))
      branch.foreach(byHashOnly)
      byHashOnly(above)
    // a block that is stored and not on the chain: no entry in the index of blocks by number
    def byHashOnly(b: Block): Unit =
      blockchainWriter
        .storeBlockByHashOnly(b)
        .and(blockchainWriter.storeChainWeight(b.hash, ChainWeight.totalDifficultyOnly(b.number.value)))
        .commit()
    def everything: Map[ByteString, ByteString] =
      storagesInstance.dataSource.storage.map((key, value) => ByteString(key.array()) -> ByteString(value))

  /** Two stores of the same chains, the main chain's head the best block, with their blocks' hashes the same. */
  private def twins(): (Chains, Chains) =
    val one = new Chains
    val two = new Chains(Some(one))
    one.store()
    two.store()
    one.everything shouldBe two.everything
    (one, two)

  "The canonical head's batch" should "be what promoting a block writes, to the byte: up, across, down and to itself" taggedAs
    (UnitTest, StateTest) in {
      val heads: Seq[(String, Chains => Block)] = Seq(
        "the branch's head, across" -> (_.branch.last),
        "an ancestor, down" -> (_.main(2)),
        "the head itself" -> (_.main.last),
        "a block below the branch's fork, then across" -> (_.branch(1)),
        "a block above the head, up" -> (_.above)
      )
      for
        (named, head) <- heads
        // and where the index names blocks above the best block, which a move of the head clears
        leftOver <- Seq(false, true)
      do
        withClue(s"$named${if leftOver then ", with entries left above the head" else ""}: ") {
          val (promoted, built) = twins()
          if leftOver then
            Seq(promoted, built).foreach { chains =>
              chains.leftovers.foreach(b =>
                chains.blockchainWriter
                  .save(b, Nil, ChainWeight.totalDifficultyOnly(b.number.value), saveAsBestBlock = false)
              )
              chains.blockchainReader.getBestBlockNumber shouldBe BigInt(6)
              chains.blockchainReader.getCanonicalHashByNumber(BigInt(8)) shouldBe Some(
                chains.leftovers.last.header.hash
              )
            }
          val target = head(promoted).header
          val before = built.everything
          val update = built.blockchainWriter.canonicalHeadUpdate(target, built.blockchainReader)
          // building it writes nothing
          built.everything shouldBe before

          val move = promoted.blockchainWriter.promoteToCanonicalHead(target, promoted.blockchainReader)
          update.batch.commit()
          built.everything shouldBe promoted.everything
          update.move shouldBe move
          update.previousBest shouldBe BigInt(6)
          built.blockchainReader.getBestBlockNumber shouldBe target.number.value
          // nothing is left above the head
          built.blockchainReader.getCanonicalHashByNumber(target.number.value + 1) shouldBe None
          built.blockchainReader.getCanonicalHashByNumber(BigInt(8)) shouldBe None
        }
    }

  it should "say what it does: what it rewrites, clears and drops, and whether that is more than an extension" taggedAs
    (UnitTest, StateTest) in {
      val (chains, _) = twins()
      import chains.*
      val across = blockchainWriter.canonicalHeadUpdate(branch.last.header, blockchainReader)
      across.reorganises shouldBe true
      across.move.adopted.map(_._2) shouldBe branch.map(_.header.hash)
      across.move.dropped.map(_._2) shouldBe main.drop(2).map(_.header.hash)
      across.cleared shouldBe 1
      // a branch that ends above the head clears nothing, and rewrites the heights below the head: a reorganisation
      val longer = BlockHelpers.generateChain(6, main(1))
      longer.foreach(byHashOnly)
      val over = blockchainWriter.canonicalHeadUpdate(longer.last.header, blockchainReader)
      over.cleared shouldBe 0
      over.reorganises shouldBe true
      over.move.dropped.map(_._2) shouldBe main.drop(2).map(_.header.hash)
      // an extension of the head by a block above it is not a reorganisation
      val next = BlockHelpers.generateBlock(main.last)
      byHashOnly(next)
      val up = blockchainWriter.canonicalHeadUpdate(next.header, blockchainReader)
      up.reorganises shouldBe false
      up.cleared shouldBe 0
      up.move.adopted.map(_._2) shouldBe Seq(next.header.hash)
      up.move.dropped shouldBe empty
    }
