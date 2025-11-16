package com.chipprbots.ethereum.testing

import scala.concurrent.duration.*

/**
 * KPI Baselines for Fukuii Test Suite
 *
 * This object defines baseline Key Performance Indicators (KPIs) for test execution,
 * performance benchmarks, and system health metrics. These baselines are used for
 * regression detection and performance monitoring.
 *
 * Based on:
 * - ADR-015: Ethereum/Tests Adapter Implementation
 * - ADR-017: Test Suite Strategy, KPIs, and Execution Benchmarks
 * - docs/testing/KPI_BASELINES.md
 * - docs/testing/PERFORMANCE_BASELINES.md
 *
 * @see [[https://github.com/chippr-robotics/fukuii/blob/main/docs/adr/017-test-suite-strategy-and-kpis.md ADR-017]]
 * @since 1.0.0
 * @version 1.0
 * @author Chippr Robotics Engineering Team
 */
object KPIBaselines {

  /**
   * Baseline established date
   */
  val baselineDate: String = "2025-11-16"

  /**
   * Test execution time baselines
   */
  object TestExecutionTime {
    
    /**
     * Tier 1: Essential Tests
     */
    object Essential {
      val target: FiniteDuration = 5.minutes
      val warningThreshold: FiniteDuration = 7.minutes
      val failureThreshold: FiniteDuration = 10.minutes
      val baseline: FiniteDuration = 4.minutes // Typical execution time
    }

    /**
     * Tier 2: Standard Tests
     */
    object Standard {
      val target: FiniteDuration = 30.minutes
      val warningThreshold: FiniteDuration = 40.minutes
      val failureThreshold: FiniteDuration = 60.minutes
      val baseline: FiniteDuration = 22.minutes // Typical execution time
    }

    /**
     * Tier 3: Comprehensive Tests
     */
    object Comprehensive {
      val target: FiniteDuration = 3.hours
      val warningThreshold: FiniteDuration = 4.hours
      val failureThreshold: FiniteDuration = 5.hours
      val baseline: FiniteDuration = 90.minutes // Typical execution time (Phase 2)
    }
  }

  /**
   * Test health KPI baselines
   */
  object TestHealth {
    
    /**
     * Test success rate baseline (percentage)
     */
    val successRateTarget: Double = 99.0
    val successRateBaseline: Double = 99.5

    /**
     * Test flakiness rate baseline (percentage)
     */
    val flakinessRateTarget: Double = 1.0
    val flakinessRateBaseline: Double = 0.5

    /**
     * Test coverage baselines (percentage)
     */
    object Coverage {
      val lineTarget: Double = 80.0
      val lineBaseline: Double = 75.0
      
      val branchTarget: Double = 70.0
      val branchBaseline: Double = 65.0
    }

    /**
     * Actor cleanup success rate (percentage)
     */
    val actorCleanupTarget: Double = 100.0
    val actorCleanupBaseline: Double = 100.0
  }

  /**
   * Ethereum/Tests compliance KPI baselines
   */
  object EthereumTestsCompliance {
    
    /**
     * GeneralStateTests pass rate (percentage)
     */
    object GeneralStateTests {
      val target: Double = 95.0
      val baseline: Double = 100.0 // SimpleTx tests (Phase 2)
    }

    /**
     * BlockchainTests pass rate (percentage)
     */
    object BlockchainTests {
      val target: Double = 90.0
      val baseline: Double = 100.0 // SimpleTx tests (Phase 2)
    }

    /**
     * TransactionTests pass rate (percentage)
     */
    object TransactionTests {
      val target: Double = 95.0
      val baseline: Option[Double] = None // Pending Phase 3
    }

    /**
     * VMTests pass rate (percentage)
     */
    object VMTests {
      val target: Double = 95.0
      val baseline: Option[Double] = None // Pending Phase 3
    }
  }

  /**
   * Performance benchmark baselines (in milliseconds)
   */
  object PerformanceBenchmarks {
    
    /**
     * Block validation timing baseline
     */
    object BlockValidation {
      val target: FiniteDuration = 100.millis
      val regressionThreshold: FiniteDuration = 120.millis
      
      case class Percentiles(p50: FiniteDuration, p95: FiniteDuration, p99: FiniteDuration)
      
