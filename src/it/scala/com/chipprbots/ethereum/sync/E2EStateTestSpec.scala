package com.chipprbots.ethereum.sync

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.concurrent.duration.*

import com.typesafe.config.ConfigValueFactory
import org.scalatest.Assertion
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.FreeSpecBase
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.metrics.Metrics
import com.chipprbots.ethereum.metrics.MetricsConfig
import com.chipprbots.ethereum.sync.util.RegularSyncItSpecUtils.ExecutedChain.*
import com.chipprbots.ethereum.sync.util.RegularSyncItSpecUtils.FakePeer
import com.chipprbots.ethereum.sync.util.SyncCommonItSpecUtils.FakePeerCustomConfig
import com.chipprbots.ethereum.sync.util.SyncCommonItSpecUtils.retryUntilWithDelay
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

/** End-to-End test suite for blockchain state synchronization and validation.
  *
  * This test suite validates state-related operations in the blockchain peer and sync systems, leveraging official
  * Ethereum execution client specifications from:
  *   - https://github.com/ethereum/execution-specs (specification)
  *   - https://github.com/ethereum/tests (generated test cases)
  *
  * Test coverage includes:
  *   - State trie synchronization between peers
  *   - State root validation and consistency
  *   - Account state propagation and verification
  *   - Contract storage synchronization
  *   - State healing and recovery mechanisms
  *   - State integrity during blockchain operations
  *
  * Every state change is a real transaction. A block carries the state root its own execution gives, and the syncing
  * peer re-executes each block on the state of the one before it. A header that names a root the syncing peer never
  * computed makes the next block unimportable (a missing trie node), so injecting state without transactions cannot be
  * synced (B-018). Each test therefore reads the synced state back: balances, nonces and contract storage.
  *
  * These tests ensure that fukuii correctly synchronizes and maintains blockchain state across peers, preventing issues
  * with state divergence, corruption, or loss that would break consensus.
  *
  * The tests complement the GeneralStateTests from ethereum/tests by focusing on peer-to-peer state synchronization
  * scenarios rather than single-node state transition validation.
  *
  * @see
  *   Issue: Run end-to-end state test to troubleshoot blockchain peer modules
  * @see
  *   Official execution specs: https://github.com/ethereum/execution-specs
  * @see
  *   Official test repository: https://github.com/ethereum/tests
  */
