package com.chipprbots.ethereum.consensus.isolation

import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.flatspec.AnyFlatSpec

import com.chipprbots.ethereum.domain.Timestamp
import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.Config
import com.chipprbots.ethereum.utils.NetworkType
import com.chipprbots.ethereum.vm.EvmConfig

/** ETC/ETH fork-dispatch isolation — Constitution Principle I.3–I.5, issue #1404.
  *
  * Principle I says the two chain families MUST NOT share a dispatch path: ETC uses
  * `OlympiaOpCodes` / `forBlock()`, ETH uses `OsakaOpCodes` / `forTimestamp()`. Until this spec
  * existed, nothing asserted it.
  *
  * What actually protects ETC today is narrower and more fragile than the principle suggests.
  * `EvmConfig.forBlock(blockNumber, timestamp, config)` applies ETH timestamp-fork overrides —
  * Shanghai, Cancun, Prague, Osaka — to *whatever config it is handed*. It does not ask which
  * chain family that config belongs to. An ETC block is safe from Osaka semantics for exactly one
  * reason: ETC chain configs leave `forkTimestamps` unset, so every `is*Timestamp` predicate
  * returns false and the overrides are inert.
  *
  * That is a convention, and before this spec it was one config edit away from a chain split —
  * with no test to catch it and consensus divergence as the failure mode. This spec converts the
  * convention into an enforced invariant, in both directions, over **every** chain in
  * `blockchains.conf` rather than a hardcoded subset, so a chain added tomorrow is covered the day
  * it lands (FR-024).
  *
  * The naming collision makes the risk easy to miss on review: `EvmConfig.OlympiaOpCodes` is used
  * for ETC's Olympia fork *and* for ETH's Cancun. Reading the dispatch table is not enough to see
  * the boundary; that is precisely why it needs a test.
  *
  * This spec asserts over existing configuration and changes no consensus behaviour. If an
  * assertion here ever fails, the config is the bug — route it through `forge` (ETC) or `beacon`
  * (ETH) per the Consensus-Critical Change Protocol. Do not "fix" a failure by weakening an
  * assertion.
  */
