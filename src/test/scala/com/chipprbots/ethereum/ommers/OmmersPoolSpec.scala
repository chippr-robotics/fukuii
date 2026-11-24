package com.chipprbots.ethereum.ommers

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.util.ByteString

import org.scalamock.scalatest.MockFactory
import org.scalatest.freespec.AnyFreeSpecLike
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures.Blocks.Block3125369
import com.chipprbots.ethereum.Timeouts
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.BlockNumber
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.Difficulty
import com.chipprbots.ethereum.ommers.OmmersPool.AddOmmers
import com.chipprbots.ethereum.ommers.OmmersPool.Command
import com.chipprbots.ethereum.ommers.OmmersPool.GetOmmers
import com.chipprbots.ethereum.ommers.OmmersPool.Ommers

class OmmersPoolSpec extends ScalaTestWithActorTestKit with AnyFreeSpecLike with Matchers with MockFactory:

  "OmmersPool" - {

    "should not return ommers if there is no any" in new TestSetup:

      /** 00 --> 11 --> 21 --> [31] (chain1) \-> 14 --> 24 (chain4) [] new block, reference! 24 is a child of 14, which
        * is no ancestor's parent: no ommer of the new block
        */
      blockchainReader.getBlockHeaderByHash.expects(block2Chain1.hash).returns(Some(block2Chain1))
      blockchainReader.getBlockHeaderByHash.expects(block1Chain1.hash).returns(Some(block1Chain1))

      ommersPool ! AddOmmers(
        block0,
        block1Chain1,
        block2Chain4,
        block2Chain1
      )

      ommersPool ! GetOmmers(block3Chain1.parentHash.value, ommersProbe.ref)
      ommersProbe.expectMessage(Timeouts.normalTimeout, OmmersPool.Ommers(Seq.empty))

    "should return ommers properly" - {

      "in case of a chain with less length than the generation limit" in new TestSetup:

        /** 00 --> 11 --> [new block] (chain1), with (14) (chain4) a child of 00, and 21, 22 children of 11, siblings of
          * the new block. [] new block, reference! () ommer given the new block. The walk stops at 00, below which
          * nothing is stored: two ancestors of the three the generation limit allows.
          */
        override val ommerGenerationLimit: Int = 3
        blockchainReader.getBlockHeaderByHash.expects(block1Chain1.hash).returns(Some(block1Chain1))
        blockchainReader.getBlockHeaderByHash.expects(block0.hash).returns(Some(block0))
        blockchainReader.getBlockHeaderByHash.expects(block0.parentHash).returns(None)

        ommersPool ! AddOmmers(
          block0,
          block1Chain1,
          block1Chain4,
          block2Chain1,
          block2Chain2,
          block3Chain3
        )

        ommersPool ! GetOmmers(block1Chain1.hash.value, ommersProbe.ref)
        ommersProbe.expectMessage(Timeouts.normalTimeout, OmmersPool.Ommers(Seq(block1Chain4)))

      "despite of start losing older ommers candidates" in new TestSetup:

        /** XX --> (11) --> 21 --> 31 (chain1) \ \ \-> 33 (chain3) \ \--> 22 --> 32 (chain2) \--> 14 ---> [24] (chain4)
          * \-> (15) (chain5) [] new block, reference! () ommer given the new block XX removed block
          */
        blockchainReader.getBlockHeaderByHash.expects(block1Chain4.hash).returns(Some(block1Chain4)).once()
        blockchainReader.getBlockHeaderByHash.expects(block0.hash).returns(Some(block0)).once()

        ommersPool ! AddOmmers(
          block0,
          block1Chain1,
          block2Chain1,
          block3Chain1,
          block1Chain4,
          block2Chain2,
          block3Chain2,
          block3Chain3
        )

        // Ommers pool size limit is reach, block0 will be removed.
        // Notice that in terms of additions, current pool implementation is behaving as a queue with a fixed size!
        ommersPool ! AddOmmers(block1Chain5)

        ommersPool ! GetOmmers(block2Chain4.parentHash.value, ommersProbe.ref)
        ommersProbe.expectMessage(Timeouts.normalTimeout, OmmersPool.Ommers(Seq(block1Chain5, block1Chain1)))

      "by respecting size and generation limits" in new TestSetup:

        /** 00 --> 11 --> 21 --> [31] (chain1) \ \ \-> 33 (chain3) \ \--> (22) --> 32 (chain2) \-> (14) (chain4) \-> 15
          * (chain5) [] new block, reference! () ommer given the new block. 14, 22 and 15 are ommers of the new block,
          * and the reply holds two: the first two in the pool
          */
        blockchainReader.getBlockHeaderByHash.expects(block2Chain1.hash).returns(Some(block2Chain1))
        blockchainReader.getBlockHeaderByHash.expects(block1Chain1.hash).returns(Some(block1Chain1))

        ommersPool ! AddOmmers(
          block0,
          block1Chain1,
          block2Chain1,
          block1Chain4,
          block2Chain2,
          block1Chain5,
          block3Chain2,
          block3Chain3
        )

        ommersPool ! GetOmmers(block3Chain1.parentHash.value, ommersProbe.ref)
        ommersProbe.expectMessage(Timeouts.normalTimeout, OmmersPool.Ommers(Seq(block1Chain4, block2Chain2)))

      "as the children of the ancestors' parents: never a sibling of the new block (B-500)" in new TestSetup:

        /** 00 --> 11 --> 21 --> [31] (chain1), with 33 (chain3) a child of 21, 22 (chain2) a child of 11 and 14
          * (chain4) a child of 00. 33 is a sibling of the new block: of its height, it is never its ommer, and it took
          * a slot of the two (StdOmmersValidator: OmmerParentIsNotAncestorError). 14, two generations back, is the
          * oldest the limit allows. As core-geth's `commitUncle`: "uncle is sibling", and the uncle's parent among the
          * ancestors.
          */
        blockchainReader.getBlockHeaderByHash.expects(block2Chain1.hash).returns(Some(block2Chain1))
        blockchainReader.getBlockHeaderByHash.expects(block1Chain1.hash).returns(Some(block1Chain1))

        ommersPool ! AddOmmers(block3Chain3, block2Chain2, block1Chain4)

        ommersPool ! GetOmmers(block2Chain1.hash.value, ommersProbe.ref)
        ommersProbe.expectMessage(Timeouts.normalTimeout, OmmersPool.Ommers(Seq(block2Chain2, block1Chain4)))

    }

    "should not evict a candidate when a header already in a full pool is added again (B-499)" in new TestSetup:

      /** 00 --> [14] --> (new block) (chain4), with 11 (chain1), 15 (chain5), 16 (chain6) and 17 (chain7) also children
        * of 00. Given parent 14, every pooled header here passes the ommer filter, and the reply limit (4) is above the
        * pool size (3), so a reply is the whole pool, newest first.
        */
      override val ommersPoolSize: Int = 3
      override val returnedOmmerSizeLimit: Int = 4
      val block1Chain6: BlockHeader =
        Block3125369.header.copy(number = BlockNumber(1), parentHash = block0.hash, difficulty = Difficulty(16))
      val block1Chain7: BlockHeader =
        Block3125369.header.copy(number = BlockNumber(1), parentHash = block0.hash, difficulty = Difficulty(17))
      blockchainReader.getBlockHeaderByHash.expects(block1Chain4.hash).returns(Some(block1Chain4)).twice()
      blockchainReader.getBlockHeaderByHash.expects(block0.hash).returns(Some(block0)).twice()

      ommersPool ! AddOmmers(block1Chain1)
      ommersPool ! AddOmmers(block1Chain5)
      ommersPool ! AddOmmers(block1Chain7)
      // The pool is full: [17, 15, 11]. 15 again is no new candidate, so nothing is evicted (trimmed before the
      // de-duplication, its second copy took 11's slot); it moves to the front, as the newest.
      ommersPool ! AddOmmers(block1Chain5)

      ommersPool ! GetOmmers(block1Chain4.hash.value, ommersProbe.ref)
      ommersProbe.expectMessage(
        Timeouts.normalTimeout,
        OmmersPool.Ommers(Seq(block1Chain5, block1Chain7, block1Chain1))
      )

      // A new header still evicts the oldest, 11.
      ommersPool ! AddOmmers(block1Chain6)

      ommersPool ! GetOmmers(block1Chain4.hash.value, ommersProbe.ref)
      ommersProbe.expectMessage(
        Timeouts.normalTimeout,
        OmmersPool.Ommers(Seq(block1Chain6, block1Chain5, block1Chain7))
      )

    "should not return an ommer an ancestor included, but the next candidate (B-500)" in new TestSetup:

      /** 00 --> 11 --> 21 --> 31' --> [new block] (chain1), where 31' included 22 as its ommer; 33 and 37 are children
        * of 21. 22 is included once: a block that carries it again fails the ommers check, and it took a slot of the
        * two. core-geth's worker refuses it ("uncle already included"): its `family` holds the ancestors' uncles.
        */
      override val ommerGenerationLimit: Int = 3
      val block3Chain1WithOmmer: BlockHeader =
        block3Chain1.copy(ommersHash = BlockHash(ByteString(Array.fill[Byte](32)(1))))
      val block3Chain7: BlockHeader =
        Block3125369.header.copy(number = BlockNumber(3), parentHash = block2Chain1.hash, difficulty = Difficulty(37))
      blockchainReader.getBlockHeaderByHash.expects(block3Chain1WithOmmer.hash).returns(Some(block3Chain1WithOmmer))
      blockchainReader.getBlockHeaderByHash.expects(block2Chain1.hash).returns(Some(block2Chain1))
      blockchainReader.getBlockHeaderByHash.expects(block1Chain1.hash).returns(Some(block1Chain1))
      // read only for the ancestor whose header names ommers: 21 and 11 name none
      blockchainReader.getBlockBodyByHash
        .expects(block3Chain1WithOmmer.hash)
        .returns(Some(BlockBody(Nil, Seq(block2Chain2))))
        .once()

      ommersPool ! AddOmmers(block2Chain2, block3Chain3, block3Chain7)

      ommersPool ! GetOmmers(block3Chain1WithOmmer.hash.value, ommersProbe.ref)
      ommersProbe.expectMessage(Timeouts.normalTimeout, OmmersPool.Ommers(Seq(block3Chain3, block3Chain7)))
  }

  // SCALA 3 MIGRATION: Cannot use self-type constraint with `new TestSetup` in Scala 3.
  // Using lazy val for mock ensures it's created when accessed within MockFactory context.
  trait TestSetup:

    // In order to support all the blocks for the given scenarios
    val ommersPoolSize: Int = 8

    // Originally it should be 6 as is stated on section 11.1, eq. (143) of the YP
    // Here we are using a simplification for testing purposes
    val ommerGenerationLimit: Int = 2
    val returnedOmmerSizeLimit: Int = 2 // Max amount of ommers allowed per block

    /** 00 ---> 11 --> 21 --> 31 (chain1) \ \ \--> 33 (chain3) \ \--> 22 --> 32 (chain2) \--> 14 --> 24 (chain4) \-> 15
      * (chain5)
      */
    val block0: BlockHeader = Block3125369.header.copy(number = BlockNumber(0), difficulty = Difficulty.Zero)

    val block1Chain1: BlockHeader =
      Block3125369.header.copy(number = BlockNumber(1), parentHash = block0.hash, difficulty = Difficulty(11))
    val block2Chain1: BlockHeader =
      Block3125369.header.copy(number = BlockNumber(2), parentHash = block1Chain1.hash, difficulty = Difficulty(21))
    val block3Chain1: BlockHeader =
      Block3125369.header.copy(number = BlockNumber(3), parentHash = block2Chain1.hash, difficulty = Difficulty(31))

    val block2Chain2: BlockHeader =
      Block3125369.header.copy(number = BlockNumber(2), parentHash = block1Chain1.hash, difficulty = Difficulty(22))
    val block3Chain2: BlockHeader =
      Block3125369.header.copy(number = BlockNumber(2), parentHash = block2Chain2.hash, difficulty = Difficulty(32))

    val block3Chain3: BlockHeader =
      Block3125369.header.copy(number = BlockNumber(3), parentHash = block2Chain1.hash, difficulty = Difficulty(33))

    val block1Chain4: BlockHeader =
      Block3125369.header.copy(number = BlockNumber(1), parentHash = block0.hash, difficulty = Difficulty(14))
    val block2Chain4: BlockHeader =
      Block3125369.header.copy(number = BlockNumber(2), parentHash = block1Chain4.hash, difficulty = Difficulty(24))

    val block1Chain5: BlockHeader =
      Block3125369.header.copy(number = BlockNumber(1), parentHash = block0.hash, difficulty = Difficulty(15))

    // Mock created lazily so it's initialized when accessed within the MockFactory context
    lazy val blockchainReader: BlockchainReader = mock[BlockchainReader]
    lazy val ommersProbe: TestProbe[Ommers] = testKit.createTestProbe[Ommers]()
    lazy val ommersPool: ActorRef[Command] =
      testKit.spawn(
        OmmersPool(blockchainReader, ommersPoolSize, ommerGenerationLimit, returnedOmmerSizeLimit)
      )
