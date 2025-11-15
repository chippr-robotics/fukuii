package com.chipprbots.ethereum.ethtest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.chipprbots.ethereum.utils.ByteStringUtils.ByteStringOps

/** Simple smoke test to validate ethereum/tests infrastructure
  *
  * Tests basic functionality:
  * - JSON parsing
  * - Test conversion to domain objects  
  * - Initial state setup
  */
class SimpleEthereumTest extends EthereumTestsSpec {

  "EthereumTestsAdapter" should "parse SimpleTx test file" in {
    val testFile = "/ethereum-tests/SimpleTx.json"
    
    info("Loading test file...")
    val suite = loadTestSuite(testFile)
    
    info(s"Loaded ${suite.tests.size} test cases")
    suite.tests.size should be > 0
    
    suite.tests.foreach { case (testName, test) =>
      info(s"Test case: $testName")
      info(s"  Network: ${test.network}")
      info(s"  Pre-state accounts: ${test.pre.size}")
      info(s"  Blocks: ${test.blocks.size}")
      info(s"  Post-state accounts: ${test.postState.size}")
      
      // Validate test structure
      test.pre should not be empty
      test.blocks should not be empty
      test.postState should not be empty
      test.network should not be empty
    }
  }

  it should "set up initial state from pre-state" in {
    val testFile = "/ethereum-tests/SimpleTx.json"
    val suite = loadTestSuite(testFile)
    
    suite.tests.foreach { case (testName, test) =>
      info(s"Setting up initial state for: $testName")
      
      val result = setupTestState(test)
      
      result match {
        case Right(world) =>
          info(s"  Successfully created world state")
          info(s"  State root: ${world.stateRootHash.toHex}")
          
          // Verify accounts were created
          test.pre.foreach { case (addressHex, expectedAccount) =>
            val address = parseAddress(addressHex)
            val account = world.getAccount(address)
            
            account should not be empty
            info(s"  Account $addressHex created successfully")
          }
          
        case Left(error) =>
          fail(s"Failed to set up initial state: $error")
      }
    }
  }
  
  it should "validate test structure for all test cases" in {
    val testFile = "/ethereum-tests/SimpleTx.json"
    val suite = loadTestSuite(testFile)
    
    suite.tests.foreach { case (testName, test) =>
      info(s"Validating structure of: $testName")
      
      // Validate pre-state
      test.pre.foreach { case (address, accountState) =>
        accountState.balance should not be empty
        accountState.nonce should not be empty
        // code and storage can be empty
      }
      
      // Validate blocks
      test.blocks.foreach { block =>
        block.blockHeader should not be null
        block.transactions should not be null
        block.uncleHeaders should not be null
      }
      
      // Validate post-state
      test.postState.foreach { case (address, accountState) =>
        accountState.balance should not be empty
        accountState.nonce should not be empty
      }
      
      info(s"  ✓ Test structure is valid")
    }
  }

  it should "execute blocks and validate post-state" in {
    val testFile = "/ethereum-tests/SimpleTx.json"
    val suite = loadTestSuite(testFile)
    
    suite.tests.foreach { case (testName, test) =>
      info(s"Executing test: $testName")
      
      val result = executeTest(test)
      
      result match {
        case Right(executionResult) =>
          info(s"  ✓ Test executed successfully")
          info(s"  Network: ${executionResult.network}")
          info(s"  Blocks executed: ${executionResult.blocksExecuted}")
          info(s"  Final state root: ${executionResult.finalStateRoot.toHex}")
          
          // Test should have executed the expected number of blocks
          executionResult.blocksExecuted shouldBe test.blocks.size
          
        case Left(error) =>
          fail(s"Failed to execute test: $error")
      }
    }
  }
}