class ChainIsolationSpec extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks:

  private val allChains: Map[String, BlockchainConfig] = Config.blockchains.blockchains

  private val etcChains: Seq[(String, BlockchainConfig)] =
    allChains.toSeq.filter(_._2.networkType == NetworkType.ETC).sortBy(_._1)

  private val ethChains: Seq[(String, BlockchainConfig)] =
    allChains.toSeq.filter(_._2.networkType == NetworkType.ETH).sortBy(_._1)

  /** ECIP-1017 era length on a real ETC chain is 5M blocks (mainnet) / 2M (Mordor). ETH-family
    * configs disable era-based emission by setting a sentinel era far beyond any reachable height.
    * Anything below this bound means ECIP-1017 emission is live.
    */
  private val Ecip1017DisabledSentinel: BigInt = BigInt(1_000_000_000L)

  /** Timestamps spanning the real ETH fork schedule plus the far future, used to prove that
    * timestamp dispatch is inert on ETC for every value a block could carry.
    */
  private val probeTimestamps: Seq[Long] = Seq(
    0L, // genesis
    1_677_557_088L, // Sepolia Shanghai
    1_706_655_072L, // Sepolia Cancun
    1_741_159_776L, // Sepolia Prague
    1_760_427_360L, // Sepolia Osaka
    4_102_444_800L // 2100-01-01 — far beyond any scheduled fork
  )

  "Chain configuration" should "declare at least one chain of each family" taggedAs (UnitTest) in {
    withClue("no ETC chains found — the ETC assertions below would vacuously pass: ") {
      etcChains should not be empty
    }
    withClue("no ETH chains found — the ETH assertions below would vacuously pass: ") {
      ethChains should not be empty
    }
  }

  // --- Principle I.3 / I.4: nothing from the ETH path may reach an ETC chain ----------------

  "An ETC chain" should "declare no ETH fork timestamps" taggedAs (UnitTest) in {
    forAll(Table(("chain", "config"), etcChains*)) { (name, config) =>
      val ts = config.forkTimestamps
      val set = Seq(
        "shanghai" -> ts.shanghaiTimestamp,
        "cancun" -> ts.cancunTimestamp,
        "prague" -> ts.pragueTimestamp,
        "osaka" -> ts.osakaTimestamp,
        "bpo1" -> ts.bpo1Timestamp,
        "bpo2" -> ts.bpo2Timestamp
      ).collect { case (fork, Some(value)) => s"$fork=$value" }

      withClue(
        s"ETC chain '$name' sets ETH fork timestamp(s) [${set.mkString(", ")}]. " +
          "EvmConfig.forBlock(blockNumber, timestamp, config) applies ETH timestamp-fork " +
          "overrides to any config that carries them — including Osaka opcodes and fee " +
          "schedules. Setting one on an ETC chain activates ETH semantics on ETC blocks and " +
          "splits the chain (Constitution I.3). "
      )(set shouldBe empty)
    }
  }

  it should "declare no terminal total difficulty — ETC does not merge" taggedAs (UnitTest) in {
    forAll(Table(("chain", "config"), etcChains*)) { (name, config) =>
      withClue(
        s"ETC chain '$name' declares terminalTotalDifficulty=${config.terminalTotalDifficulty}. " +
          "BlockchainConfig.isPoS returns true once total difficulty crosses it, which would " +
          "route an ETC chain onto the PoS path. ETC is and remains Proof-of-Work " +
          "(Constitution I.4). "
      )(config.terminalTotalDifficulty shouldBe None)
    }
  }

  it should "keep ECIP-1017 era-based emission active" taggedAs (UnitTest) in {
    forAll(Table(("chain", "config"), etcChains*)) { (name, config) =>
      val era = config.monetaryPolicyConfig.eraDuration
      withClue(
        s"ETC chain '$name' has eraDuration=$era, at or above the disabled sentinel " +
          s"$Ecip1017DisabledSentinel. That is the ETH-family setting: it switches off " +
          "ECIP-1017 fixed-supply emission and changes block rewards (Constitution I.1). "
      )(BigInt(era) should be < Ecip1017DisabledSentinel)
    }
  }

  it should "produce an EVM config unaffected by block timestamp" taggedAs (UnitTest) in {
    // The load-bearing assertion. Rather than trusting that forkTimestamps is unset, invoke the
    // timestamp-aware overload directly across the ETH fork schedule and require the result to be
    // identical to the block-number-only overload. If any ETH override ever leaks onto an ETC
    // config — by a config edit, a default change, or a new override added without a family guard
    // — these differ and CI goes red before a node forks.
    forAll(Table(("chain", "config"), etcChains*)) { (name, config) =>
      val probeBlocks = Seq(
        BigInt(0),
        config.forkBlockNumbers.byzantiumBlockNumber.max(1),
        config.forkBlockNumbers.atlantisBlockNumber.max(1),
        config.forkBlockNumbers.magnetoBlockNumber.max(1),
        BigInt(25_000_000)
      ).distinct

      for
        block <- probeBlocks
        ts <- probeTimestamps
      do
        val blockOnly = EvmConfig.forBlock(block, config)
        val withTimestamp = EvmConfig.forBlock(block, Timestamp(ts), config)

        withClue(
          s"ETC chain '$name' at block $block, timestamp $ts: the timestamp-aware forBlock " +
            "overload returned a DIFFERENT EvmConfig than the block-number-only overload. " +
            "Timestamp fork dispatch belongs to the ETH path and MUST be inert on ETC " +
            "(Constitution I.3). "
        ) {
          withTimestamp.opCodeList shouldBe blockOnly.opCodeList
          withTimestamp.feeSchedule.getClass shouldBe blockOnly.feeSchedule.getClass
          withTimestamp.eip3651Enabled shouldBe blockOnly.eip3651Enabled
          withTimestamp.eip3860Enabled shouldBe blockOnly.eip3860Enabled
          withTimestamp.eip6780Enabled shouldBe blockOnly.eip6780Enabled
        }
    }
  }

  // --- Principle I.5: nothing from the ETC path may reach an ETH chain ----------------------

  "An ETH chain" should "not activate ECIP-1017 era-based emission" taggedAs (UnitTest) in {
    forAll(Table(("chain", "config"), ethChains*)) { (name, config) =>
      val era = config.monetaryPolicyConfig.eraDuration
      withClue(
        s"ETH chain '$name' has eraDuration=$era, below the disabled sentinel " +
          s"$Ecip1017DisabledSentinel — ECIP-1017 fixed-supply emission would apply. That is " +
          "an ETC-only rule and must not enter the ETH/Sepolia path (Constitution I.5). "
      )(BigInt(era) should be >= Ecip1017DisabledSentinel)
    }
  }

  it should "not activate the ETC-only Thanos/ECIP-1099 epoch change" taggedAs (UnitTest) in {
    forAll(Table(("chain", "config"), ethChains*)) { (name, config) =>
      val ecip1099 = config.forkBlockNumbers.ecip1099BlockNumber
      withClue(
        s"ETH chain '$name' activates ECIP-1099 (Thanos epoch halving) at block $ecip1099. " +
          "ECIP-1099 alters Ethash DAG epoch sizing and is ETC-only; post-Merge ETH has no " +
          "Ethash at all (Constitution I.5). "
      )(ecip1099 should be >= Ecip1017DisabledSentinel)
    }
  }

  it should "declare the ETH fork timestamps its dispatch depends on" taggedAs (UnitTest) in {
    // The mirror of the ETC case: ETH chains dispatch by timestamp, so a live ETH chain that
    // declares none has silently fallen back to block-number dispatch and would miss every
    // post-Merge fork. `hive` is exempt — it is a harness chain whose schedule the simulator
    // supplies per test.
    forAll(Table(("chain", "config"), ethChains.filterNot(_._1 == "hive")*)) { (name, config) =>
      withClue(
        s"ETH chain '$name' declares no Shanghai timestamp. ETH fork dispatch is " +
          "timestamp-based; without it the chain would never advance past the Merge-era " +
          "ruleset (Constitution I.3). "
      )(config.forkTimestamps.shanghaiTimestamp shouldBe defined)
    }
  }

  // --- Family assignment is explicit, not accidental -----------------------------------------

  "Every configured chain" should "belong to exactly one family" taggedAs (UnitTest) in {
    val classified = (etcChains ++ ethChains).map(_._1).toSet
    withClue(
      "chains missing from both families — NetworkType.fromString defaults to ETC, so an " +
        "unclassified chain silently inherits ETC rules: "
    )(allChains.keySet.diff(classified) shouldBe empty)
  }
