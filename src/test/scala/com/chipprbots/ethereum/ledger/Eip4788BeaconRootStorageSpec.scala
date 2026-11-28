package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.Mocks
import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostCancun
import com.chipprbots.ethereum.ledger.BlockExecution.BeaconRootContractAddress
import com.chipprbots.ethereum.ledger.BlockExecution.BeaconRootHistoryBufferLength
import com.chipprbots.ethereum.ledger.BlockExecution.BeaconRootsCode
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteStringUtils
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.Config.SyncConfig
import com.chipprbots.ethereum.utils.ForkTimestamps

/** EIP-4788: Ring-buffer storage write integration tests.
  *
  * Focuses on [[BlockExecution.applyEip4788]]'s storage-layer behaviour: slot formula (`timestamp %
  * HISTORY_BUFFER_LENGTH` for the timestamp entry and `timestamp % HISTORY_BUFFER_LENGTH + HISTORY_BUFFER_LENGTH` for
  * the root entry), wrap-around when the timestamp index crosses the 8191-slot boundary, the pre-Cancun guard, and the
  * contract-deployment check (code + nonce=1) required by §ETH-T4-C.
  */
class Eip4788BeaconRootStorageSpec extends AnyFlatSpec with Matchers:

  private val CancunTs: Long = 100L
  private val Buf: BigInt = BeaconRootHistoryBufferLength // 8191

  trait TestSetup extends EphemBlockchainTestSetup:
    override lazy val vm: VMImpl = new Mocks.MockVM()

    implicit override lazy val blockchainConfig: BlockchainConfig =
      Config.blockchains.blockchainConfig.copy(
        forkTimestamps = ForkTimestamps(
          shanghaiTimestamp = Some(0L),
          cancunTimestamp = Some(CancunTs)
        )
      )

    override lazy val blockQueue: BlockQueue = BlockQueue(blockchainReader, SyncConfig(Config.config))

    override lazy val blockValidation = new BlockValidation(
      mining.withValidators(Mocks.MockValidatorsAlwaysSucceed),
      blockchainReader,
      blockQueue
    )

    lazy val exec: BlockExecution = new BlockExecution(
      blockchain,
      blockchainReader,
      blockchainWriter,
      storagesInstance.storages.evmCodeStorage,
      mining.blockPreparator,
      blockValidation
    )

    val emptyWorld: InMemoryWorldStateProxy = InMemoryWorldStateProxy(
      storagesInstance.storages.evmCodeStorage,
      blockchain.getBackingMptStorage(-1),
      (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = false,
      ethCompatibleStorage = true
    )

    /** The canonical EIP-4788 contract as every public network actually has it: deployed by the pre-signed
      * Nick's-method transaction from 0x0B799C86a49DEeb90402691F1041aa3AF2d3C875 (EIP-4788 "Deployment") long before
      * the Cancun fork, so the account already carries code and nonce=1 by the time the first post-Cancun block is
      * processed. The client never deploys it itself — EIP-4788 Block processing: "if no code exists at
      * BEACON_ROOTS_ADDRESS, the call must fail silently" — so every storage-behaviour test must start from a world
      * where it is present.
      */
    val deployedWorld: InMemoryWorldStateProxy =
      emptyWorld
        .saveAccount(
          BeaconRootContractAddress,
          Account.empty(blockchainConfig.accountStartNonce).copy(nonce = UInt256(1))
        )
        .saveCode(BeaconRootContractAddress, BeaconRootsCode)

    def makeBlock(beaconRoot: ByteString, timestamp: Long = CancunTs): Block = Block(
      header = Fixtures.Blocks.ValidBlock.header.copy(
        unixTimestamp = Timestamp(timestamp),
        gasLimit = GasAmount(8_000_000),
        gasUsed = GasAmount.Zero,
        extraFields = HefPostCancun(
          baseFee = BigInt(1),
          withdrawalsRoot = ByteString(Array.fill(32)(0x00.toByte)),
          blobGasUsed = BigInt(0),
          excessBlobGas = BigInt(0),
          parentBeaconBlockRoot = beaconRoot
        )
      ),
      body = BlockBody(Nil, Nil)
    )

    def runBlock(block: Block, world: InMemoryWorldStateProxy = deployedWorld): InMemoryWorldStateProxy =
      exec.executeBlockTransactions(block, world).toOption.get.worldState

  // Case 1 — First post-Cancun block: timestamp slot and root slot
  "EIP-4788 beacon root storage" should
    "write timestamp and beacon root to the correct ring-buffer slots on the first post-Cancun block" taggedAs (
      UnitTest,
      ConsensusTest
    ) in (new TestSetup:
      val ts: Long = CancunTs
      val beaconRoot: ByteString = ByteString(Array.fill(32)(0xab.toByte))
      val world: InMemoryWorldStateProxy = runBlock(makeBlock(beaconRoot, ts))

      val timestampIdx: BigInt = BigInt(ts) % Buf
      val rootIdx: BigInt = timestampIdx + Buf
      val storage: InMemoryWorldStateProxyStorage = world.getStorage(BeaconRootContractAddress)

      storage.load(timestampIdx) shouldBe BigInt(ts)
      storage.load(rootIdx) shouldBe UInt256(beaconRoot).toBigInt
    )

  // Case 2 — Pre-Cancun block: no storage written, contract absent
  it should "NOT write any storage slots for a block without parentBeaconBlockRoot" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val preCancunBlock: Block = Block(
      header = Fixtures.Blocks.ValidBlock.header.copy(
        unixTimestamp = Timestamp(CancunTs),
        gasLimit = GasAmount(8_000_000),
        gasUsed = GasAmount.Zero
        // HefEmpty by default — no parentBeaconBlockRoot
      ),
      body = BlockBody(Nil, Nil)
    )
    val world: InMemoryWorldStateProxy = runBlock(preCancunBlock, emptyWorld)

    val timestampIdx: BigInt = BigInt(CancunTs) % Buf
    val rootIdx: BigInt = timestampIdx + Buf
    val storage: InMemoryWorldStateProxyStorage = world.getStorage(BeaconRootContractAddress)

    storage.load(timestampIdx) shouldBe BigInt(0)
    storage.load(rootIdx) shouldBe BigInt(0)
    world.getCode(BeaconRootContractAddress) shouldBe ByteString.empty

  // Case 3 — Wrap-around: slot 8190 then slot 0 (new values overwrite, old slot retained)
  it should "overwrite slot 0 when the timestamp index wraps from 8190 to 0" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    // timestamp_A % 8191 = 8190  (last slot before wrap)
    val tsA: Long = 8190L
    // timestamp_B % 8191 = 0     (wraps to slot 0)
    val tsB: Long = 8191L

    val rootA: ByteString = ByteString(Array.fill(32)(0xaa.toByte))
    val rootB: ByteString = ByteString(Array.fill(32)(0xbb.toByte))

    val world1: InMemoryWorldStateProxy = runBlock(makeBlock(rootA, tsA))
    val world2: InMemoryWorldStateProxy = runBlock(makeBlock(rootB, tsB), world1)

    val slotA_ts: BigInt = BigInt(tsA) % Buf // 8190
    val slotA_root: BigInt = slotA_ts + Buf // 16381
    val slotB_ts: BigInt = BigInt(tsB) % Buf // 0
    val slotB_root: BigInt = slotB_ts + Buf // 8191

    val storage: InMemoryWorldStateProxyStorage = world2.getStorage(BeaconRootContractAddress)

    // Slot 0 written by block B
    storage.load(slotB_ts) shouldBe BigInt(tsB)
    storage.load(slotB_root) shouldBe UInt256(rootB).toBigInt

    // Slot 8190 retains block A's values (not overwritten by block B)
    storage.load(slotA_ts) shouldBe BigInt(tsA)
    storage.load(slotA_root) shouldBe UInt256(rootA).toBigInt

  // Case 4 — §ETH-T4-C: the contract is a pre-existing chain object, not something the client
  // deploys. Processing a post-Cancun block must leave its code and nonce exactly as found.
  it should "keep the deployed EIP-4788 bytecode and nonce=1 unchanged" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val world: InMemoryWorldStateProxy =
      runBlock(makeBlock(ByteString(Array.fill(32)(0xcc.toByte))))

    world.getCode(BeaconRootContractAddress) shouldBe BeaconRootsCode
    world.getAccount(BeaconRootContractAddress).map(_.nonce) shouldBe Some(UInt256(1))

  // Case 5 — EIP-4788 Block processing: "if no code exists at BEACON_ROOTS_ADDRESS, the call must
  // fail silently". No account, no code, no storage: byte-for-byte what go-ethereum leaves behind.
  it should "write nothing at all when no code exists at the beacon roots address" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val world: InMemoryWorldStateProxy =
      runBlock(makeBlock(ByteString(Array.fill(32)(0xdd.toByte))), emptyWorld)

    val timestampIdx: BigInt = BigInt(CancunTs) % Buf
    world.getAccount(BeaconRootContractAddress) shouldBe None
    world.getCode(BeaconRootContractAddress) shouldBe ByteString.empty
    world.getStorage(BeaconRootContractAddress).load(timestampIdx) shouldBe BigInt(0)
    world.getStorage(BeaconRootContractAddress).load(timestampIdx + Buf) shouldBe BigInt(0)

  // ── B-517: the direct write is the canonical contract's effect; other code is run ─────────

  // The direct write stands in for go-ethereum's ProcessBeaconBlockRoot (a SYSTEM_ADDRESS call, 30,000,000 gas) only
  // because it is what the canonical contract does. Pinned here by running that contract as the call, through
  // BlockExecution.uncheckedSystemCall, and comparing the whole state root with import's: a first slot, a wrapped
  // index, the largest uint64 timestamp (TIMESTAMP must not sign-extend) and a zero root (a store of zero).
  it should "give the state root that running the canonical contract as a SYSTEM_ADDRESS call gives" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val cases: Seq[(Long, ByteString)] = Seq(
      CancunTs -> ByteString(Array.fill(32)(0xab.toByte)),
      (CancunTs + 3 * 8191L + 17) -> ByteString(Array.tabulate(32)(_.toByte)),
      -1L -> ByteString(Array.fill(32)(0xee.toByte)), // uint64 max: 2^64 - 1
      (CancunTs + 1) -> ByteString(new Array[Byte](32))
    )
    cases.foreach { (ts, root) =>
      val block = makeBlock(root, ts)
      val direct = InMemoryWorldStateProxy.persistState(runBlock(block))
      val called = BlockExecution.uncheckedSystemCall(
        block.header,
        deployedWorld,
        BeaconRootContractAddress,
        root,
        accessRecorder = None,
        precompileRelocations = Map.empty
      )
      withClue(s"timestamp ${java.lang.Long.toUnsignedString(ts)}: ") {
        called.stateRootHash shouldBe direct.stateRootHash
        called.stateRootHash should not be InMemoryWorldStateProxy.persistState(deployedWorld).stateRootHash
      }
    }

  /** Other code at the beacon roots address: called by SYSTEM_ADDRESS it stores the calldata (the root) at slot 0;
    * called by anyone else it returns slot 0.
    */
  private val customCode: ByteString = ByteStringUtils.string2hash(
    "33" + "73" + "ff" * 19 + "fe" + "14" + "6025" + "57" + "600054" + "600052" + "6020" + "6000" + "f3" +
      "5b" + "600035" + "600055" + "00"
  )

  // go-ethereum runs the code at BEACON_ROOTS_ADDRESS, whatever it is; the direct write gave this account the
  // canonical contract's two slots instead.
  it should "run code other than the canonical contract, as go-ethereum's system call does" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new TestSetup:
    val root: ByteString = ByteString(Array.fill(32)(0x5a.toByte))
    val custom: InMemoryWorldStateProxy = emptyWorld
      .saveAccount(
        BeaconRootContractAddress,
        Account.empty(blockchainConfig.accountStartNonce).copy(nonce = UInt256(1))
      )
      .saveCode(BeaconRootContractAddress, customCode)
    val storage: InMemoryWorldStateProxyStorage =
      runBlock(makeBlock(root), custom).getStorage(BeaconRootContractAddress)
    storage.load(0) shouldBe UInt256(root).toBigInt
    storage.load(BigInt(CancunTs) % Buf) shouldBe BigInt(0)
    storage.load(BigInt(CancunTs) % Buf + Buf) shouldBe BigInt(0)

  // EIP-4788: the call's failure is ignored and its changes are dropped (geth: `_, _, _ = evm.Call(...)`).
  it should "leave the state as it was when that code reverts" taggedAs (UnitTest, ConsensusTest) in new TestSetup:
    val reverting: InMemoryWorldStateProxy = emptyWorld
      .saveAccount(
        BeaconRootContractAddress,
        Account.empty(blockchainConfig.accountStartNonce).copy(nonce = UInt256(1))
      )
      .saveCode(BeaconRootContractAddress, ByteString(0x5f, 0x5f, 0xfd)) // PUSH0 PUSH0 REVERT
    InMemoryWorldStateProxy
      .persistState(runBlock(makeBlock(ByteString(Array.fill(32)(0x01.toByte))), reverting))
      .stateRootHash shouldBe InMemoryWorldStateProxy.persistState(reverting).stateRootHash
