package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigFactory
import org.bouncycastle.util.encoders.Hex
import org.json4s.*
import org.json4s.native.JsonMethods.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.blockchain.sync.EphemBlockchainTestSetup
import com.chipprbots.ethereum.blockchain.sync.snap.PosHeaderFixtures
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.HefPostOlympia
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** B-169: Sepolia's proof-of-work blocks pay go-ethereum's block reward, 2 ETH, and its proof-of-stake blocks pay
  * nothing, on the shipped Sepolia config.
  *
  * The config paid 0 from its first version (15f468c5f1, the Engine API's introduction; 404de9937 only filled in the
  * reduced-reward keys with 0 too). The post-merge skip it might have stood in for was already in that commit, on the
  * header (`BlockHeader.isPoS`), so the zero only cost the 1,450,408 proof-of-work blocks their 2 ETH, and a sync from
  * genesis would have failed block 1's state root. go-ethereum: `consensus/ethash/consensus.go`
  * `ConstantinopleBlockReward` and `accumulateRewards`, `consensus/beacon/consensus.go` `Finalize` (the ethash rewards
  * for a header that is not `IsPoSHeader`), `params/chainspecs/sepolia.json` (Byzantium and Constantinople at 0).
  *
  * The blocks are Sepolia's own, and the state roots are the chain's. Each rewarded block has no transactions and no
  * ommers, so the reward is the only change to the state, and it touches one account: its miner. A go-ethereum archive
  * node's `eth_getProof` for that account at the parent is every trie node on the path the reward rewrites, so the
  * parent's state root is held here as that path alone, and block import (execute, then validate the header) must reach
  * the block's real state root on it. Fixtures: `sepolia/premerge-rewards.json` (blocks 0 and 1, and the proofs) and
  * `sepolia/headers-merge-and-forks.txt` (1,450,407 to 1,450,410), both checked against their block hashes.
  */
