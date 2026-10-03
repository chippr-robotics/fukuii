package com.chipprbots.ethereum.domain

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import scala.util.Try

import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import org.bouncycastle.crypto.AsymmetricCipherKeyPair

import com.chipprbots.ethereum.crypto
import com.chipprbots.ethereum.crypto.ECDSASignature
import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.mpt.ByteArraySerializable
import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.SignedTransactions.*
import com.chipprbots.ethereum.rlp.RLPImplicitConversions.*
import com.chipprbots.ethereum.rlp.RLPImplicits.given
import com.chipprbots.ethereum.rlp.{encode as rlpEncode, *}
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.utils.ByteUtils
import com.chipprbots.ethereum.utils.NetworkType
import com.chipprbots.ethereum.vm.EvmConfig

object SignedTransaction:

  implicit private val ioRuntime: IORuntime = IORuntime.global

  // txHash size is 32bytes, Address size is 20 bytes, taking into account some overhead key-val pair have
  // around 70bytes then 100k entries have around 7mb. 100k entries is around 300blocks for Ethereum network.
  val maximumSenderCacheSize = 100000

  // Each background thread gets batch of signed tx to calculate senders.
  // Batch size balances scheduling overhead vs core utilization.
  val batchSize = 50

  // Cache available processors count for parallel execution (constant at runtime)
  private val availableProcessors: Int = Runtime.getRuntime.availableProcessors

  private val txSenders: Cache[TxHash, Address] = CacheBuilder
    .newBuilder()
    .maximumSize(maximumSenderCacheSize)
    .recordStats()
    .build()

  val FirstByteOfAddress = 12
  val LastByteOfAddress: Int = FirstByteOfAddress + Address.Length
  val EIP155NegativePointSign = 35
  val EIP155PositivePointSign = 36
  val valueForEmptyR = 0
  val valueForEmptyS = 0

  def apply(
      tx: Transaction,
      pointSign: Byte,
      signatureRandom: ByteString,
      signature: ByteString
  ): SignedTransaction =
    val txSignature = ECDSASignature(
      r = ByteUtils.bytesToBigInt(signatureRandom.toArray),
      s = ByteUtils.bytesToBigInt(signature.toArray),
      // pointSign must be treated as unsigned byte (EIP-155 values can be >= 128)
      v = BigInt(pointSign & 0xff)
    )
    SignedTransaction(tx, txSignature)

  def sign(
      tx: Transaction,
      keyPair: AsymmetricCipherKeyPair,
      chainId: Option[BigInt]
  ): SignedTransaction =
    val bytes = bytesToSign(tx, chainId)
    val sig = ECDSASignature.sign(bytes, keyPair)
    SignedTransaction(tx, getEthereumSignature(tx, sig, chainId))

  private[domain] def bytesToSign(tx: Transaction, chainId: Option[BigInt]): Array[Byte] =
    tx match
      case legacyTransaction: LegacyTransaction => getLegacyBytesToSign(legacyTransaction, chainId)
      case twal: TransactionWithAccessList      => getTWALBytesToSign(twal)
      case twdf: TransactionWithDynamicFee      => getTWDFBytesToSign(twdf)
      case btx: BlobTransaction                 => getBlobTxBytesToSign(btx)
      case sct: SetCodeTransaction              => getSCTBytesToSign(sct)

  private def getLegacyBytesToSign(legacyTransaction: LegacyTransaction, chainIdOpt: Option[BigInt]): Array[Byte] =
    chainIdOpt match
      case Some(id) =>
        chainSpecificTransactionBytes(legacyTransaction, id)
      case None =>
        generalTransactionBytes(legacyTransaction)

  /** Transaction specific piece of code. This should be moved to the Signer architecture once available.
    *
    * Convert a RLP compatible ECDSA Signature to a raw crypto signature. Depending on the transaction type and the
    * block number, different rules are used to enhance the v field with additional context for signing purpose and
    * networking communication.
    *
    * Currently, both semantic data are represented by the same data structure.
    *
    * @see
    *   getEthereumSignature for the reciprocal conversion.
    * @param signedTransaction
    *   the signed transaction from which to extract the raw signature
    * @return
    *   a raw crypto signature, with only 27 or 28 as valid ECDSASignature.v value
    */
  private def getRawSignature(
      signedTransaction: SignedTransaction
  )(implicit blockchainConfig: BlockchainConfig): ECDSASignature =
    signedTransaction.tx match
      case _: LegacyTransaction =>
        val chainIdOpt = extractChainId(signedTransaction)
        getLegacyTransactionRawSignature(signedTransaction.signature, chainIdOpt)
      case _: TransactionWithAccessList =>
        getTWALRawSignature(signedTransaction.signature)
      case _: TransactionWithDynamicFee =>
        // Type-2 uses same y-parity encoding as Type-1
        getTWALRawSignature(signedTransaction.signature)
      case _: BlobTransaction =>
        // Type-3 uses same y-parity encoding as Type-1/Type-2
        getTWALRawSignature(signedTransaction.signature)
      case _: SetCodeTransaction =>
        // Type-4 uses same y-parity encoding as Type-1/Type-2
        getTWALRawSignature(signedTransaction.signature)

  /** Transaction specific piece of code. This should be moved to the Signer architecture once available.
    *
    * Convert a LegacyTransaction RLP compatible ECDSA Signature to a raw crypto signature
    *
    * @param ethereumSignature
    *   the v-modified signature, received from the network
    * @param chainIdOpt
    *   the chainId if available
    * @return
    *   a raw crypto signature, with only 27 or 28 as valid ECDSASignature.v value
    */
  private def getLegacyTransactionRawSignature(
      ethereumSignature: ECDSASignature,
      chainIdOpt: Option[BigInt]
  ): ECDSASignature =
    // Normalize v to handle negative values (e.g., -98 byte -> 158 unsigned)
    val normalizedV = if ethereumSignature.v < 0 then ethereumSignature.v + 256 else ethereumSignature.v

    chainIdOpt match
      // ignore chainId for unprotected negative y-parity in pre-eip155 signature
      case Some(_) if normalizedV == ECDSASignature.negativePointSign =>
        ethereumSignature.copy(v = BigInt(ECDSASignature.negativePointSign))
      // ignore chainId for unprotected positive y-parity in pre-eip155 signature
      case Some(_) if normalizedV == ECDSASignature.positivePointSign =>
        ethereumSignature.copy(v = BigInt(ECDSASignature.positivePointSign))
      // identify negative y-parity for protected post eip-155 signature
      case Some(chainId) if normalizedV == (2 * chainId + EIP155NegativePointSign) =>
        ethereumSignature.copy(v = BigInt(ECDSASignature.negativePointSign))
      // identify positive y-parity for protected post eip-155 signature
      case Some(chainId) if normalizedV == (2 * chainId + EIP155PositivePointSign) =>
        ethereumSignature.copy(v = BigInt(ECDSASignature.positivePointSign))
      // legacy pre-eip
      case None => ethereumSignature
      // unexpected chainId
      case _ =>
        throw new IllegalStateException(
          s"Unexpected pointSign for LegacyTransaction, chainId: ${chainIdOpt
              .getOrElse("None")}, ethereum.signature.v: ${ethereumSignature.v}, normalized.v: $normalizedV"
        )

  /** Transaction specific piece of code. This should be moved to the Signer architecture once available.
    *
    * Convert a TransactionWithAccessList RLP compatible ECDSA Signature to a raw crypto signature
    *
    * @param ethereumSignature
    *   the v-modified signature, received from the network
    * @return
    *   a raw crypto signature, with only 27 or 28 as valid ECDSASignature.v value
    */
  private def getTWALRawSignature(ethereumSignature: ECDSASignature): ECDSASignature =
    ethereumSignature.v match
      case v if v == 0 => ethereumSignature.copy(v = BigInt(ECDSASignature.negativePointSign))
      case v if v == 1 => ethereumSignature.copy(v = BigInt(ECDSASignature.positivePointSign))
      case _ =>
        throw new IllegalStateException(
          s"Unexpected pointSign for TransactionWithAccessList, ethereum.signature.v: ${ethereumSignature.v}"
        )

  /** Transaction specific piece of code. This should be moved to the Signer architecture once available.
    *
    * Convert a raw crypto signature into a RLP compatible ECDSA one. Depending on the transaction type and the block
    * number, different rules are used to enhance the v field with additional context for signing purpose and networking
    * communication.
    *
    * Currently, both semantic data are represented by the same data structure.
    *
    * @see
    *   getRawSignature for the reciprocal conversion.
    * @param tx
    *   the transaction to adapt the raw signature to
    * @param rawSignature
    *   the raw signature generated by the crypto module
    * @param chainIdOpt
    *   the chainId if available
    * @return
    *   a ECDSASignature with v value depending on the transaction type
    */
  private def getEthereumSignature(
      tx: Transaction,
      rawSignature: ECDSASignature,
      chainIdOpt: Option[BigInt]
  ): ECDSASignature =
    tx match
      case _: LegacyTransaction =>
        getLegacyEthereumSignature(rawSignature, chainIdOpt)
      case _: TransactionWithAccessList =>
        getTWALEthereumSignature(rawSignature)
      case _: TransactionWithDynamicFee =>
        // Type-2 uses same y-parity encoding as Type-1
        getTWALEthereumSignature(rawSignature)
      case _: BlobTransaction =>
        // Type-3 uses same y-parity encoding as Type-1/Type-2
        getTWALEthereumSignature(rawSignature)
      case _: SetCodeTransaction =>
        // Type-4 uses same y-parity encoding as Type-1/Type-2
        getTWALEthereumSignature(rawSignature)

  /** Transaction specific piece of code. This should be moved to the Signer architecture once available.
    *
    * Convert a raw crypto signature into a RLP compatible ECDSA one.
    *
    * @param rawSignature
    *   the raw signature generated by the crypto module
    * @param chainIdOpt
    *   the chainId if available
    * @return
    *   a legacy transaction specific ECDSASignature, with v chainId-protected if possible
    */
  private def getLegacyEthereumSignature(rawSignature: ECDSASignature, chainIdOpt: Option[BigInt]): ECDSASignature =
    chainIdOpt match
      case Some(chainId) if rawSignature.v == ECDSASignature.negativePointSign =>
        rawSignature.copy(v = chainId * 2 + EIP155NegativePointSign)
      case Some(chainId) if rawSignature.v == ECDSASignature.positivePointSign =>
        rawSignature.copy(v = chainId * 2 + EIP155PositivePointSign)
      case None => rawSignature
      case _ =>
        throw new IllegalStateException(
          s"Unexpected pointSign. ChainId: ${chainIdOpt.getOrElse("None")}, "
            + s"raw.signature.v: ${rawSignature.v}, "
            + s"authorized values are ${ECDSASignature.allowedPointSigns.mkString(", ")}"
        )

  /** Transaction specific piece of code. This should be moved to the Signer architecture once available.
    *
    * Convert a raw crypto signature into a RLP compatible ECDSA one.
    *
    * @param rawSignature
    *   the raw signature generated by the crypto module
    * @return
    *   a transaction-with-access-list specific ECDSASignature
    */
  private def getTWALEthereumSignature(rawSignature: ECDSASignature): ECDSASignature =
    rawSignature match
      case ECDSASignature(_, _, v) if v == ECDSASignature.positivePointSign =>
        rawSignature.copy(v = BigInt(ECDSASignature.positiveYParity))
      case ECDSASignature(_, _, v) if v == ECDSASignature.negativePointSign =>
        rawSignature.copy(v = BigInt(ECDSASignature.negativeYParity))
      case _ =>
        throw new IllegalStateException(
          s"Unexpected pointSign. raw.signature.v: ${rawSignature.v}, authorized values are ${ECDSASignature.allowedPointSigns
              .mkString(", ")}"
        )

  def getSender(tx: SignedTransaction)(implicit blockchainConfig: BlockchainConfig): Option[Address] =
    Option(txSenders.getIfPresent(tx.hash)).orElse {
      val result = calculateSender(tx)
      result.foreach(address => txSenders.put(tx.hash, address))
      result
    }

  private def calculateSender(tx: SignedTransaction)(implicit blockchainConfig: BlockchainConfig): Option[Address] =
    Try {
      val bytesToSign: Array[Byte] = getBytesToSign(tx)
      val recoveredPublicKey: Option[Array[Byte]] = getRawSignature(tx).publicKey(bytesToSign)

      for
        key <- recoveredPublicKey
        addrBytes = crypto.kec256(key).slice(FirstByteOfAddress, LastByteOfAddress)
        if addrBytes.length == Address.Length
      yield Address(addrBytes)
    }.toOption.flatten

  def retrieveSendersInBackGround(blocks: Seq[BlockBody])(implicit blockchainConfig: BlockchainConfig): Unit =
    val blocktx = blocks
      .collect {
        case block if block.transactionList.nonEmpty => block.transactionList
      }
      .flatten
      .grouped(batchSize)

    IO.parTraverseN(availableProcessors)(blocktx.toSeq)(calculateSendersForTxs).void.unsafeRunAndForget()(ioRuntime)

  private def calculateSendersForTxs(txs: Seq[SignedTransaction])(implicit
      blockchainConfig: BlockchainConfig
  ): IO[Unit] =
    IO(txs.foreach(calculateAndCacheSender))

  private def calculateAndCacheSender(stx: SignedTransaction)(implicit blockchainConfig: BlockchainConfig) =
    calculateSender(stx).foreach(address => txSenders.put(stx.hash, address))

  /** Transaction specific piece of code. This should be moved to the Signer architecture once available.
    *
    * Extract pre-eip 155 payload to sign for legacy transaction
    *
    * @param tx
    * @return
    *   the transaction payload for Legacy transaction
    */
  private def generalTransactionBytes(tx: Transaction): Array[Byte] =
    val receivingAddressAsArray: Array[Byte] = tx.receivingAddress.map(_.toArray).getOrElse(Array.empty[Byte])
    crypto.kec256(
      rlpEncode(
        RLPList(
          toEncodeable(tx.nonce),
          toEncodeable(tx.gasPrice),
          toEncodeable(tx.gasLimit),
          toEncodeable(receivingAddressAsArray),
          toEncodeable(tx.value),
          toEncodeable(tx.payload)
        )
      )
    )

  /** Transaction specific piece of code. This should be moved to the Signer architecture once available.
    *
    * Extract post-eip 155 payload to sign for legacy transaction
    *
    * @param tx
    * @param chainId
    * @return
    *   the transaction payload for Legacy transaction
    */
  private def chainSpecificTransactionBytes(tx: Transaction, chainId: BigInt): Array[Byte] =
    val receivingAddressAsArray: Array[Byte] = tx.receivingAddress.map(_.toArray).getOrElse(Array.empty[Byte])
    crypto.kec256(
      rlpEncode(
        RLPList(
          toEncodeable(tx.nonce),
          toEncodeable(tx.gasPrice),
          toEncodeable(tx.gasLimit),
          toEncodeable(receivingAddressAsArray),
          toEncodeable(tx.value),
          toEncodeable(tx.payload),
          toEncodeable(chainId),
          toEncodeable(valueForEmptyR),
          toEncodeable(valueForEmptyS)
        )
      )
    )

  /** Transaction specific piece of code. This should be moved to the Signer architecture once available.
    *
    * @param stx
    *   the signed transaction to get the chainId from
    * @return
    *   Some(chainId) if available, None if not (unprotected signed transaction)
    */
  private def extractChainId(stx: SignedTransaction)(implicit blockchainConfig: BlockchainConfig): Option[BigInt] =
    val chainIdOpt: Option[BigInt] = stx.tx match
      case _: LegacyTransaction
          if stx.signature.v == ECDSASignature.negativePointSign || stx.signature.v == ECDSASignature.positivePointSign =>
        None
      case _: LegacyTransaction =>
        // EIP-155: Extract chainId from v value
        // v = chainId * 2 + 35 (for negative y-parity) or chainId * 2 + 36 (for positive y-parity)
        // Handle negative v values by converting to unsigned (e.g., -98 byte -> 158 unsigned)
        val normalizedV = if stx.signature.v < 0 then stx.signature.v + 256 else stx.signature.v

        // Only extract chainId if v is >= 35 (valid EIP-155 range)
        // Values < 35 that aren't 27 or 28 are invalid
        if normalizedV >= EIP155NegativePointSign then
          val chainId = (normalizedV - EIP155NegativePointSign) / 2
          // Validate that extracted chainId matches the blockchain's configured chainId
          // This ensures EIP-155 replay protection works correctly
          if chainId == blockchainConfig.chainId.value then Some(chainId)
          else
            // ChainId present but does not match local config - reject for replay protection
            None
        else
          // Invalid v value (not 27, 28, or >= 35)
          None
      case twal: TransactionWithAccessList => Some(twal.chainId)
      case twdf: TransactionWithDynamicFee => Some(twdf.chainId)
      case btx: BlobTransaction            => Some(btx.chainId)
      case sct: SetCodeTransaction         => Some(sct.chainId)
    chainIdOpt

  /** Transaction specific piece of code. This should be moved to the Signer architecture once available.
    *
    * @param signedTransaction
    *   the signed transaction from which to extract the payload to sign
    * @return
    *   the payload to sign
    */
  private def getBytesToSign(
      signedTransaction: SignedTransaction
  )(implicit blockchainConfig: BlockchainConfig): Array[Byte] =
    signedTransaction.tx match
      case _: LegacyTransaction            => getLegacyBytesToSign(signedTransaction)
      case twal: TransactionWithAccessList => getTWALBytesToSign(twal)
      case twdf: TransactionWithDynamicFee => getTWDFBytesToSign(twdf)
      case btx: BlobTransaction            => getBlobTxBytesToSign(btx)
      case sct: SetCodeTransaction         => getSCTBytesToSign(sct)

  /** Transaction specific piece of code. This should be moved to the Signer architecture once available.
    *
    * Extract pre-eip / post-eip 155 payload to sign for legacy transaction
    *
    * @param signedTransaction
    * @return
    *   the transaction payload for Legacy transaction
    */
  private def getLegacyBytesToSign(
      signedTransaction: SignedTransaction
  )(implicit blockchainConfig: BlockchainConfig): Array[Byte] =
    val chainIdOpt = extractChainId(signedTransaction)
    chainIdOpt match
      case None          => generalTransactionBytes(signedTransaction.tx)
      case Some(chainId) => chainSpecificTransactionBytes(signedTransaction.tx, chainId)

  /** Transaction specific piece of code. This should be moved to the Signer architecture once available.
    *
    * Extract payload to sign for Transaction with access list
    *
    * @param tx
    * @return
    *   the transaction payload to sign for Transaction with access list
    */
  private def getTWALBytesToSign(tx: TransactionWithAccessList): Array[Byte] =
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.accessListItemCodec
    val receivingAddressAsArray: Array[Byte] = tx.receivingAddress.map(_.toArray).getOrElse(Array.empty[Byte])
    crypto.kec256(
      rlpEncode(
        PrefixedRLPEncodable(
          0x01,
          RLPList(
            tx.chainId,
            tx.nonce,
            tx.gasPrice,
            tx.gasLimit,
            receivingAddressAsArray,
            tx.value,
            RLPValue(tx.payload.toArray[Byte]),
            tx.accessList
          )
        )
      )
    )

  private def getTWDFBytesToSign(tx: TransactionWithDynamicFee): Array[Byte] =
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.accessListItemCodec
    val receivingAddressAsArray: Array[Byte] = tx.receivingAddress.map(_.toArray).getOrElse(Array.empty[Byte])
    crypto.kec256(
      rlpEncode(
        PrefixedRLPEncodable(
          0x02,
          RLPList(
            tx.chainId,
            tx.nonce,
            tx.maxPriorityFeePerGas,
            tx.maxFeePerGas,
            tx.gasLimit,
            receivingAddressAsArray,
            tx.value,
            RLPValue(tx.payload.toArray[Byte]),
            tx.accessList
          )
        )
      )
    )

  private def getBlobTxBytesToSign(tx: BlobTransaction): Array[Byte] =
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.accessListItemCodec
    val receivingAddressAsArray: Array[Byte] = tx.receivingAddress.map(_.toArray).getOrElse(Array.empty[Byte])
    crypto.kec256(
      rlpEncode(
        PrefixedRLPEncodable(
          0x03,
          RLPList(
            tx.chainId,
            tx.nonce,
            tx.maxPriorityFeePerGas,
            tx.maxFeePerGas,
            tx.gasLimit,
            receivingAddressAsArray,
            tx.value,
            RLPValue(tx.payload.toArray[Byte]),
            tx.accessList,
            tx.maxFeePerBlobGas,
            RLPList(tx.blobVersionedHashes.map(h => RLPValue(h.value.toArray))*)
          )
        )
      )
    )

  private def getSCTBytesToSign(tx: SetCodeTransaction): Array[Byte] =
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.accessListItemCodec
    import com.chipprbots.ethereum.network.p2p.messages.ETHPackets.setCodeAuthorizationCodec
    val receivingAddressAsArray: Array[Byte] = tx.receivingAddress.map(_.toArray).getOrElse(Array.empty[Byte])
    crypto.kec256(
      rlpEncode(
        PrefixedRLPEncodable(
          0x04,
          RLPList(
            tx.chainId,
            tx.nonce,
            tx.maxPriorityFeePerGas,
            tx.maxFeePerGas,
            tx.gasLimit,
            receivingAddressAsArray,
            tx.value,
            RLPValue(tx.payload.toArray[Byte]),
            tx.accessList,
            tx.authorizationList
          )
        )
      )
    )

  val byteArraySerializable: ByteArraySerializable[SignedTransaction] = new ByteArraySerializable[SignedTransaction]:

    override def fromBytes(bytes: Array[Byte]): SignedTransaction = bytes.toSignedTransaction

    override def toBytes(input: SignedTransaction): Array[Byte] = input.toBytes

