package com.chipprbots.ethereum.ethtest

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import io.circe._
import io.circe.parser._
import scala.io.Source

/** Adapter for running ethereum/tests JSON blockchain tests
  *
  * Implements support for the official Ethereum test suite at https://github.com/ethereum/tests This provides
  * comprehensive EVM validation for blocks < 19.25M (pre-Spiral fork) where Ethereum Classic maintains 100% EVM
  * compatibility with Ethereum.
  *
  * Test Format: JSON files containing blockchain test scenarios with:
  *   - Pre-state: Initial account states
  *   - Blocks: Transactions and expected post-state
  *   - Post-state: Expected state after block execution
  *   - Network: Fork configuration (Frontier, Homestead, Byzantium, etc.)
  *
  * See ADR-014 for rationale and compatibility analysis.
  */
object EthereumTestsAdapter {

  /** Load and parse a JSON blockchain test file
    *
    * @param resourcePath
    *   Path to JSON test file in resources
    * @return
    *   Parsed test suite
    */
  def loadTestSuite(resourcePath: String)(implicit runtime: IORuntime): IO[BlockchainTestSuite] =
    IO {
      val source = Source.fromInputStream(getClass.getResourceAsStream(resourcePath))
      try {
        val jsonString = source.mkString
        parse(jsonString) match {
          case Right(json) =>
            json.as[BlockchainTestSuite] match {
              case Right(suite) => suite
              case Left(error)  => throw new RuntimeException(s"Failed to decode test suite: $error")
            }
          case Left(error) => throw new RuntimeException(s"Failed to parse JSON: $error")
        }
      } finally source.close()
    }
}

/** Container for multiple blockchain test cases
  *
  * ethereum/tests JSON files contain multiple test cases in a single file. Each test case has a name and test data.
  */
case class BlockchainTestSuite(tests: Map[String, BlockchainTest])

object BlockchainTestSuite {
  implicit val decoder: Decoder[BlockchainTestSuite] = Decoder.instance { cursor =>
    cursor.as[Map[String, BlockchainTest]].map(BlockchainTestSuite(_))
  }
}

/** Single blockchain test case
  *
  * Represents one test scenario from ethereum/tests.
  *
  * @param pre
  *   Initial state before block execution
  * @param blocks
  *   Blocks to execute in sequence
  * @param postState
  *   Expected state after all blocks executed
  * @param network
  *   Fork configuration (e.g., "Byzantium", "Constantinople")
  * @param genesisBlockHeader
  *   Genesis block header (optional, for proper parent setup)
  */
case class BlockchainTest(
    pre: Map[String, AccountState],
    blocks: Seq[TestBlock],
    postState: Map[String, AccountState],
    network: String,
    genesisBlockHeader: Option[TestBlockHeader]
)

object BlockchainTest {
  implicit val decoder: Decoder[BlockchainTest] = Decoder.instance { cursor =>
    for {
      pre <- cursor.downField("pre").as[Map[String, AccountState]]
      // Make blocks optional - some VM tests may not have blocks field
      blocks <- cursor.downField("blocks").as[Option[Seq[TestBlock]]].map(_.getOrElse(Seq.empty))
      // Make postState optional - VM tests may have different structure than blockchain tests
      // Some tests may not include post-state validation fields
      postState <- cursor
        .downField("postState")
        .as[Option[Map[String, AccountState]]]
        .map(_.getOrElse(Map.empty))
      network <- cursor.downField("network").as[String]
      genesisBlockHeader <- cursor.downField("genesisBlockHeader").as[Option[TestBlockHeader]]
    } yield BlockchainTest(pre, blocks, postState, network, genesisBlockHeader)
  }
}

/** Account state in ethereum/tests format
  *
  * @param balance
  *   Account balance in wei (hex string)
  * @param code
  *   Contract bytecode (hex string)
  * @param nonce
  *   Transaction nonce (hex string)
  * @param storage
  *   Contract storage (hex key -> hex value)
  */
case class AccountState(
    balance: String,
    code: String,
    nonce: String,
    storage: Map[String, String]
)

object AccountState {
  implicit val decoder: Decoder[AccountState] = Decoder.instance { cursor =>
    for {
      balance <- cursor.downField("balance").as[String]
      code <- cursor.downField("code").as[String]
      nonce <- cursor.downField("nonce").as[String]
      storage <- cursor.downField("storage").as[Map[String, String]]
    } yield AccountState(balance, code, nonce, storage)
  }
}

