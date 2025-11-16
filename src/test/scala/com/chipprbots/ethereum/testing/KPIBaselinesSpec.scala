package com.chipprbots.ethereum.testing

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

/**
 * Test suite to validate KPI baselines are properly defined and accessible.
 *
 * This test ensures that all KPI baselines documented in ADR-017 are
 * programmatically available for use in performance monitoring and
 * regression detection.
 *
 * @see [[KPIBaselines]]
 */
class KPIBaselinesSpec extends AnyFlatSpec with Matchers {

  "KPIBaselines" should "have a valid baseline date" in {
    KPIBaselines.baselineDate should not be empty
    KPIBaselines.baselineDate should fullyMatch regex """\d{4}-\d{2}-\d{2}"""
  }

  "Test Execution Time baselines" should "be defined for all tiers" in {
    KPIBaselines.TestExecutionTime.Essential.target shouldBe 5.minutes
    KPIBaselines.TestExecutionTime.Essential.warningThreshold shouldBe 7.minutes
    KPIBaselines.TestExecutionTime.Essential.failureThreshold shouldBe 10.minutes
    KPIBaselines.TestExecutionTime.Essential.baseline should be <= 5.minutes

    KPIBaselines.TestExecutionTime.Standard.target shouldBe 30.minutes
    KPIBaselines.TestExecutionTime.Standard.warningThreshold shouldBe 40.minutes
    KPIBaselines.TestExecutionTime.Standard.failureThreshold shouldBe 60.minutes
    KPIBaselines.TestExecutionTime.Standard.baseline should be <= 30.minutes

    KPIBaselines.TestExecutionTime.Comprehensive.target shouldBe 3.hours
    KPIBaselines.TestExecutionTime.Comprehensive.warningThreshold shouldBe 4.hours
    KPIBaselines.TestExecutionTime.Comprehensive.failureThreshold shouldBe 5.hours
    KPIBaselines.TestExecutionTime.Comprehensive.baseline should be <= 3.hours
  }

  "Test Health baselines" should "be defined with valid percentages" in {
    KPIBaselines.TestHealth.successRateTarget shouldBe 99.0
    KPIBaselines.TestHealth.successRateBaseline should be >= 99.0

    KPIBaselines.TestHealth.flakinessRateTarget shouldBe 1.0
    KPIBaselines.TestHealth.flakinessRateBaseline should be <= 1.0

    KPIBaselines.TestHealth.Coverage.lineTarget shouldBe 80.0
    KPIBaselines.TestHealth.Coverage.lineBaseline should be <= 80.0

    KPIBaselines.TestHealth.Coverage.branchTarget shouldBe 70.0
    KPIBaselines.TestHealth.Coverage.branchBaseline should be <= 70.0

    KPIBaselines.TestHealth.actorCleanupTarget shouldBe 100.0
    KPIBaselines.TestHealth.actorCleanupBaseline shouldBe 100.0
  }

  "Ethereum/Tests Compliance baselines" should "be defined with targets" in {
    KPIBaselines.EthereumTestsCompliance.GeneralStateTests.target shouldBe 95.0
    KPIBaselines.EthereumTestsCompliance.GeneralStateTests.baseline shouldBe 100.0

    KPIBaselines.EthereumTestsCompliance.BlockchainTests.target shouldBe 90.0
    KPIBaselines.EthereumTestsCompliance.BlockchainTests.baseline shouldBe 100.0

    KPIBaselines.EthereumTestsCompliance.TransactionTests.target shouldBe 95.0
    KPIBaselines.EthereumTestsCompliance.TransactionTests.baseline shouldBe None

    KPIBaselines.EthereumTestsCompliance.VMTests.target shouldBe 95.0
    KPIBaselines.EthereumTestsCompliance.VMTests.baseline shouldBe None
  }

  "Performance Benchmark baselines" should "be defined for block validation" in {
    val blockValidation = KPIBaselines.PerformanceBenchmarks.BlockValidation
    
    blockValidation.target shouldBe 100.millis
    blockValidation.regressionThreshold shouldBe 120.millis
    
    blockValidation.emptyBlock.p50 should be <= 100.millis
    blockValidation.simpleTxBlock.p50 should be <= 100.millis
    blockValidation.fullBlock.p50 should be <= 100.millis
  }

  it should "be defined for transaction execution" in {
    val txExecution = KPIBaselines.PerformanceBenchmarks.TransactionExecution
    
    txExecution.simpleTransferTarget shouldBe 1.millis
    txExecution.contractCallTarget shouldBe 10.millis
    txExecution.regressionThreshold shouldBe 1.2.millis
    
    txExecution.simpleTransfer.p50 should be <= 1.millis
    txExecution.contractCallSimple.p50 should be <= 10.millis
  }