case class SignedTransaction(tx: Transaction, signature: ECDSASignature):

  def safeSenderIsEqualTo(address: Address)(implicit blockchainConfig: BlockchainConfig): Boolean =
    SignedTransaction.getSender(this).contains(address)

  override def toString: String =
    s"SignedTransaction { " +
      s"tx: $tx, " +
      s"signature: $signature" +
      s"}"

  def isChainSpecific: Boolean =
    signature.v != ECDSASignature.negativePointSign && signature.v != ECDSASignature.positivePointSign

  lazy val hash: TxHash = TxHash(ByteString(kec256(this.toBytes: Array[Byte])))

case class SignedTransactionWithSender(tx: SignedTransaction, senderAddress: Address)

object SignedTransactionWithSender:

  /** The rules one pass of the stateless filter applies: the EVM config intrinsic gas is priced under, and whether
    * EIP-7623's `tx.gas >= floor` rule applies. `eip7623Floor` is consulted only before Amsterdam; an Amsterdam config
    * carries its own floor rule (see [[coversIntrinsicGas]]).
    */
  final private[domain] case class StatelessRules(config: EvmConfig, eip7623Floor: Boolean)

  /** Pool admission: validates and recovers senders for a batch of signed transactions a peer or a re-org hands the
    * pool. Performs stateless validation (chain ID, nonce cap, intrinsic gas and the calldata floor) before the
    * expensive ECDSA recovery, under the rules of the fork active at the chain head ([[admissionRules]]). Uses parallel
    * ECDSA recovery across all CPU cores for large batches (>= 16 txs).
    *
    * @param headTimestamp
    *   the chain head's timestamp. Read only on ETH-family chains, where it selects the timestamp fork; ETC ignores it.
    */
  def getSignedTransactions(
      stxs: Seq[SignedTransaction],
      headTimestamp: => Timestamp
  )(implicit blockchainConfig: BlockchainConfig): Seq[SignedTransactionWithSender] =
    filterAndRecover(stxs, admissionRules(headTimestamp))

  /** Same validation as [[getSignedTransactions]], but sender recovery runs on the caller's thread. This is used by
    * upstream batch schedulers that already provide parallelism and need deterministic chunk admission order.
    */
  def getSignedTransactionsSequential(
      stxs: Seq[SignedTransaction],
      headTimestamp: => Timestamp
  )(implicit blockchainConfig: BlockchainConfig): Seq[SignedTransactionWithSender] =
    recoverSenders(statelessValid(stxs, admissionRules(headTimestamp)))

  /** The stateless half of [[getSignedTransactions]]: what the pool would admit, before any sender is recovered. */
  def getStatelessValidTransactions(
      stxs: Seq[SignedTransaction],
      headTimestamp: => Timestamp
  )(implicit blockchainConfig: BlockchainConfig): Seq[SignedTransaction] =
    statelessValid(stxs, admissionRules(headTimestamp))

  /** The transactions a STORED block carries, with their senders, for re-executing that block (the debug_trace*,
    * trace_* and debug_intermediateRoots replays).
    *
    * These are not candidates for admission: the block was validated when it was imported, under its own fork. So an
    * Amsterdam block keeps every transaction whose sender recovers, whatever fork the head has since reached. Filtering
    * them with the pool's rules dropped valid transactions from a replay; every transaction after a dropped one then
    * replayed against the wrong state. At Amsterdam that was certain to happen: the pool filter never selected
    * Amsterdam rules before WI-14, so a 12,000-gas self-transfer in an Amsterdam block (valid by EIP-2780) failed its
    * 21,000 intrinsic check and vanished from the trace.
    *
    * A block before Amsterdam, and every ETC block, is filtered exactly as every replay always was, by
    * [[latestConfiguredForkRules]], so pre-Amsterdam and ETC trace output does not move.
    */
  def getSignedTransactionsOfBlock(
      header: BlockHeader,
      stxs: Seq[SignedTransaction]
  )(implicit blockchainConfig: BlockchainConfig): Seq[SignedTransactionWithSender] =
    if blockchainConfig.isAmsterdamTimestamp(header.unixTimestamp) then recoverAll(stxs)
    else filterAndRecover(stxs, latestConfiguredForkRules)

  /** The pool's stateless rules: those of the fork ACTIVE AT THE CHAIN HEAD, as go-ethereum's pool applies them
    * (`legacypool.ValidateTxBasics` → `txpool.ValidateTransaction(tx, pool.currentHead, …)`, which takes its `rules`
    * from `head.Time`, then checks intrinsic gas, the floor from Prague and, at Amsterdam, both against `MaxTxGas`).
    *
    * ETH: the fork the head's timestamp selects, Amsterdam included, with EIP-7623's floor rule from Prague
    * ([[com.chipprbots.ethereum.ledger.BlockPreparator.eip7623Active]], the block validator's own predicate). Before
    * WI-14 the filter used the latest CONFIGURED fork up to Osaka as a proxy for "now". That proxy never named
    * Amsterdam, and where Amsterdam was scheduled it could not tell which floor applied, so it applied none.
    *
    * The block-number coordinate stays `olympiaBlockNumber`, which on an ETH chain is London's block: every
    * block-numbered fork an ETH-family chain has, as before.
    *
    * ETC: unchanged. Olympia's config by construction (built at `olympiaBlockNumber`), without EIP-7623's floor rule:
    * this filter does not read the ETC head, so it cannot tell whether Olympia is active. The head is not read on ETC.
    */
  private[domain] def admissionRules(headTimestamp: => Timestamp)(implicit
      blockchainConfig: BlockchainConfig
  ): StatelessRules =
    val olympiaBlock = blockchainConfig.forkBlockNumbers.olympiaBlockNumber
    if blockchainConfig.networkType == NetworkType.ETH then
      val head = headTimestamp
      StatelessRules(
        EvmConfig.forBlock(olympiaBlock, head, blockchainConfig),
        com.chipprbots.ethereum.ledger.BlockPreparator.eip7623Active(olympiaBlock, head)
      )
    else StatelessRules(EvmConfig.forBlock(olympiaBlock, blockchainConfig), eip7623Floor = false)

  /** The rules every replay of a stored block filtered its transactions with before WI-14, kept verbatim for the
    * replays of pre-Amsterdam and ETC blocks so their output does not move ([[getSignedTransactionsOfBlock]]).
    *
    * ETH: the latest configured fork timestamp up to Osaka, as a proxy for "now"; EIP-7623's floor rule only on a chain
    * with no Amsterdam timestamp. ETC: Olympia's config, no floor rule.
    *
    * Known limitation, deliberately not changed here because it is pre-Amsterdam behaviour: a block's transactions are
    * filtered with rules that are not the block's own. On an ETH chain without Amsterdam (mainnet today) a pre-Prague
    * block's transaction below EIP-7623's floor is dropped from the replay.
    */
  private[domain] def latestConfiguredForkRules(implicit blockchainConfig: BlockchainConfig): StatelessRules =
    if blockchainConfig.networkType == NetworkType.ETH then
      val ft = blockchainConfig.forkTimestamps
      val latestTimestamp: Long =
        ft.osakaTimestamp
          .orElse(ft.bpo2Timestamp)
          .orElse(ft.bpo1Timestamp)
          .orElse(ft.pragueTimestamp)
          .orElse(ft.cancunTimestamp)
          .orElse(ft.shanghaiTimestamp)
          .getOrElse(0L)
      val olympiaBlock = blockchainConfig.forkBlockNumbers.olympiaBlockNumber
      StatelessRules(
        EvmConfig.forBlock(olympiaBlock, Timestamp(latestTimestamp), blockchainConfig),
        com.chipprbots.ethereum.ledger.BlockPreparator.eip7623Active(olympiaBlock, Timestamp(latestTimestamp)) &&
          ft.amsterdamTimestamp.isEmpty
      )
    else
      StatelessRules(EvmConfig.forBlock(blockchainConfig.forkBlockNumbers.olympiaBlockNumber, blockchainConfig), false)

  /** Stateless filter, then sender recovery: sequential for a small batch, across all cores for a large one. */
  private def filterAndRecover(
      stxs: Seq[SignedTransaction],
      rules: StatelessRules
  )(implicit blockchainConfig: BlockchainConfig): Seq[SignedTransactionWithSender] =
    recoverAll(statelessValid(stxs, rules))

  private def recoverAll(
      stxs: Seq[SignedTransaction]
  )(implicit blockchainConfig: BlockchainConfig): Seq[SignedTransactionWithSender] =
    if stxs.size < 16 then
      // Small batch: sequential to avoid overhead
      recoverSenders(stxs)
    else
      // Large batch: parallel ECDSA recovery across all cores
      getSignedTransactionsParallel(stxs)

  private def statelessValid(
      stxs: Seq[SignedTransaction],
      rules: StatelessRules
  )(implicit blockchainConfig: BlockchainConfig): Seq[SignedTransaction] =
    val StatelessRules(config, eip7623Floor) = rules
    val eip2681NonceCap = BigInt(2).pow(64) - 2 // EIP-2681: nonces >= 2^64-1 rejected
    stxs.filter { stx =>
      val tx = stx.tx
      // 1. Chain ID validation for typed transactions (EIP-2930+)
      val chainIdValid = tx match
        case twal: TransactionWithAccessList => twal.chainId == blockchainConfig.chainId.value
        case twdf: TransactionWithDynamicFee => twdf.chainId == blockchainConfig.chainId.value
        case btx: BlobTransaction            => btx.chainId == blockchainConfig.chainId.value
        case sct: SetCodeTransaction         => sct.chainId == blockchainConfig.chainId.value
        case _: LegacyTransaction            => true // validated in getSender
      if !chainIdValid then false
      else if tx.nonce > eip2681NonceCap then false // EIP-2681 nonce overflow
      else
        // 2. Intrinsic gas validation — reject txs with gas below minimum
        val authListSize = tx match
          case sct: SetCodeTransaction => sct.authorizationList.size
          case _                       => 0
        coversIntrinsicGas(config, stx, authListSize, eip7623Floor)
    }

  /** Whether `stx`'s gas limit covers its intrinsic gas under `config` — and, under Amsterdam, the rest of
    * execution-specs `validate_transaction`'s gas rule: `tx.gas >= max(intrinsic, calldata floor)` with both at most
    * TX_MAX_GAS_LIMIT (EIP-7976 / EIP-7981 / EIP-8037), the rule `StdSignedTransactionValidator` applies to blocks.
    * Before Amsterdam, `eip7623Floor` adds EIP-7623's `tx.gas >= 21,000 + 10 * tokens` (see [[admissionRules]] and
    * [[latestConfiguredForkRules]] for where).
    *
    * The sender enters intrinsic gas and the floor only through transactionBaseCost, and only from Amsterdam, where a
    * self-transfer (to == sender) costs less. Recovering the sender is an ECDSA public-key recovery, and the stateless
    * filter runs sequentially on its caller's thread for a whole announcement batch; for 2,000 txs that kept the
    * SignedTransactionsFilterActor busy for seconds. So the check runs first with a sender that is never tx.to. Neither
    * cost is ever below the true one, so passing it is exact. The real sender is recovered only when that check fails
    * under Amsterdam. The fallback is harmless: `recoverSenders` drops txs whose sender cannot be recovered.
    */
  private[domain] def coversIntrinsicGas(
      config: com.chipprbots.ethereum.vm.EvmConfig,
      stx: SignedTransaction,
      authListSize: Int,
      eip7623Floor: Boolean
  )(implicit blockchainConfig: BlockchainConfig): Boolean =
    val tx = stx.tx
    def covers(sender: Address): Boolean =
      val intrinsicGas = config.calcTransactionIntrinsicGas(
        tx.payload,
        tx.isContractInit,
        Transaction.accessList(tx),
        authListSize,
        tx.receivingAddress,
        UInt256(tx.value),
        sender
      )
      if !config.amsterdamEnabled then
        val floor: BigInt =
          if !eip7623Floor then BigInt(0)
          else
            com.chipprbots.ethereum.ledger.BlockPreparator.calcFloorDataGas(
              tx.payload,
              config.transactionBaseCost(tx.receivingAddress, UInt256(tx.value), sender)
            )
        tx.gasLimit.value >= intrinsicGas.max(floor)
      else
        val floor = config.calcAmsterdamCalldataFloorGas(
          tx.payload,
          Transaction.accessList(tx),
          tx.receivingAddress,
          UInt256(tx.value),
          sender
        )
        val required = intrinsicGas.max(floor)
        tx.gasLimit.value >= required && required <= com.chipprbots.ethereum.vm.AmsterdamGas.TxMaxGasLimit
    val notTheRecipient = if tx.receivingAddress.contains(Address(0)) then Address(1) else Address(0)
    covers(notTheRecipient) ||
    (config.amsterdamEnabled && covers(SignedTransaction.getSender(stx).getOrElse(Address(0))))

  private def recoverSenders(
      stxs: Seq[SignedTransaction]
  )(implicit blockchainConfig: BlockchainConfig): Seq[SignedTransactionWithSender] =
    stxs.flatMap { stx =>
      SignedTransaction.getSender(stx).map(addr => SignedTransactionWithSender(stx, addr))
    }

  /** Parallel ECDSA sender recovery using cats-effect IO.parTraverseN. Distributes signature validation across all
    * available CPU cores, using one fiber per batch rather than per transaction to keep scheduler overhead low during
    * devp2p LargeTxRequest-style bursts.
    */
  private def getSignedTransactionsParallel(
      stxs: Seq[SignedTransaction]
  )(implicit blockchainConfig: BlockchainConfig): Seq[SignedTransactionWithSender] =
    val batches = stxs.grouped(SignedTransaction.batchSize).toVector
    val parallelism = math.min(Runtime.getRuntime.availableProcessors, batches.size).max(1)
    IO.parTraverseN(parallelism)(batches) { batch =>
      IO(recoverSenders(batch))
    }.map(_.flatten)
      .unsafeRunSync()(IORuntime.global)

  def apply(transaction: LegacyTransaction, signature: ECDSASignature, sender: Address): SignedTransactionWithSender =
    SignedTransactionWithSender(SignedTransaction(transaction, signature), sender)