// scalastyle:off magic.number
class SepoliaPreMergeRewardSpec extends AnyFlatSpec with Matchers:

  private lazy val shippedRoot: TypesafeConfig =
    ConfigFactory.parseResources("conf/base/blockchains.conf").atPath("fukuii").resolve()

  private lazy val sepolia: BlockchainConfig =
    BlockchainConfig.fromRawConfig(shippedRoot.getConfig("fukuii.blockchains.sepolia"))

  private val TwoEth = BigInt(2) * BigInt(10).pow(18)

  private lazy val fixture: JValue =
    val src = scala.io.Source.fromResource("sepolia/premerge-rewards.json")
    try parse(src.mkString)
    finally src.close()

  private def str(v: JValue): String = v.values.toString
  private def bytes(v: JValue): ByteString = ByteString(Hex.decode(str(v).stripPrefix("0x")))
  private def quantity(v: JValue): BigInt = BigInt(str(v).stripPrefix("0x"), 16)

  /** Block `index` of the fixture (0 or 1), rebuilt from its JSON fields; its hash must be the chain's. */
  private def jsonHeader(index: Int): BlockHeader =
    val JArray(headers) = fixture \ "headers": @unchecked
    val h = headers(index)
    val header = BlockHeader(
      parentHash = BlockHash(bytes(h \ "parentHash")),
      ommersHash = BlockHash(bytes(h \ "sha3Uncles")),
      beneficiary = bytes(h \ "miner"),
      stateRoot = TrieRoot(bytes(h \ "stateRoot")),
      transactionsRoot = TrieRoot(bytes(h \ "transactionsRoot")),
      receiptsRoot = TrieRoot(bytes(h \ "receiptsRoot")),
      logsBloom = BloomFilter(bytes(h \ "logsBloom")),
      difficulty = Difficulty(quantity(h \ "difficulty")),
      number = BlockNumber(quantity(h \ "number")),
      gasLimit = GasAmount(quantity(h \ "gasLimit")),
      gasUsed = GasAmount(quantity(h \ "gasUsed")),
      unixTimestamp = Timestamp(quantity(h \ "timestamp").toLong),
      extraData = bytes(h \ "extraData"),
      mixHash = BlockHash(bytes(h \ "mixHash")),
      nonce = bytes(h \ "nonce"),
      extraFields = HefPostOlympia(quantity(h \ "baseFeePerGas"))
    )
    require(header.hash.value == bytes(h \ "hash"), s"fixture block $index does not hash to ${str(h \ "hash")}")
    header

  private lazy val genesis: BlockHeader = jsonHeader(0)
  private lazy val block1: BlockHeader = jsonHeader(1)
  private lazy val block1450407: BlockHeader = PosHeaderFixtures.at(PosHeaderFixtures.sepolia, 1_450_407)
  private lazy val block1450408: BlockHeader = PosHeaderFixtures.at(PosHeaderFixtures.sepolia, 1_450_408)
  private lazy val block1450409: BlockHeader = PosHeaderFixtures.at(PosHeaderFixtures.sepolia, 1_450_409)

  /** The miner's account proof for `block` at `when` ("before": the parent, "after": the block). */
  private def proof(block: String, when: String): JValue = fixture \ "proofs" \ block \ when
  private def proofNodes(p: JValue): Seq[Array[Byte]] =
    val JArray(nodes) = p \ "accountProof": @unchecked
    nodes.map(n => Hex.decode(str(n).stripPrefix("0x")))

  private trait Setup extends EphemBlockchainTestSetup:
    implicit override lazy val blockchainConfig: BlockchainConfig = sepolia
    lazy val exec: BlockExecution = mkBlockExecution()

    /** Stores `parent`, and of its state the trie path `p` proves: every node the reward to that account rewrites. */
    def holdParent(parent: BlockHeader, p: JValue): Unit =
      val nodes = proofNodes(p)
      ByteString(kec256(nodes.head)) shouldBe parent.stateRoot.value
      storagesInstance.storages.nodeStorage.update(Nil, nodes.map(n => ByteString(kec256(n)) -> n))
      blockchainWriter.save(Block(parent, BlockBody.empty), Nil, ChainWeight.zero, saveAsBestBlock = true)

    def balanceAt(root: TrieRoot, address: Address): BigInt =
      InMemoryWorldStateProxy(
        storagesInstance.storages.evmCodeStorage,
        blockchain.getBackingMptStorage(-1),
        (_: BigInt) => None,
        sepolia.accountStartNonce,
        root.value,
        noEmptyAccounts = true,
        ethCompatibleStorage = sepolia.ethCompatibleStorage
      ).getAccount(address).map(_.balance.toBigInt).getOrElse(BigInt(0))

  "The Sepolia reward fixtures" should "be the chain's blocks: 0, 1 and 1,450,407 to 1,450,409, with no transactions or ommers" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    genesis.number.value shouldBe 0
    Hex.toHexString(genesis.hash.value.toArray) shouldBe
      "25a5cc106eea7138acab33231d7160d69cb777ee0c2c553fcddf5138993e6dd9"
    block1.parentHash shouldBe genesis.hash
    for h <- Seq(block1, block1450408, block1450409) do
      withClue(s"block ${h.number}: ") {
        h.transactionsRoot.value shouldBe ByteString(MerklePatriciaTrie.EmptyRootHash)
        h.ommersHash.value shouldBe BlockHeader.EmptyOmmers
      }
    block1.isPoS shouldBe false
    block1450408.isPoS shouldBe false
    block1450409.isPoS shouldBe true
    block1450408.number.value shouldBe PosHeaderFixtures.SepoliaTerminalBlock
  }

  "The shipped Sepolia reward schedule" should "be go-ethereum's: 2 ETH a block from genesis to the merge" taggedAs (
    UnitTest,
    ConsensusTest
  ) in {
    val rewards = BlockRewards.calculator(using sepolia)
    for n <- Seq(BigInt(1), BigInt(1_450_408)) do rewards.calculateMiningRewardForBlock(n) shouldBe TwoEth
    // go-ethereum accumulateRewards: the miner gets reward / 32 per ommer; an ommer's miner (ommer + 8 - number) / 8.
    rewards.calculateMiningRewardForOmmers(1_000_000, 2) shouldBe TwoEth / 32 * 2
    rewards.calculateOmmerRewardForInclusion(1_000_000, 999_999) shouldBe TwoEth * 7 / 8
    rewards.calculateOmmerRewardForInclusion(1_000_000, 999_994) shouldBe TwoEth * 2 / 8
  }

  "Block import on Sepolia" should "pay block 1's miner 2 ETH and reach block 1's state root" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    val before: JValue = proof("block1", "before")
    holdParent(genesis, before)
    exec.executeAndValidateBlock(Block(block1, BlockBody.empty)) shouldBe Right(Nil)

    val miner: Address = Address(block1.beneficiary)
    quantity(before \ "balance") shouldBe 0
    balanceAt(block1.stateRoot, miner) shouldBe TwoEth
    balanceAt(block1.stateRoot, miner) shouldBe quantity(proof("block1", "after") \ "balance")

  it should "pay the terminal proof-of-work block 1,450,408's miner 2 ETH and reach its state root" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    val before: JValue = proof("block1450408", "before")
    holdParent(block1450407, before)
    exec.executeAndValidateBlock(Block(block1450408, BlockBody.empty)) shouldBe Right(Nil)

    val miner: Address = Address(block1450408.beneficiary)
    balanceAt(block1450408.stateRoot, miner) shouldBe quantity(before \ "balance") + TwoEth
    balanceAt(block1450408.stateRoot, miner) shouldBe quantity(proof("block1450408", "after") \ "balance")

  it should "pay nothing for the first proof-of-stake block 1,450,409: its state root is its parent's" taggedAs (
    UnitTest,
    ConsensusTest
  ) in new Setup:
    holdParent(block1450407, proof("block1450408", "before"))
    exec.executeAndValidateBlock(Block(block1450408, BlockBody.empty)) shouldBe Right(Nil)
    blockchainWriter.save(Block(block1450408, BlockBody.empty), Nil, ChainWeight.zero, saveAsBestBlock = true)

    block1450409.stateRoot shouldBe block1450408.stateRoot
    exec.executeAndValidateBlock(Block(block1450409, BlockBody.empty)) shouldBe Right(Nil)