      val emptyBlock: Percentiles = Percentiles(30.millis, 45.millis, 60.millis)
      val simpleTxBlock: Percentiles = Percentiles(60.millis, 90.millis, 120.millis)
      val complexTxBlock: Percentiles = Percentiles(80.millis, 130.millis, 180.millis)
      val fullBlock: Percentiles = Percentiles(95.millis, 160.millis, 220.millis)
    }

    /**
     * Transaction execution timing baseline
     */
    object TransactionExecution {
      val simpleTransferTarget: FiniteDuration = 1.millis
      val contractCallTarget: FiniteDuration = 10.millis
      val regressionThreshold: FiniteDuration = 1.2.millis
      
      case class Percentiles(p50: FiniteDuration, p95: FiniteDuration, p99: FiniteDuration)
      
      val simpleTransfer: Percentiles = Percentiles(0.3.millis, 0.5.millis, 0.8.millis)
      val contractCallSimple: Percentiles = Percentiles(2.millis, 4.millis, 6.millis)
      val contractCallComplex: Percentiles = Percentiles(8.millis, 15.millis, 25.millis)
      val contractCreation: Percentiles = Percentiles(12.millis, 20.millis, 30.millis)
    }

    /**
     * State root calculation timing baseline
     */
    object StateRootCalculation {
      val target: FiniteDuration = 50.millis
      val regressionThreshold: FiniteDuration = 60.millis
      
      case class Percentiles(p50: FiniteDuration, p95: FiniteDuration, p99: FiniteDuration)
      
      val smallState: Percentiles = Percentiles(15.millis, 20.millis, 25.millis)      // < 100 accounts
      val mediumState: Percentiles = Percentiles(40.millis, 50.millis, 60.millis)     // 100-1000 accounts
      val largeState: Percentiles = Percentiles(100.millis, 150.millis, 200.millis)   // 1000-10000 accounts
    }

    /**
     * RLP encoding/decoding timing baseline
     */
    object RLPOperations {
      val target: FiniteDuration = 100.micros
      val regressionThreshold: FiniteDuration = 120.micros
      
      case class Percentiles(p50: FiniteDuration, p95: FiniteDuration)
      
      val tinyPayload: Percentiles = Percentiles(10.micros, 20.micros)      // < 100 bytes
      val smallPayload: Percentiles = Percentiles(30.micros, 50.micros)     // < 1 KB
      val mediumPayload: Percentiles = Percentiles(100.micros, 150.micros)  // 1-10 KB
    }

    /**
     * Cryptographic operations timing baseline
     */
    object CryptoOperations {
      val target: FiniteDuration = 1.millis
      val regressionThreshold: FiniteDuration = 1.2.millis
      
      case class Percentiles(p50: FiniteDuration, p95: FiniteDuration)
      
      val ecdsaSign: Percentiles = Percentiles(0.5.millis, 0.8.millis)
      val ecdsaVerify: Percentiles = Percentiles(0.8.millis, 1.2.millis)
      val ecdsaRecover: Percentiles = Percentiles(1.0.millis, 1.5.millis)
      val keccak256Small: Percentiles = Percentiles(10.micros, 20.micros)
    }

    /**
     * Network operations timing baseline
     */
    object NetworkOperations {
      val handshakeTarget: FiniteDuration = 500.millis
      val regressionThreshold: FiniteDuration = 600.millis
      
      case class Percentiles(p50: FiniteDuration, p95: FiniteDuration)
      
      val peerHandshakeLocal: Percentiles = Percentiles(100.millis, 150.millis)
      val peerHandshakeRemote: Percentiles = Percentiles(300.millis, 500.millis)
      val messageEncode: Percentiles = Percentiles(200.micros, 500.micros)
      val messageDecode: Percentiles = Percentiles(300.micros, 600.micros)
    }

    /**
     * Database operations timing baseline
     */
    object DatabaseOperations {
      val target: FiniteDuration = 1.millis
      val regressionThreshold: FiniteDuration = 1.2.millis
      
      case class Percentiles(p50: FiniteDuration, p95: FiniteDuration)
      
      val singleGet: Percentiles = Percentiles(100.micros, 300.micros)
      val singlePut: Percentiles = Percentiles(200.micros, 500.micros)
      val batchGet10: Percentiles = Percentiles(500.micros, 1.millis)
      val batchPut10: Percentiles = Percentiles(1.millis, 2.millis)
    }
  }

