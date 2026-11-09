package com.chipprbots.ethereum.ledger

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Logger
import com.chipprbots.ethereum.utils.NetworkType

/** A proof-of-work block's rewards, credited to the state as block import credits them (yellow paper section 11.3,
  * ECIP-1017, ECIP-1111). Block import and mining pay [[payProofOfWork]] (`BlockPreparator.payBlockReward`).
  * eth_simulateV1 (`EthSimulateService`) pays the miner and ommer rewards only, [[payMinerAndOmmers]], the same code
  * import pays them with.
  *
  * Which blocks are proof-of-work is each caller's question: block import asks the header (`BlockHeader.isPoS`), and
  * eth_simulateV1 asks the difficulty it gave the block, as go-ethereum's engine does.
  */
object BlockRewards extends Logger:

  /** The reward schedule of `blockchainConfig`: ECIP-1017's eras on ETC (5,000,000 blocks on mainnet, 2,000,000 on
    * Mordor, 20% less each era), and the Frontier, Byzantium and Constantinople rewards on ETH (one era, no reduction).
    */
  def calculator(using blockchainConfig: BlockchainConfig): BlockRewardCalculator =
    new BlockRewardCalculator(
      blockchainConfig.monetaryPolicyConfig,
      blockchainConfig.forkBlockNumbers.byzantiumBlockNumber,
      blockchainConfig.forkBlockNumbers.constantinopleBlockNumber
    )

  /** Credits a proof-of-work block's rewards in block import's order:
    *   1. from Olympia, ECIP-1111's `baseFee * header.gasUsed` to the treasury (before the miner, per ECIP-1111); 2.
    *      the miner: the block reward, plus 1/32 of it for each ommer; 3. each ommer's miner: its inclusion reward.
    *
    * `header.gasUsed` must be the block's gas used, as a sealed header carries it: the treasury credit is read from it.
    *
    * @param header
    *   the block's header: number, beneficiary, base fee and gas used are read
    * @param ommers
    *   the block's ommer headers (none for a simulated block)
    * @param world
    *   the state after the block's transactions
    * @return
    *   the state after the rewards
    */
  def payProofOfWork(
      header: BlockHeader,
      ommers: Seq[BlockHeader],
      world: InMemoryWorldStateProxy
  )(using blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    // ECIP-1111: Treasury credit BEFORE miner/ommer rewards (spec order per ECIP-1111)
    val worldAfterTreasury = creditBaseFeeToTreasury(header, blockchainConfig.treasuryAddress, world)
    payMinerAndOmmers(header, ommers, worldAfterTreasury)

  /** Credits a proof-of-work block's miner and ommer rewards, steps 2 and 3 of [[payProofOfWork]]: the miner gets the
    * block reward (ECIP-1017's era reward on ETC), plus 1/32 of it for each ommer; each ommer's miner gets its
    * inclusion reward. No header field the transactions decide is read.
    *
    * @param header
    *   the block's header: number and beneficiary are read
    * @param ommers
    *   the block's ommer headers (none for a simulated block)
    * @param world
    *   the state to credit
    * @return
    *   the state after the rewards
    */
  def payMinerAndOmmers(
      header: BlockHeader,
      ommers: Seq[BlockHeader],
      world: InMemoryWorldStateProxy
  )(using blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    val rewards = calculator
    val blockNumber = header.number.value
    val minerRewardForBlock = rewards.calculateMiningRewardForBlock(blockNumber)
    val minerRewardForOmmers = rewards.calculateMiningRewardForOmmers(blockNumber, ommers.size)
    val minerAddress = Address(header.beneficiary)

    val minerReward = minerRewardForOmmers + minerRewardForBlock

    val worldAfterPayingBlockReward = increaseAccountBalance(minerAddress, UInt256(minerReward))(world)
    log.debug("Paying block {} reward of {} to miner with address {}", blockNumber, minerReward, minerAddress)

    ommers.foldLeft(worldAfterPayingBlockReward) { (ws, ommer) =>
      val ommerAddress = Address(ommer.beneficiary)
      val ommerReward = rewards.calculateOmmerRewardForInclusion(blockNumber, ommer.number.value)

      log.debug(
        "Paying block {} reward of {} to ommer with account address {}",
        blockNumber,
        ommerReward,
        ommerAddress
      )
      increaseAccountBalance(ommerAddress, UInt256(ommerReward))(ws)
    }

  /** ECIP-1111: Credit baseFee * gasUsed to treasury. Applied BEFORE miner and ommer rewards per ECIP-1111 spec. */
  private def creditBaseFeeToTreasury(
      blockHeader: BlockHeader,
      treasuryAddress: Address,
      world: InMemoryWorldStateProxy
  )(using blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    val isOlympiaActivated = blockHeader.number.value >= blockchainConfig.forkBlockNumbers.olympiaBlockNumber
    if !isOlympiaActivated then world
    else
      if treasuryAddress == Address(0) && blockchainConfig.networkType == NetworkType.ETC then
        throw new IllegalStateException(
          s"OLYMPIA SAFETY: treasury address is zero at block ${blockHeader.number}. " +
            "Set treasury-address in chain config before activating Olympia."
        )

      blockHeader.baseFee match
        case Some(baseFee) if baseFee > 0 && blockHeader.gasUsed > GasAmount.Zero && treasuryAddress != Address(0) =>
          val treasuryCredit = baseFee * blockHeader.gasUsed.value
          log.debug(
            "Crediting baseFee revenue {} (baseFee={} * gasUsed={}) to treasury {}",
            treasuryCredit,
            baseFee,
            blockHeader.gasUsed,
            treasuryAddress
          )
          increaseAccountBalance(treasuryAddress, UInt256(treasuryCredit))(world)
        case _ => world

  private def increaseAccountBalance(address: Address, value: UInt256)(
      world: InMemoryWorldStateProxy
  )(using blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    val account =
      world.getAccount(address).getOrElse(Account.empty(blockchainConfig.accountStartNonce)).increaseBalance(value)
    world.saveAccount(address, account)