  it should "be defined for state root calculation" in {
    val stateRoot = KPIBaselines.PerformanceBenchmarks.StateRootCalculation
    
    stateRoot.target shouldBe 50.millis
    stateRoot.regressionThreshold shouldBe 60.millis
    
    stateRoot.smallState.p50 should be <= 50.millis
    stateRoot.mediumState.p50 should be <= 50.millis
  }

  it should "be defined for RLP operations" in {
    val rlp = KPIBaselines.PerformanceBenchmarks.RLPOperations
    
    rlp.target shouldBe 100.micros
    rlp.regressionThreshold shouldBe 120.micros
    
    rlp.tinyPayload.p50 should be <= 100.micros
    rlp.smallPayload.p50 should be <= 100.micros
  }

  it should "be defined for crypto operations" in {
    val crypto = KPIBaselines.PerformanceBenchmarks.CryptoOperations
    
    crypto.target shouldBe 1.millis
    crypto.regressionThreshold shouldBe 1.2.millis
    
    crypto.ecdsaSign.p50 should be <= 1.millis
    crypto.ecdsaVerify.p50 should be <= 1.5.millis
  }

  it should "be defined for network operations" in {
    val network = KPIBaselines.PerformanceBenchmarks.NetworkOperations
    
    network.handshakeTarget shouldBe 500.millis
    network.regressionThreshold shouldBe 600.millis
    
    network.peerHandshakeLocal.p50 should be <= 500.millis
  }

  it should "be defined for database operations" in {
    val db = KPIBaselines.PerformanceBenchmarks.DatabaseOperations
    
    db.target shouldBe 1.millis
    db.regressionThreshold shouldBe 1.2.millis
    
    db.singleGet.p50 should be <= 1.millis
    db.singlePut.p50 should be <= 1.millis
  }

  "Memory baselines" should "be defined with valid thresholds" in {
    KPIBaselines.MemoryBaselines.heapTarget shouldBe 2048
    KPIBaselines.MemoryBaselines.regressionThreshold shouldBe 2400
    
    KPIBaselines.MemoryBaselines.nodeStartup.peak should be <= 2048
    KPIBaselines.MemoryBaselines.syncingFast.peak should be <= 2048
    KPIBaselines.MemoryBaselines.syncingFull.peak should be <= 2048
    
    KPIBaselines.MemoryBaselines.gcOverheadTarget shouldBe 5.0
    KPIBaselines.MemoryBaselines.gcOverheadBaseline should be <= 5.0
    KPIBaselines.MemoryBaselines.gcOverheadThreshold shouldBe 6.0
  }

  "Validation helpers" should "correctly check if duration is within target" in {
    val validation = KPIBaselines.Validation
    
    validation.isWithinTarget(4.minutes, 5.minutes) shouldBe true
    validation.isWithinTarget(6.minutes, 5.minutes) shouldBe false
  }

  it should "correctly detect regressions" in {
    val validation = KPIBaselines.Validation
    
    // 10% increase - not a regression (threshold is 20% = 1.2)
    validation.isRegression(110.millis, 100.millis, 1.2) shouldBe false
    
    // 25% increase - regression (exceeds 20% threshold)
    validation.isRegression(125.millis, 100.millis, 1.2) shouldBe true
  }

  it should "correctly calculate percentage difference" in {
    val validation = KPIBaselines.Validation
    
    validation.percentageDifference(110.millis, 100.millis) shouldBe 10.0
    validation.percentageDifference(120.millis, 100.millis) shouldBe 20.0
    validation.percentageDifference(90.millis, 100.millis) shouldBe -10.0
  }

  it should "correctly check percentage within target" in {
    val validation = KPIBaselines.Validation
    
    validation.isPercentageWithinTarget(98.0, 99.0, 5.0) shouldBe true
    validation.isPercentageWithinTarget(95.0, 99.0, 5.0) shouldBe true
    validation.isPercentageWithinTarget(93.0, 99.0, 5.0) shouldBe false
  }

  "Summary" should "generate a formatted summary string" in {
    val summary = KPIBaselines.summary
    
    summary should not be empty
    summary should include("KPI Baselines Summary")
    summary should include(KPIBaselines.baselineDate)
    summary should include("Test Execution Time")
    summary should include("Test Health")
    summary should include("Performance Benchmarks")
    summary should include("Ethereum/Tests Compliance")
    summary should include("Memory Baselines")
  }
}