  /**
   * Memory usage baselines (in megabytes)
   */
  object MemoryBaselines {
    val heapTarget: Int = 2048 // MB
    val regressionThreshold: Int = 2400 // MB (20% over target)
    
    case class MemoryProfile(initial: Int, peak: Int, stable: Int)
    
    val nodeStartup: MemoryProfile = MemoryProfile(100, 300, 200)
    val syncingFast: MemoryProfile = MemoryProfile(200, 1500, 800)
    val syncingFull: MemoryProfile = MemoryProfile(200, 2000, 1200)
    val mining: MemoryProfile = MemoryProfile(300, 1000, 600)
    val rpcServer: MemoryProfile = MemoryProfile(250, 500, 350)
    
    /**
     * GC overhead target (percentage of execution time)
     */
    val gcOverheadTarget: Double = 5.0
    val gcOverheadBaseline: Double = 2.5
    val gcOverheadThreshold: Double = 6.0
  }

  /**
   * Helper methods for baseline validation
   */
  object Validation {
    
    /**
     * Check if a duration is within baseline target
     */
    def isWithinTarget(actual: FiniteDuration, target: FiniteDuration): Boolean =
      actual <= target

    /**
     * Check if a duration represents a regression
     */
    def isRegression(actual: FiniteDuration, baseline: FiniteDuration, threshold: Double = 1.2): Boolean =
      actual > baseline * threshold

    /**
     * Calculate percentage difference from baseline
     */
    def percentageDifference(actual: FiniteDuration, baseline: FiniteDuration): Double =
      ((actual.toMillis - baseline.toMillis).toDouble / baseline.toMillis.toDouble) * 100.0

    /**
     * Check if a percentage is within target
     */
    def isPercentageWithinTarget(actual: Double, target: Double, tolerance: Double = 5.0): Boolean =
      Math.abs(actual - target) <= tolerance
  }

  /**
   * Formatted baseline summary for reporting
   */
  def summary: String = {
    s"""
       |KPI Baselines Summary (Established: $baselineDate)
       |
       |Test Execution Time:
       |  Essential:      ${TestExecutionTime.Essential.baseline.toMinutes} min (target: ${TestExecutionTime.Essential.target.toMinutes} min)
       |  Standard:       ${TestExecutionTime.Standard.baseline.toMinutes} min (target: ${TestExecutionTime.Standard.target.toMinutes} min)
       |  Comprehensive:  ${TestExecutionTime.Comprehensive.baseline.toMinutes} min (target: ${TestExecutionTime.Comprehensive.target.toMinutes} min)
       |
       |Test Health:
       |  Success Rate:   ${TestHealth.successRateBaseline}% (target: ${TestHealth.successRateTarget}%)
       |  Flakiness Rate: ${TestHealth.flakinessRateBaseline}% (target: ${TestHealth.flakinessRateTarget}%)
       |  Line Coverage:  ${TestHealth.Coverage.lineBaseline}% (target: ${TestHealth.Coverage.lineTarget}%)
       |  Branch Coverage: ${TestHealth.Coverage.branchBaseline}% (target: ${TestHealth.Coverage.branchTarget}%)
       |
       |Performance Benchmarks:
       |  Block Validation:    ${PerformanceBenchmarks.BlockValidation.simpleTxBlock.p50.toMillis} ms P50
       |  Tx Execution:        ${PerformanceBenchmarks.TransactionExecution.simpleTransfer.p50.toMicros} μs P50
       |  State Root Calc:     ${PerformanceBenchmarks.StateRootCalculation.mediumState.p50.toMillis} ms P50
       |  RLP Operations:      ${PerformanceBenchmarks.RLPOperations.smallPayload.p50.toMicros} μs P50
       |
       |Ethereum/Tests Compliance:
       |  GeneralStateTests:   ${EthereumTestsCompliance.GeneralStateTests.baseline}% (target: ${EthereumTestsCompliance.GeneralStateTests.target}%)
       |  BlockchainTests:     ${EthereumTestsCompliance.BlockchainTests.baseline}% (target: ${EthereumTestsCompliance.BlockchainTests.target}%)
       |
       |Memory Baselines:
       |  Heap Target:         ${MemoryBaselines.heapTarget} MB
       |  GC Overhead:         ${MemoryBaselines.gcOverheadBaseline}% (target: ${MemoryBaselines.gcOverheadTarget}%)
       |""".stripMargin
  }
}
