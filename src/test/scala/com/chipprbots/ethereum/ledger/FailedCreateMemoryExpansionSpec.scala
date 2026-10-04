package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.NetworkType

/** A CREATE whose pre-check fails (endowment above the creator's balance) still charges memory expansion for its
  * initcode range, so memory MUST be expanded: go-ethereum resizes memory in the interpreter loop before `opCreate`
  * runs, whatever the outcome. Left unexpanded, the next memory opcode charges the same expansion twice.
  *
  * Platåberget (glamsterdam-devnet-8) block 319453 tx 3: CREATE(value > balance, offset 0x40, size 0x574) then MSTORE
  * at 0xa2c4 cost +142 gas — invisible in the header's gasUsed (a max over two dimensions) but visible in the sender's
  * and coinbase's balances, hence the state root.
  */
// scalastyle:off magic.number
class FailedCreateMemoryExpansionSpec extends AnyFlatSpec with Matchers with AmsterdamFixtureVectors:

  private val Entry: Address = Address(0xe0e0)
  private val header = amsterdamHeader(500, 5000).copy(gasLimit = GasAmount(60_000_000))
  private val osakaHeader = preAmsterdamHeader(500, 300).copy(gasLimit = GasAmount(60_000_000))
  private val etcConfig: BlockchainConfig = Config.blockchains.blockchainConfig.copy(networkType = NetworkType.ETC)
  private val etcHeader: BlockHeader =
    Fixtures.Blocks.ValidBlock.header.copy(number = BlockNumber(20_000_000), gasLimit = GasAmount(60_000_000))

  private def op(b: Int) = ByteString(b.toByte)
  private def push2(v: Int) = ByteString(0x61.toByte, (v >> 8).toByte, v.toByte)
  private def push1(v: Int) = ByteString(0x60.toByte, v.toByte)

  /** CREATE(value = 1000 > Entry's balance of 100, offset 0x40, size 0x574), result popped. */
  private val failingCreate = push2(0x574) ++ push1(0x40) ++ push2(1000) ++ op(0xf0) ++ op(0x50)
  private val mstoreFar = push1(0) ++ push2(0xa2c4) ++ op(0x52)
  private val sstoreMsize = op(0x59) ++ push1(0) ++ op(0x55)

  /** Touch the same range up front with MSTORE8 so the CREATE's own expansion charge is zero. */
  private val preExpand = push1(0) ++ push2(0x5b3) ++ op(0x53)

  private def world(code: ByteString): InMemoryWorldStateProxy =
    InMemoryWorldStateProxy(
      setup.storagesInstance.storages.evmCodeStorage,
      setup.blockchain.getBackingMptStorage(-1),
      (_: BigInt) => None,
      UInt256.Zero,
      ByteString(MerklePatriciaTrie.EmptyRootHash),
      noEmptyAccounts = true,
      ethCompatibleStorage = true
    ).saveAccount(senderAddress, Account(nonce = UInt256(0), balance = UInt256(BigInt("1000000000000000000000"))))
      .saveAccount(
        Entry,
        Account(
          nonce = UInt256(1),
          balance = UInt256(100),
          codeHash = CodeHash(com.chipprbots.ethereum.crypto.kec256(code))
        )
      )
      .saveCode(Entry, code)

  private def run(code: ByteString, cfg: BlockchainConfig, hdr: BlockHeader): TxResult =
    val stx =
      if cfg.networkType == NetworkType.ETC then legacyTx(Some(Entry), 0, 3_000_000, config = cfg)
      else dynamicFeeTx(Some(Entry), 0, 3_000_000, config = cfg)
    execute(stx, hdr, world(code), cfg)

  private val schedules: Seq[(String, BlockchainConfig, BlockHeader)] = Seq(
    ("Amsterdam", amsterdamConfig, header),
    ("pre-Amsterdam ETH", preAmsterdamConfig, osakaHeader),
    ("ETC", etcConfig, etcHeader)
  )

  schedules.foreach { case (name, cfg, hdr) =>
    s"a failed CREATE on $name" should "leave memory expanded over its initcode range" taggedAs (
      VMTest,
      ConsensusTest
    ) in {
      val r = run(failingCreate ++ sstoreMsize, cfg, hdr)
      r.vmError shouldBe None
      // [0x40, 0x5b4) rounds to 0x5c0
      r.worldState.getStorage(Entry).load(UInt256(0)) shouldBe BigInt(0x5c0)
    }

    it should "not charge the expansion a second time at the next memory opcode" taggedAs (VMTest, ConsensusTest) in {
      val lazyExpansion = run(failingCreate ++ mstoreFar, cfg, hdr).gasUsed
      val eagerExpansion = run(preExpand ++ failingCreate ++ mstoreFar, cfg, hdr).gasUsed
      // Eager pays PUSH1 + PUSH2 + MSTORE8 (3 + 3 + 3) on top, for the same 142 expansion; the 142 charged once.
      // Before the fix lazy paid it twice: lazy - eager = +133 instead of -9.
      (lazyExpansion - eagerExpansion) shouldBe BigInt(-9)
    }
  }
