package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO
import cats.implicits.*

import com.chipprbots.ethereum.consensus.blocks.BlockGenerator
import com.chipprbots.ethereum.db.storage.MptStorage
import com.chipprbots.ethereum.domain.Account
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.BlockHash
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Blockchain
import com.chipprbots.ethereum.domain.BlockchainReader
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.domain.branch.BestBranch
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofRequest
import com.chipprbots.ethereum.jsonrpc.ProofService.GetProofResponse
import com.chipprbots.ethereum.jsonrpc.ProofService.ProofAccount
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProof
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProof.asRlpSerializedNode
import com.chipprbots.ethereum.jsonrpc.ProofService.StorageProofKey
import com.chipprbots.ethereum.mpt.MptNode
import com.chipprbots.ethereum.mpt.MptTraversals

object ProofService:

  /** Request to eth get proof
    *
    * @param address
    *   the address of the account or contract
    * @param storageKeys
    *   array of storage keys; a storage key is indexed from the solidity compiler by the order it is declared. For
    *   mappings it uses the keccak of the mapping key with its position (and recursively for X-dimensional mappings).
    *   See eth_getStorageAt
    * @param blockNumber
    *   block number (integer block number or string "latest", "earliest", ...)
    */
  case class GetProofRequest(address: Address, storageKeys: Seq[StorageProofKey], blockNumber: BlockParam)

  case class GetProofResponse(proofAccount: ProofAccount)

  sealed trait StorageProof:
    def key: StorageProofKey
    def value: BigInt
    def proof: Seq[ByteString]

  object StorageProof:
    def apply(position: BigInt, value: Option[BigInt], proof: Option[Vector[MptNode]]): StorageProof =
      (value, proof) match
        case (Some(value), Some(proof)) =>
          StorageValueProof(StorageProofKey(position), value, proof.map(asRlpSerializedNode))
        case (None, Some(proof)) =>
          EmptyStorageValue(StorageProofKey(position), proof.map(asRlpSerializedNode))
        case (Some(value), None) => EmptyStorageProof(StorageProofKey(position), value)
        case (None, None)        => EmptyStorageValueProof(StorageProofKey(position))

    /** `sp` for the key the request named: the proof is of the slot, the response echoes the request's own key. */
    def withKey(sp: StorageProof, key: StorageProofKey): StorageProof = sp match
      case p: StorageValueProof      => p.copy(key = key)
      case p: EmptyStorageValue      => p.copy(key = key)
      case p: EmptyStorageProof      => p.copy(key = key)
      case p: EmptyStorageValueProof => p.copy(key = key)

    def asRlpSerializedNode(node: MptNode): ByteString =
      // Use cached RLP encoding if available (preserves original storage bytes)
      node.cachedRlpEncoded.map(ByteString(_)).getOrElse(ByteString(MptTraversals.encodeNode(node)))

  /** Object proving a relationship of a storage value to an account's storageHash
    *
    * @param key
    *   storage proof key
    * @param value
    *   the value of the storage slot in its account tree
    * @param proof
    *   the set of node values needed to traverse a patricia merkle tree (from root to leaf) to retrieve a value
    */
  case class EmptyStorageValueProof(key: StorageProofKey) extends StorageProof:
    val value: BigInt = BigInt(0)
    val proof: Seq[ByteString] = Seq.empty[MptNode].map(asRlpSerializedNode)
  case class EmptyStorageValue(key: StorageProofKey, proof: Seq[ByteString]) extends StorageProof:
    val value: BigInt = BigInt(0)
  case class EmptyStorageProof(key: StorageProofKey, value: BigInt) extends StorageProof:
    val proof: Seq[ByteString] = Seq.empty[MptNode].map(asRlpSerializedNode)
  case class StorageValueProof(key: StorageProofKey, value: BigInt, proof: Seq[ByteString]) extends StorageProof

  /** The key used to get the storage slot in its account tree.
    *
    * @param v
    *   the slot
    * @param fixed32
    *   the request named the slot with 63 or 64 hex digits, and the response gives it back as 32 bytes; any other key
    *   is returned as a minimal quantity (execution-apis `eth_getProof`, geth)
    */
  case class StorageProofKey(v: BigInt, fixed32: Boolean = false)

  /** The merkle proofs of the specified account connecting them to the blockhash of the block specified.
    *
    * Proof of account consists of:
    *   - account object: nonce, balance, storageHash, codeHash
    *   - Markle Proof for the account starting with stateRoot from specified block
    *   - Markle Proof for each requested storage entry starting with a storage Hash from the account
    *
    * @param address
    *   the address of the account or contract of the request
    * @param accountProof
    *   Markle Proof for the account starting with stateRoot from specified block
    * @param balance
    *   the Ether balance of the account or contract of the request
    * @param codeHash
    *   the code hash of the contract of the request (keccak(NULL) if external account)
    * @param nonce
    *   the transaction count of the account or contract of the request
    * @param storageHash
    *   the storage hash of the contract of the request (keccak(rlp(NULL)) if external account)
    * @param storageProof
    *   current block header PoW hash
    */
  case class ProofAccount(
      address: Address,
      accountProof: Seq[ByteString],
      balance: BigInt,
      codeHash: ByteString,
      nonce: UInt256,
      storageHash: ByteString,
      storageProof: Seq[StorageProof]
  )

  object ProofAccount:

    def apply(
        account: Account,
        accountProof: Seq[ByteString],
        storageProof: Seq[StorageProof],
        address: Address
    ): ProofAccount =
      ProofAccount(
        address = address,
        accountProof = accountProof,
        balance = account.balance,
        codeHash = account.codeHash.value,
        nonce = account.nonce,
        storageHash = account.storageRoot.value,
        storageProof = storageProof
      )

  sealed trait MptProofError
  object MptProofError:
    case object UnableRebuildMpt extends MptProofError
    case object KeyNotFoundInRebuidMpt extends MptProofError

