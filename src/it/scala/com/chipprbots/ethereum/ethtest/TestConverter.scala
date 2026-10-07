package com.chipprbots.ethereum.ethtest

import org.apache.pekko.util.ByteString

import org.bouncycastle.util.encoders.Hex

import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.utils.BlockchainConfig

/** Converts ethereum/tests JSON format to internal domain objects
  *
  * Handles hex string parsing and mapping to strongly-typed domain objects.
  */
object TestConverter:

  /** Convert ethereum/tests AccountState to internal Account
    *
    * @param address
    *   Account address (hex string from test file key)
    * @param state
    *   Account state from test
    * @return
    *   Internal Account object
    */
  def toAccount(address: String, state: AccountState): Account =
    val _ = Address(ByteString(parseHex(address)))
    val balance = UInt256(parseBigInt(state.balance))
    val nonce = UInt256(parseBigInt(state.nonce))
    val _ =
      if state.code.isEmpty || state.code == "0x" then Account.EmptyCodeHash
      else
        // Code hash will be computed when storing
        ByteString(parseHex(state.code))

    Account(
      nonce = nonce,
      balance = balance,
      storageRoot = Account.EmptyStorageRootHash, // Will be computed from storage
      codeHash = Account.EmptyCodeHash // Placeholder, actual code stored separately
    )

  /** Convert ethereum/tests TestBlockHeader to internal BlockHeader
    *
    * @param testHeader
    *   Header from test file
    * @return
    *   Internal BlockHeader object
    */
  def toBlockHeader(testHeader: TestBlockHeader): BlockHeader =
    import com.chipprbots.ethereum.domain.BlockHeader.HeaderExtraFields.*

    // Post-merge header fields. Defaults are applied when a fixture omits a field so
    // the reconstructed header hashes byte-identically to block[0].parentHash.
    val baseFee = testHeader.baseFeePerGas.map(parseBigInt).getOrElse(BigInt(0))
    val withdrawalsRoot =
      testHeader.withdrawalsRoot.map(h => ByteString(parseHex(h))).getOrElse(BlockHeader.EmptyMpt)
    val blobGasUsed = testHeader.blobGasUsed.map(parseBigInt).getOrElse(BigInt(0))
    val excessBlobGas = testHeader.excessBlobGas.map(parseBigInt).getOrElse(BigInt(0))
    val parentBeaconBlockRoot =
      testHeader.parentBeaconBlockRoot.map(h => ByteString(parseHex(h))).getOrElse(ByteString(Array.fill(32)(0.toByte)))
    val requestsHash = testHeader.requestsHash.map(h => ByteString(parseHex(h)))

    // Select the post-merge extraFields variant from the fields actually present in
    // the fixture. Each variant changes the RLP item count and therefore the hash, so
    // matching go-ethereum's per-fork header shape is consensus-critical here.
    val extraFields =
      if requestsHash.isDefined then
        HefPostPrague(baseFee, withdrawalsRoot, blobGasUsed, excessBlobGas, parentBeaconBlockRoot, requestsHash.get)
      else if testHeader.blobGasUsed.isDefined || testHeader.parentBeaconBlockRoot.isDefined then
        HefPostCancun(baseFee, withdrawalsRoot, blobGasUsed, excessBlobGas, parentBeaconBlockRoot)
      else if testHeader.withdrawalsRoot.isDefined then HefPostShanghai(baseFee, withdrawalsRoot)
      else if testHeader.baseFeePerGas.isDefined then HefPostOlympia(baseFee)
      else HefEmpty

    BlockHeader(
      parentHash = BlockHash(ByteString(parseHex(testHeader.parentHash))),
      ommersHash = BlockHash(ByteString(parseHex(testHeader.uncleHash))),
      beneficiary = ByteString(parseHex(testHeader.coinbase)),
      stateRoot = TrieRoot(ByteString(parseHex(testHeader.stateRoot))),
      transactionsRoot = TrieRoot(ByteString(parseHex(testHeader.transactionsTrie))),
      receiptsRoot = TrieRoot(ByteString(parseHex(testHeader.receiptTrie))),
      logsBloom = BloomFilter(ByteString(parseHex(testHeader.bloom))),
      difficulty = Difficulty(parseBigInt(testHeader.difficulty)),
      number = BlockNumber(parseBigInt(testHeader.number)),
      gasLimit = GasAmount(parseBigInt(testHeader.gasLimit)),
      gasUsed = GasAmount(parseBigInt(testHeader.gasUsed)),
      unixTimestamp = Timestamp(parseBigInt(testHeader.timestamp).toLong),
      extraData = ByteString(parseHex(testHeader.extraData)),
      mixHash = BlockHash(ByteString(parseHex(testHeader.mixHash))),
      nonce = ByteString(parseHex(testHeader.nonce)),
      extraFields = extraFields
    )

  /** Convert ethereum/tests TestWithdrawal to internal Withdrawal (EIP-4895).
    *
    * @param testWithdrawal
    *   Withdrawal from test file
    * @return
    *   Internal Withdrawal object (amount is in Gwei, as in the test format)
    */
  def toWithdrawal(testWithdrawal: TestWithdrawal): Withdrawal =
    Withdrawal(
      index = parseBigInt(testWithdrawal.index),
      validatorIndex = parseBigInt(testWithdrawal.validatorIndex),
      address = Address(ByteString(parseHex(testWithdrawal.address))),
      amount = parseBigInt(testWithdrawal.amount)
    )

  /** Convert ethereum/tests TestTransaction to internal Transaction
    *
    * @param testTx
    *   Transaction from test file
    * @return
    *   Internal SignedTransaction object
    */
  def toTransaction(testTx: TestTransaction): SignedTransaction =
    // Parse signature components
    val v = parseBigInt(testTx.v).toByte
    val r = ByteString(parseHex(testTx.r))
    val s = ByteString(parseHex(testTx.s))

    // Parse common transaction data.
    // gasPrice is present for legacy (0x00) and EIP-2930 (0x01) txs; EIP-1559 (0x02)
    // and EIP-4844 (0x03) omit it (they carry maxFeePerGas/maxPriorityFeePerGas).
    // Default to 0 when absent — the dynamic-fee/blob branches never read this value.
    val nonce = parseBigInt(testTx.nonce)
    val gasPrice = testTx.gasPrice.map(parseBigInt).getOrElse(BigInt(0))
    val gasLimit = GasAmount(parseBigInt(testTx.gasLimit))
    val receivingAddress =
      if testTx.to.isEmpty || testTx.to == "0x" then None
      else Some(Address(ByteString(parseHex(testTx.to))))
    val value = parseBigInt(testTx.value)
    val payload = ByteString(parseHex(testTx.data))

    // Parse access list (shared by Type 1, 2, 3, 4)
    def parseAccessList: List[AccessListItem] =
      testTx.accessList.getOrElse(List.empty).map { item =>
        AccessListItem(
          address = Address(ByteString(parseHex(item.address))),
          storageKeys = item.storageKeys.map(key => StorageKey(parseBigInt(key)))
        )
      }

    // Determine transaction type and create appropriate transaction object
    val tx: Transaction = testTx.txType match
      case Some("0x01") | Some("0x1") =>
        // EIP-2930: Transaction with access list
        val chainId = testTx.chainId.map(parseBigInt).getOrElse(BigInt(1))
        TransactionWithAccessList(
          chainId = chainId,
          nonce = nonce,
          gasPrice = GasPrice(gasPrice),
          gasLimit = gasLimit,
          receivingAddress = receivingAddress,
          value = value,
          payload = payload,
          accessList = parseAccessList
        )
      case Some("0x02") | Some("0x2") =>
        // EIP-1559: Dynamic fee transaction
        val chainId = testTx.chainId.map(parseBigInt).getOrElse(BigInt(1))
        val maxPriorityFeePerGas = testTx.maxPriorityFeePerGas.map(parseBigInt).getOrElse(gasPrice)
        val maxFeePerGas = testTx.maxFeePerGas.map(parseBigInt).getOrElse(gasPrice)
        TransactionWithDynamicFee(
          chainId = chainId,
          nonce = nonce,
          maxPriorityFeePerGas = maxPriorityFeePerGas,
          maxFeePerGas = maxFeePerGas,
          gasLimit = gasLimit,
          receivingAddress = receivingAddress,
          value = value,
          payload = payload,
          accessList = parseAccessList
        )
      case Some("0x03") | Some("0x3") =>
        // EIP-4844: Blob transaction
        val chainId = testTx.chainId.map(parseBigInt).getOrElse(BigInt(1))
        val maxPriorityFeePerGas = testTx.maxPriorityFeePerGas.map(parseBigInt).getOrElse(gasPrice)
        val maxFeePerGas = testTx.maxFeePerGas.map(parseBigInt).getOrElse(gasPrice)
        val maxFeePerBlobGas = testTx.maxFeePerBlobGas.map(parseBigInt).getOrElse(BigInt(0))
        val blobVersionedHashes =
          testTx.blobVersionedHashes.getOrElse(List.empty).map(h => BlobVersionedHash(ByteString(parseHex(h))))
        BlobTransaction(
          chainId = chainId,
          nonce = nonce,
          maxPriorityFeePerGas = maxPriorityFeePerGas,
          maxFeePerGas = maxFeePerGas,
          gasLimit = gasLimit,
          receivingAddress = receivingAddress,
          value = value,
          payload = payload,
          accessList = parseAccessList,
          maxFeePerBlobGas = maxFeePerBlobGas,
          blobVersionedHashes = blobVersionedHashes
        )
      case _ =>
        // Legacy transaction (or unknown type, default to legacy)
        LegacyTransaction(
          nonce = nonce,
          gasPrice = GasPrice(gasPrice),
          gasLimit = gasLimit,
          receivingAddress = receivingAddress,
          value = value,
          payload = payload
        )

    SignedTransaction(tx, v, r, s)

  /** Map network name to fork block numbers
    *
    * ethereum/tests use network names like "Byzantium", "Constantinople", etc. We need to map these to our fork block
    * configuration.
    *
    * @param network
    *   Network name from test
    * @return
    *   BlockchainConfig with appropriate fork configuration
    */
  def networkToConfig(network: String, baseConfig: BlockchainConfig): BlockchainConfig =
    import com.chipprbots.ethereum.utils.ForkBlockNumbers

    // EIP-155/160/161 activate together at Spurious Dragon (hive/fukuii/fukuii.sh sets all three at
    // HIVE_FORK_SPURIOUS), so every network from EIP158 on carries eip161BlockNumber = 0.

    val forks = network.toLowerCase match
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
          eip155BlockNumber = 0,
          eip161BlockNumber = 0
        )
      case "byzantium" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0
        )
      case "constantinople" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0
        )
      case "constantinoplefix" | "petersburg" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0
        )
      case "istanbul" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0
        )
      case "berlin" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          berlinBlockNumber = 0
        )
      case "london" | "arrowglacier" | "grayglacier" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          berlinBlockNumber = 0,
          olympiaBlockNumber = 0 // London = EIP-1559, mapped to Olympia in Fukuii
        )
      case "merge" | "paris" | "themerge" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          berlinBlockNumber = 0,
          olympiaBlockNumber = 0
        )
      case "shanghai" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          berlinBlockNumber = 0,
          olympiaBlockNumber = 0
        )
      case "cancun" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          berlinBlockNumber = 0,
          olympiaBlockNumber = 0
        )
      case "prague" =>
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          berlinBlockNumber = 0,
          olympiaBlockNumber = 0
        )
      case "osaka" =>
        // Osaka (Sepolia active) — post-Prague, timestamp-gated. Same fork-block
        // layout as Prague; the Osaka activation is applied via forkTimestamps below.
        ForkBlockNumbers.Empty.copy(
          frontierBlockNumber = 0,
          homesteadBlockNumber = 0,
          eip150BlockNumber = 0,
          eip160BlockNumber = 0,
          eip155BlockNumber = 0,
          eip161BlockNumber = 0,
          byzantiumBlockNumber = 0,
          constantinopleBlockNumber = 0,
          petersburgBlockNumber = 0,
          istanbulBlockNumber = 0,
          berlinBlockNumber = 0,
          olympiaBlockNumber = 0
        )
      case _ =>
        // Default to Frontier for unknown networks
        ForkBlockNumbers.Empty.copy(frontierBlockNumber = 0)

    // These vectors target the ETH execution path (chainId=1, timestamp fork dispatch).
    // The base config defaults to networkType=ETC; force ETH so the ETC-Olympia
    // block-number guards (e.g. EIP-2935 history contract, EIP-7623 calldata floor)
    // do NOT fire pre-Prague just because olympiaBlockNumber is mapped to 0 here.
    // Without this, the EIP-2935 ETC path writes HistoryStorage slots at Cancun and
    // throws "Account not found" when persisting that contract's storage.
    val configWithForks = baseConfig.copy(
      forkBlockNumbers = forks,
      networkType = com.chipprbots.ethereum.utils.NetworkType.ETH
    )
    network.toLowerCase match
      case "shanghai" =>
        configWithForks.copy(
          forkTimestamps = com.chipprbots.ethereum.utils.ForkTimestamps(shanghaiTimestamp = Some(0L))
        )
      case "cancun" =>
        configWithForks.copy(
          forkTimestamps = com.chipprbots.ethereum.utils.ForkTimestamps(
            shanghaiTimestamp = Some(0L),
            cancunTimestamp = Some(0L)
          )
        )
      case "prague" =>
        configWithForks.copy(
          forkTimestamps = com.chipprbots.ethereum.utils.ForkTimestamps(
            shanghaiTimestamp = Some(0L),
            cancunTimestamp = Some(0L),
            pragueTimestamp = Some(0L)
          )
        )
      case "osaka" =>
        configWithForks.copy(
          forkTimestamps = com.chipprbots.ethereum.utils.ForkTimestamps(
            shanghaiTimestamp = Some(0L),
            cancunTimestamp = Some(0L),
            pragueTimestamp = Some(0L),
            osakaTimestamp = Some(0L)
          )
        )
      case _ => configWithForks

  /** Parse hex string to byte array, handling "0x" prefix */
  private def parseHex(hex: String): Array[Byte] =
    val cleaned = if hex.startsWith("0x") then hex.substring(2) else hex
    if cleaned.isEmpty then Array.empty[Byte]
    else Hex.decode(cleaned)

  /** Parse hex or decimal string to BigInt */
  private def parseBigInt(value: String): BigInt =
    if value.startsWith("0x") then BigInt(value.substring(2), 16)
    else BigInt(value)