class E2EStateTestSpec extends FreeSpecBase with Matchers with BeforeAndAfterAll:
  implicit val testRuntime: IORuntime = IORuntime.global

  override def beforeAll(): Unit =
    // Close any previous metrics instance so the new one starts with a clean registry
    Metrics.closeInstance("default")
    Metrics.configure(
      MetricsConfig(Config.config.withValue("metrics.enabled", ConfigValueFactory.fromAnyRef(true)))
    )

  override def afterAll(): Unit = {
    // No need to shutdown IORuntime.global
  }

  private val Wei = BigInt(1000000000)

  /** A transfer of `n * 1e9` to the account whose address is `n`, at each block number in `blocks`. */
  private def transfersAt(blocks: Set[Int])(n: BigInt): Seq[TxPlan] =
    if blocks.contains(n.toInt) then Seq(transferTo(Address(n), n * Wei)) else Nil

  /** A transfer in every block after the first, the one that funds the sender. */
  private def transferEveryBlock(n: BigInt): Seq[TxPlan] =
    if n > 1 then Seq(transferTo(Address(n), n * Wei)) else Nil

  /** At every 50th block, a contract creation that stores `(i, n * i)` for i in 1 to 5. */
  private def storageContractEvery50(n: BigInt): Seq[TxPlan] =
    if n % 50 == 0 && n > 0 then Seq(TxPlan(None, 0, storingInitCode((1 to 5).map(i => (BigInt(i), n * i)))))
    else Nil

  /** The contract `storageContractEvery50` creates at block `n`: the sender's `n / 50`th transaction. */
  private def storageContractAt(n: Int): Address = contractAddress(Sender, BigInt(n / 50 - 1))

  private def assertSameBestBlock(peer1: FakePeer, peer2: FakePeer): Assertion =
    val best1 = peer1.blockchainReader.getBestBlock.get
    val best2 = peer2.blockchainReader.getBestBlock.get
    best1.hash shouldBe best2.hash
    best1.header.stateRoot shouldBe best2.header.stateRoot

  private def assertSameBlockAt(peer1: FakePeer, peer2: FakePeer, number: Int): Assertion =
    val block1 = peer1.blockchainReader.getBlockByNumber(peer1.blockchainReader.getBestBranch, number).get
    val block2 = peer2.blockchainReader.getBlockByNumber(peer2.blockchainReader.getBestBranch, number).get
    block1.hash shouldBe block2.hash
    block1.header.stateRoot shouldBe block2.header.stateRoot

  /** `peer` holds, in its own state, the transfer made at each of `blocks`. */
  private def assertTransfers(peer: FakePeer, blocks: Iterable[Int]): Assertion =
    blocks.foreach { n =>
      peer.accountAtBest(Address(n)).map(_.balance) shouldBe Some(UInt256(BigInt(n) * Wei))
    }
    succeed

  /** `peer` holds, in its own state, every storage contract created at the multiples of 50 up to `last`. */
  private def assertStorageContracts(peer: FakePeer, last: Int): Assertion =
    (50 to last by 50).foreach { n =>
      val contract = storageContractAt(n)
      peer.accountAtBest(contract) shouldBe defined
      (1 to 5).foreach(i => peer.storageAtBest(contract, i) shouldBe BigInt(n) * i)
    }
    succeed

  "E2E State Test" - {

    "State Trie Synchronization" - {

      "should synchronize state trie between peers" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 200
        val stateBlockNumber = 100

        for
          // Peer1 builds a chain whose block 100 carries a transfer
          _ <- peer1.importExecutedBlocksUntil(blockNumber)(transfersAt(Set(stateBlockNumber)))
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          assertSameBestBlock(peer1, peer2)
          assertSameBlockAt(peer1, peer2, stateBlockNumber)
          assertTransfers(peer2, Seq(stateBlockNumber))
          peer2.accountAtBest(Sender).map(_.nonce) shouldBe Some(UInt256(1))
      }

      "should maintain state trie consistency across multiple state updates" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 300
        val stateBlocks = Set(50, 100, 150, 200, 250)

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber)(transfersAt(stateBlocks))
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          stateBlocks.foreach(assertSameBlockAt(peer1, peer2, _))
          assertTransfers(peer2, stateBlocks)
          peer2.accountAtBest(Sender).map(_.nonce) shouldBe Some(UInt256(stateBlocks.size))
      }

      "should handle large state tries efficiently" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 500

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber)(storageContractEvery50)
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          assertSameBestBlock(peer1, peer2)
          assertStorageContracts(peer2, blockNumber)
      }

    }

    "State Root Validation" - {

      "should validate state roots during synchronization" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 150
        val stateBlockNumber = 75

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber)(transfersAt(Set(stateBlockNumber)))
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          assertSameBlockAt(peer1, peer2, stateBlockNumber)
          assertSameBestBlock(peer1, peer2)
          assertTransfers(peer2, Seq(stateBlockNumber))
      }

      "should detect and reject invalid state roots" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        // Peer2 checks the state root and gas used of each block against its own execution of it, as import does; the
        // default harness validators accept any root
        FakePeer.start2FakePeersRes(
          fakePeerCustomConfig2 = FakePeerCustomConfig.defaultConfig.copy(validateStateRoots = true)
        )
      ) { case (peer1, peer2) =>
        val validBlocks = 20
        val badBlock = validBlocks + 1
        val bogusRoot = TrieRoot(kec256(ByteString("a state root that no execution of the block gives")))

        for
          // Peer1 builds a valid chain, and then one block whose header names a state root its transactions do not give
          _ <- peer1.importExecutedBlocksUntil(validBlocks)(transferEveryBlock)
          forged <- peer1.importForgedBlock(Seq(transferTo(Address(badBlock), BigInt(badBlock) * Wei)))(
            _.copy(stateRoot = bogusRoot)
          )
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          // The blocks before it are valid and go in
          _ <- peer2.waitForRegularSyncLoadLastBlock(validBlocks)
          // The bad one is rejected, and its peer is blamed for it
          blamed <- retryUntilWithDelay(IO(peer2.blacklist.keys), 100.millis, 600)(_.nonEmpty)
        yield
          peer1.blockchainReader.getBestBlock.get.hash shouldBe forged.hash
          forged.header.stateRoot shouldBe bogusRoot
          // Peer2 holds the valid chain, with its state, and nothing of the block with the root it could not reproduce
          peer2.blockchainReader.getBestBlockNumber shouldBe validBlocks
          peer2.blockchainReader.getBlockHeaderByHash(forged.hash) shouldBe None
          peer2.blockchainReader.getBlockByNumber(peer2.blockchainReader.getBestBranch, badBlock) shouldBe None
          assertSameBlockAt(peer1, peer2, validBlocks)
          assertTransfers(peer2, 2 to validBlocks)
          blamed should not be empty
      }

      "should maintain state root consistency during chain reorganization" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val commonBlocks = 100
        val peer2ForkBlocks = 5
        val peer1ExtraBlocks = 20
        val abandoned = Address(0xdead)

        for
          // Both peers hold the same chain, with state
          _ <- peer1.importExecutedBlocksUntil(commonBlocks)(transfersAt(Set(50)))
          _ <- peer2.importExecutedBlocksUntil(commonBlocks)(transfersAt(Set(50)))

          // Peer2 forks off it: a different miner, and a transfer to an account the main chain never touches
          _ <- peer2.importExecutedBlocksUntil(commonBlocks + peer2ForkBlocks, OtherMiner)(_ =>
            Seq(transferTo(abandoned, 7))
          )
          peer2ForkTip = peer2.blockchainReader.getBestBlock.get

          // Peer1 mines on through its running regular sync, until its chain is the heavier one
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer1.mineExecutedBlocks(peer1ExtraBlocks)(transfersAt(Set(110)))

          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(commonBlocks + peer1ExtraBlocks)
        yield
          assertSameBestBlock(peer1, peer2)
          // Peer2's fork is no longer canonical, and its state is gone from the best block's
          peer2.blockchainReader
            .getBlockByNumber(peer2.blockchainReader.getBestBranch, commonBlocks + peer2ForkBlocks)
            .map(_.hash) should not be Some(peer2ForkTip.hash)
          peer2.accountAtBest(abandoned) shouldBe None
          assertTransfers(peer2, Seq(50, 110))
      }

    }

    "Account State Propagation" - {

      "should propagate account state changes between peers" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 200
        val stateBlocks = Set(50, 100, 150)

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber)(transfersAt(stateBlocks))
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          assertSameBestBlock(peer1, peer2)
          assertTransfers(peer2, stateBlocks)
      }

      "should handle rapid account state updates" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 300

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber)(transferEveryBlock)
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          assertSameBestBlock(peer1, peer2)
          assertTransfers(peer2, 2 to blockNumber)
          // One transfer per block from block 2
          peer2.accountAtBest(Sender).map(_.nonce) shouldBe Some(UInt256(blockNumber - 1))
      }

    }

    "Contract Storage Synchronization" - {

      "should synchronize contract storage between peers" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 250

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber)(storageContractEvery50)
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          assertSameBestBlock(peer1, peer2)
          assertStorageContracts(peer2, blockNumber)
      }

      "should handle storage updates across multiple blocks" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 400

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber)(storageContractEvery50)
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          (50 to blockNumber by 50).foreach(assertSameBlockAt(peer1, peer2, _))
          assertStorageContracts(peer2, blockNumber)
      }

    }

    "State Healing and Recovery" - {

      "should recover from partial state loss" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 200

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber)(transfersAt(Set(100)))
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          // Peer2 should have recovered the full state
          assertSameBestBlock(peer1, peer2)
          assertTransfers(peer2, Seq(100))
      }

      "should handle state synchronization with missing peers" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        NetworkTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 150

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber)(transfersAt(Set(75)))
          _ <- peer2.startRegularSync()
          // connectToPeers returns once the handshake is done, with peer1's sync not yet started
          _ <- peer2.connectToPeers(Set(peer1.node))
          // Start peer1 sync after peer2 is already trying to sync
          _ <- peer1.startRegularSync()
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          assertSameBestBlock(peer1, peer2)
          assertTransfers(peer2, Seq(75))
      }

      "should maintain state integrity during peer disconnection" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val initialBlocks = 100
        val finalBlocks = 200
        val transfers = transfersAt(Set(50, 150))

        for
          // Partial sync with state
          _ <- peer1.importExecutedBlocksUntil(initialBlocks)(transfers)
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer1.startRegularSync()
          _ <- peer2.waitForRegularSyncLoadLastBlock(initialBlocks)
          bestBeforeDisconnect = peer2.blockchainReader.getBestBlock.get

          // The connection ends, as seen by both peers
          _ <- peer2.disconnectAllPeers()
          _ <- peer2.waitForHandshakedPeers(0)
          _ <- peer1.waitForHandshakedPeers(0)

          // Peer1 moves on while they are apart
          _ <- peer1.importExecutedBlocksUntil(finalBlocks)(transfers)
          bestWhileApart = peer2.blockchainReader.getBestBlock.get
          stateWhileApart = (50 :: Nil).map(n => peer2.accountAtBest(Address(n)).map(_.balance))

          // They meet again, and peer2 continues from where it was
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForHandshakedPeers(1)
          _ <- peer2.waitForRegularSyncLoadLastBlock(finalBlocks)
        yield
          // Apart, peer2 neither advanced nor lost anything: the state it had is the state it kept
          bestWhileApart.hash shouldBe bestBeforeDisconnect.hash
          bestWhileApart.number.value shouldBe initialBlocks
          stateWhileApart shouldBe List(Some(UInt256(BigInt(50) * Wei)))
          // Together again, it has what peer1 has, with the state of both stretches
          assertSameBestBlock(peer1, peer2)
          assertTransfers(peer2, Seq(50, 150))
      }

    }

    "State Integrity" - {

      "should maintain state integrity across entire sync process" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 300

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber)(storageContractEvery50)
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
        yield
          // Verify state integrity at every 50th block
          (50 to blockNumber by 50).foreach(assertSameBlockAt(peer1, peer2, _))
          assertStorageContracts(peer2, blockNumber)
      }

      "should verify state consistency after blockchain reorganization with state changes" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val commonBlocks = 100
        val peer2ForkBlocks = 5
        val peer1ExtraBlocks = 15
        val commonTransfers = Set(25, 50, 75)
        val abandoned = Address(0xdead)

        for
          // Both peers hold the same chain, with state
          _ <- peer1.importExecutedBlocksUntil(commonBlocks)(transfersAt(commonTransfers))
          _ <- peer2.importExecutedBlocksUntil(commonBlocks)(transfersAt(commonTransfers))

          // Peer2 forks off it: a different miner, and a transfer to an account the main chain never touches
          _ <- peer2.importExecutedBlocksUntil(commonBlocks + peer2ForkBlocks, OtherMiner)(_ =>
            Seq(transferTo(abandoned, 7))
          )
          peer2ForkTip = peer2.blockchainReader.getBestBlock.get

          // Peer1 mines on through its running regular sync, until its chain is the heavier one
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer1.mineExecutedBlocks(peer1ExtraBlocks)(transfersAt(Set(105)))

          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(commonBlocks + peer1ExtraBlocks)
        yield
          assertSameBestBlock(peer1, peer2)
          // Peer2's fork is no longer canonical, and its state is gone from the best block's
          peer2.blockchainReader
            .getBlockByNumber(peer2.blockchainReader.getBestBranch, commonBlocks + peer2ForkBlocks)
            .map(_.hash) should not be Some(peer2ForkTip.hash)
          peer2.accountAtBest(abandoned) shouldBe None
          // The blocks below the fork still match
          commonTransfers.foreach(assertSameBlockAt(peer1, peer2, _))
          assertTransfers(peer2, commonTransfers + 105)
      }

      "should persist state correctly across peer restarts" taggedAs (
        IntegrationTest,
        SyncTest,
        StateTest,
        DatabaseTest,
        SlowTest
      ) in customTestCaseResourceM(
        FakePeer.start2FakePeersRes()
      ) { case (peer1, peer2) =>
        val blockNumber = 200
        val stateBlocks = Set(50, 100, 150)
        val laterBlocks = 215
        val laterTransfers = transfersAt(Set(210))

        for
          _ <- peer1.importExecutedBlocksUntil(blockNumber)(transfersAt(stateBlocks))
          _ <- peer1.startRegularSync()
          _ <- peer2.startRegularSync()
          _ <- peer2.connectToPeers(Set(peer1.node))
          _ <- peer2.waitForRegularSyncLoadLastBlock(blockNumber)
          syncedBest = peer2.blockchainReader.getBestBlock.get

          // Peer2 ends the way a process does, with its database closed and nothing else of it running...
          _ <- peer2.stop()
          // ...and starts again on the same data directory, with no connection to anyone
          outcome <- FakePeer.restartedRes(peer2, "Peer2Restarted").use { restarted =>
            for
              reopenedBest <- IO(restarted.blockchainReader.getBestBlock.get)
              // What it holds is what the first one wrote: the chain, and the state, readable without any sync
              _ <- IO {
                Seq(50, 100, 150, 200).foreach(assertSameBlockAt(peer1, restarted, _))
                assertTransfers(restarted, stateBlocks)
                restarted.accountAtBest(Sender).map(_.nonce) shouldBe Some(UInt256(stateBlocks.size))
              }
              // And it carries on from there: the blocks peer1 made since come in on top of the state it kept
              _ <- peer1.importExecutedBlocksUntil(laterBlocks)(laterTransfers)
              _ <- restarted.startRegularSync()
              _ <- restarted.connectToPeers(Set(peer1.node))
              _ <- restarted.waitForRegularSyncLoadLastBlock(laterBlocks)
            yield
              reopenedBest.hash shouldBe syncedBest.hash
              reopenedBest.number.value shouldBe blockNumber
              assertSameBestBlock(peer1, restarted)
              assertTransfers(restarted, stateBlocks + 210)
          }
        yield outcome
      }

    }

  }