trait ProofService:

  /** Returns the account- and storage-values of the specified account including the Merkle-proof.
    */
  def getProof(req: GetProofRequest): ServiceResponse[GetProofResponse]

/** Spec: [EIP-1186](https://eips.ethereum.org/EIPS/eip-1186) besu:
  * https://github.com/PegaSysEng/pantheon/pull/1824/files parity:
  * https://github.com/openethereum/parity-ethereum/pull/9001 geth: https://github.com/ethereum/go-ethereum/pull/17737
  */
class EthProofService(
    blockchain: Blockchain,
    blockchainReader: BlockchainReader,
    blockGenerator: BlockGenerator,
    ethCompatibleStorage: Boolean
) extends ProofService:

  def getProof(req: GetProofRequest): ServiceResponse[GetProofResponse] =
    getProofAccount(req.address, req.storageKeys, req.blockNumber)
      .map(_.map(GetProofResponse.apply))

  /** Get account and storage values for account including Merkle Proof.
    *
    * @param address
    *   address of the account
    * @param storageKeys
    *   storage keys which should be proofed and included
    * @param block
    *   block number or string "latest", "earliest"
    * @return
    */
  def getProofAccount(
      address: Address,
      storageKeys: Seq[StorageProofKey],
      block: BlockParam
  ): IO[Either[JsonRpcError, ProofAccount]] = IO {
    resolveHeader(block).map(_.header).flatMap { header =>
      val blockNumber = header.number.value
      // The account, its proof and its storage proofs come from one state: a proof is of one block.
      StateRead
        .at(blockchainReader, header) { state =>
          for accountProof <- Either.fromOption(
              blockchainReader.accountProofIn(state, header, address).map(_.map(asRlpSerializedNode)),
              noAccountProof(address, blockNumber)
            )
          yield blockchainReader.accountIn(state, header, address) match
            case Some(account) =>
              ProofAccount(account, accountProof, getStorageProof(state, address, account, storageKeys), address)
            // geth: an account that does not exist is proved absent by the account proof; its hashes are zero and each
            // storage entry is 0 with an empty proof
            case None =>
              ProofAccount(
                address = address,
                accountProof = accountProof,
                balance = BigInt(0),
                codeHash = ZeroHash,
                nonce = UInt256(0),
                storageHash = ZeroHash,
                storageProof = storageKeys.toList.map(ProofService.EmptyStorageValueProof(_))
              )
        }
        .flatten
    }
  }

  /** The proofs of `storageKeys` in the storage of `address`, whose account in `state` is `account`. */
  def getStorageProof(
      state: MptStorage,
      address: Address,
      account: Account,
      storageKeys: Seq[StorageProofKey]
  ): Seq[StorageProof] =
    storageKeys.toList
      .map { storageKey =>
        StorageProof.withKey(
          blockchain.getStorageProofAt(
            state = state,
            address = address,
            storageRoot = account.storageRoot.value,
            position = storageKey.v,
            ethCompatibleStorage = ethCompatibleStorage
          ),
          storageKey
        )
      }

  private val ZeroHash: ByteString = ByteString(new Array[Byte](32))

  private def noAccountProof(address: Address, blockNumber: BigInt): JsonRpcError =
    JsonRpcError.LogicError(s"No account proof for Address [${address.toString}] blockNumber [${blockNumber.toString}]")

  private def asRlpSerializedNode(node: MptNode): ByteString =
    ByteString(MptTraversals.encodeNode(node))

  /** The header the proof is of, by header and number index alone (geth `StateAndHeaderByNumberOrHash`): a block whose
    * body was never stored (checkpoint import, SNAP pivot) still has a state to prove against. "pending" is as it was:
    * the generator's last build when the state is not held in place, else the head.
    */
  private def resolveHeader(blockParam: BlockParam): Either[JsonRpcError, ResolvedHeader] =
    def getHeader(number: BigInt): Either[JsonRpcError, BlockHeader] =
      val header = blockchainReader.getBestBranch match
        case BestBranch(_, tip) if tip >= number && number >= 0 => blockchainReader.getBlockHeaderByNumber(number)
        case _                                                  => None
      header.toRight(JsonRpcError.InvalidParams(s"Block $number not found"))

    def getLatestHeader(): Either[JsonRpcError, BlockHeader] =
      blockchainReader.getBestBlockHeader
        .toRight(JsonRpcError.InvalidParams("Latest block not found"))

    blockParam match
      case BlockParam.WithNumber(blockNumber) => getHeader(blockNumber).map(ResolvedHeader(_, pendingState = None))
      case BlockParam.WithHash(hash) =>
        blockchainReader
          .getBlockHeaderByHash(BlockHash(hash))
          .toRight(JsonRpcError.InvalidParams("Block not found for hash"))
          .map(ResolvedHeader(_, pendingState = None))
      case BlockParam.Earliest  => getHeader(0).map(ResolvedHeader(_, pendingState = None))
      case BlockParam.Latest    => getLatestHeader().map(ResolvedHeader(_, pendingState = None))
      case BlockParam.Safe      => getLatestHeader().map(ResolvedHeader(_, pendingState = None))
      case BlockParam.Finalized => getLatestHeader().map(ResolvedHeader(_, pendingState = None))
      case BlockParam.Pending   =>
        // On the unified state layout the pending block's state is held nowhere: the block was built on a layer that
        // was dropped, and only the state of the head is on disk. So "pending" is the head there, which is what it
        // is on any node with no pending block. With the guard off it is the block the generator last built.
        Option
          .unless(blockchainReader.holdsStateInPlace)(blockGenerator.getPendingBlockAndState)
          .flatten
          .map(pb => ResolvedHeader(pb.pendingBlock.block.header, pendingState = Some(pb.worldState)))
          .map(Right.apply)
          .getOrElse(resolveHeader(BlockParam.Latest)) // Default behavior in other clients