/** Test block from ethereum/tests
  *
  * @param blockHeader
  *   Block header fields
  * @param transactions
  *   List of transactions in block
  * @param uncleHeaders
  *   Uncle block headers
  */
case class TestBlock(
    blockHeader: TestBlockHeader,
    transactions: Seq[TestTransaction],
    uncleHeaders: Seq[TestBlockHeader]
)

object TestBlock {
  implicit val decoder: Decoder[TestBlock] = Decoder.instance { cursor =>
    for {
      header <- cursor.downField("blockHeader").as[TestBlockHeader]
      txs <- cursor.downField("transactions").as[Seq[TestTransaction]]
      uncles <- cursor.downField("uncleHeaders").as[Seq[TestBlockHeader]]
    } yield TestBlock(header, txs, uncles)
  }
}

/** Block header from ethereum/tests (hex-encoded fields) */
case class TestBlockHeader(
    parentHash: String,
    uncleHash: String,
    coinbase: String,
    stateRoot: String,
    transactionsTrie: String,
    receiptTrie: String,
    bloom: String,
    difficulty: String,
    number: String,
    gasLimit: String,
    gasUsed: String,
    timestamp: String,
    extraData: String,
    mixHash: String,
    nonce: String
)

object TestBlockHeader {
  implicit val decoder: Decoder[TestBlockHeader] = Decoder.instance { cursor =>
    for {
      parentHash <- cursor.downField("parentHash").as[String]
      uncleHash <- cursor.downField("uncleHash").as[String]
      coinbase <- cursor.downField("coinbase").as[String]
      stateRoot <- cursor.downField("stateRoot").as[String]
      transactionsTrie <- cursor.downField("transactionsTrie").as[String]
      receiptTrie <- cursor.downField("receiptTrie").as[String]
      bloom <- cursor.downField("bloom").as[String]
      difficulty <- cursor.downField("difficulty").as[String]
      number <- cursor.downField("number").as[String]
      gasLimit <- cursor.downField("gasLimit").as[String]
      gasUsed <- cursor.downField("gasUsed").as[String]
      timestamp <- cursor.downField("timestamp").as[String]
      extraData <- cursor.downField("extraData").as[String]
      mixHash <- cursor.downField("mixHash").as[String]
      nonce <- cursor.downField("nonce").as[String]
    } yield TestBlockHeader(
      parentHash,
      uncleHash,
      coinbase,
      stateRoot,
      transactionsTrie,
      receiptTrie,
      bloom,
      difficulty,
      number,
      gasLimit,
      gasUsed,
      timestamp,
      extraData,
      mixHash,
      nonce
    )
  }
}

/** Transaction from ethereum/tests (hex-encoded fields) */
case class TestTransaction(
    data: String,
    gasLimit: String,
    gasPrice: String,
    nonce: String,
    to: String,
    value: String,
    v: String,
    r: String,
    s: String,
    txType: Option[String] = None, // "0x01" for EIP-2930, "0x02" for EIP-1559, etc.
    chainId: Option[String] = None, // Chain ID for typed transactions
    accessList: Option[List[TestAccessListItem]] = None // Access list for EIP-2930+
)

/** Access list item from ethereum/tests */
case class TestAccessListItem(
    address: String,
    storageKeys: List[String]
)

object TestAccessListItem {
  implicit val decoder: Decoder[TestAccessListItem] = Decoder.instance { cursor =>
    for {
      address <- cursor.downField("address").as[String]
      storageKeys <- cursor.downField("storageKeys").as[List[String]]
    } yield TestAccessListItem(address, storageKeys)
  }
}

object TestTransaction {
  implicit val decoder: Decoder[TestTransaction] = Decoder.instance { cursor =>
    for {
      data <- cursor.downField("data").as[String]
      gasLimit <- cursor.downField("gasLimit").as[String]
      gasPrice <- cursor.downField("gasPrice").as[String]
      nonce <- cursor.downField("nonce").as[String]
      to <- cursor.downField("to").as[String]
      value <- cursor.downField("value").as[String]
      v <- cursor.downField("v").as[String]
      r <- cursor.downField("r").as[String]
      s <- cursor.downField("s").as[String]
      txType <- cursor.downField("type").as[Option[String]]
      chainId <- cursor.downField("chainId").as[Option[String]]
      accessList <- cursor.downField("accessList").as[Option[List[TestAccessListItem]]]
    } yield TestTransaction(data, gasLimit, gasPrice, nonce, to, value, v, r, s, txType, chainId, accessList)
  }
}
