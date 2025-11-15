package com.chipprbots.ethereum.ethtest

import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain._
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Converts ethereum/tests JSON format to internal domain objects
  *
  * Handles hex string parsing and mapping to strongly-typed domain objects.
  */
object TestConverter {

  /** Convert ethereum/tests AccountState to internal Account
    *
    * @param address Account address (hex string from test file key)
    * @param state Account state from test
    * @return Internal Account object
    */
  def toAccount(address: String, state: AccountState): Account = {
    val addr = Address(ByteString(parseHex(address)))
    val balance = UInt256(parseBigInt(state.balance))
    val nonce = UInt256(parseBigInt(state.nonce))
    val codeHash = if (state.code.isEmpty || state.code == "0x") {
      Account.EmptyCodeHash
    } else {
      // Code hash will be computed when storing
      ByteString(parseHex(state.code))
    }

    Account(
      nonce = nonce,
      balance = balance,
      storageRoot = Account.EmptyStorageRootHash, // Will be computed from storage
      codeHash = Account.EmptyCodeHash // Placeholder, actual code stored separately
    )
  }

  /** Convert ethereum/tests TestBlockHeader to internal BlockHeader
    *
    * @param testHeader Header from test file
    * @return Internal BlockHeader object
    */
  def toBlockHeader(testHeader: TestBlockHeader): BlockHeader = {
    BlockHeader(
      parentHash = ByteString(parseHex(testHeader.parentHash)),
      ommersHash = ByteString(parseHex(testHeader.uncleHash)),
      beneficiary = ByteString(parseHex(testHeader.coinbase)),
      stateRoot = ByteString(parseHex(testHeader.stateRoot)),
      transactionsRoot = ByteString(parseHex(testHeader.transactionsTrie)),
      receiptsRoot = ByteString(parseHex(testHeader.receiptTrie)),
      logsBloom = ByteString(parseHex(testHeader.bloom)),
      difficulty = parseBigInt(testHeader.difficulty),
      number = parseBigInt(testHeader.number),
      gasLimit = parseBigInt(testHeader.gasLimit),
      gasUsed = parseBigInt(testHeader.gasUsed),
      unixTimestamp = parseBigInt(testHeader.timestamp).toLong,
      extraData = ByteString(parseHex(testHeader.extraData)),
      mixHash = ByteString(parseHex(testHeader.mixHash)),
      nonce = ByteString(parseHex(testHeader.nonce))
    )
  }

  /** Convert ethereum/tests TestTransaction to internal Transaction
    *
    * @param testTx Transaction from test file
    * @return Internal SignedTransaction object
    */
  def toTransaction(testTx: TestTransaction): SignedTransaction = {
    // Parse signature components
    val v = parseBigInt(testTx.v).toByte
    val r = ByteString(parseHex(testTx.r))
    val s = ByteString(parseHex(testTx.s))

    // Parse transaction data
    val nonce = parseBigInt(testTx.nonce)
    val gasPrice = parseBigInt(testTx.gasPrice)
    val gasLimit = parseBigInt(testTx.gasLimit)
    val receivingAddress =
      if (testTx.to.isEmpty || testTx.to == "0x") None
      else Some(Address(ByteString(parseHex(testTx.to))))
    val value = parseBigInt(testTx.value)
    val payload = ByteString(parseHex(testTx.data))

    val tx = LegacyTransaction(
      nonce = nonce,
      gasPrice = gasPrice,
      gasLimit = gasLimit,
      receivingAddress = receivingAddress,
      value = value,
      payload = payload
    )

    SignedTransaction(tx, v, r, s)
  }

  /** Map network name to fork block numbers
    *
    * ethereum/tests use network names like "Byzantium", "Constantinople", etc.
    * We need to map these to our fork block configuration.
    *
    * @param network Network name from test
    * @return BlockchainConfig with appropriate fork configuration
    */
  def networkToConfig(network: String, baseConfig: BlockchainConfig): BlockchainConfig = {
    import com.chipprbots.ethereum.utils.ForkBlockNumbers

    val forks = network.toLowerCase match {
      case "frontier" =>
        ForkBlockNumbers.Empty.copy(frontierBlockNumber = 0)
      case "homestead" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0
        )
      case "eip150" | "tangerinewhistle" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0
        )
      case "eip158" | "spuriousdragon" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0
        )
      case "byzantium" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          byzantiumBlockNumber = 0
        )
      case "constantinople" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0
        )
      case "istanbul" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          istanbulBlockNumber = 0
        )
      case "berlin" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          istanbulBlockNumber = 0,
          berlinBlockNumber = 0
        )
      case _ =>
        // Default to Frontier for unknown networks
        ForkBlockNumbers.Empty.copy(frontierBlockNumber = 0)
    }

    baseConfig.copy(forkBlockNumbers = forks)
  }

  /** Parse hex string to byte array, handling "0x" prefix */
  private def parseHex(hex: String): Array[Byte] = {
    val cleaned = if (hex.startsWith("0x")) hex.substring(2) else hex
    if (cleaned.isEmpty) Array.empty[Byte]
    else Hex.decode(cleaned)
  }

  /** Parse hex or decimal string to BigInt */
  private def parseBigInt(value: String): BigInt = {
    if (value.startsWith("0x")) {
      BigInt(value.substring(2), 16)
    } else {
      BigInt(value)
    }
  }
}
