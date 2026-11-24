package com.chipprbots.ethereum.consensus.mining

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.testing.Tags.*

/** Which blocks a node counts as its own on a tie (B-284, forge N-c): those of a coinbase an operator chose, or of the
  * coinbase it mines to; with neither, none, as core-geth's `isLocalBlock` with no etherbase set.
  */
class CoinbaseProviderSpec extends AnyFlatSpec with Matchers:

  private val chosen = Address(0x6d696e)

  "CoinbaseProvider.of" should "count the shipped default coinbase as not chosen: no own blocks unless the node mines" taggedAs UnitTest in {
    val provider = CoinbaseProvider.of(MiningConfigs.miningConfig.copy(coinbase = MiningConfig.DefaultCoinbase))
    provider.get() shouldBe MiningConfig.DefaultCoinbase
    provider.ownBlocksEtherbase(miningEnabled = false) shouldBe None
    // a node that mines seals its blocks to it: they are its own
    provider.ownBlocksEtherbase(miningEnabled = true) shouldBe Some(MiningConfig.DefaultCoinbase)
  }

  it should "count a configured coinbase as chosen, mining or not" taggedAs UnitTest in {
    val provider = CoinbaseProvider.of(MiningConfigs.miningConfig.copy(coinbase = chosen))
    provider.ownBlocksEtherbase(miningEnabled = false) shouldBe Some(chosen)
    provider.ownBlocksEtherbase(miningEnabled = true) shouldBe Some(chosen)
  }

  "CoinbaseProvider.update" should "make the etherbase chosen: miner_setEtherbase on a node that does not mine" taggedAs UnitTest in {
    val provider = CoinbaseProvider.of(MiningConfigs.miningConfig.copy(coinbase = MiningConfig.DefaultCoinbase))
    provider.ownBlocksEtherbase(miningEnabled = false) shouldBe None
    provider.update(chosen)
    provider.ownBlocksEtherbase(miningEnabled = false) shouldBe Some(chosen)
  }

  "MiningConfig.DefaultCoinbase" should "be the coinbase conf/base/mining.conf ships" taggedAs UnitTest in {
    val shipped = ConfigFactory.parseResources("conf/base/mining.conf").getString("mining.coinbase")
    Address(shipped) shouldBe MiningConfig.DefaultCoinbase
  }
