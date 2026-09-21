package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import cats.effect.IO

import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.db.storage.EvmCodeStorage
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.ledger.InMemoryWorldStateProxy
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.nodebuilder.BlockchainConfigBuilder

object EthUserService:
  case class GetStorageAtRequest(address: Address, position: BigInt, block: BlockParam)
  case class GetStorageAtResponse(value: ByteString)
  case class GetCodeRequest(address: Address, block: BlockParam)
  case class GetCodeResponse(result: ByteString)
  case class GetBalanceRequest(address: Address, block: BlockParam)
  case class GetBalanceResponse(value: BigInt)
  case class GetTransactionCountRequest(address: Address, block: BlockParam)
  case class GetTransactionCountResponse(value: BigInt)
  case class GetStorageRootRequest(address: Address, block: BlockParam)
  case class GetStorageRootResponse(storageRoot: ByteString)

  /** A single address's storage-slot batch, as decoded from an `eth_getStorageValues` request.
    *
    * @param rawAddress
    *   the address exactly as spelled by the caller — preserved verbatim as the response map key (fixtures use
    *   lowercase keys and expect them echoed back unchanged, not checksummed).
    */
  case class StorageValuesEntry(rawAddress: String, address: Address, slots: Seq[BigInt])
  case class GetStorageValuesRequest(entries: Seq[StorageValuesEntry], block: BlockParam)
  case class GetStorageValuesResponse(values: Map[String, Seq[ByteString]])

class EthUserService(
    val blockchain: Blockchain,
    val blockchainReader: BlockchainReader,
    val mining: Mining,
    evmCodeStorage: EvmCodeStorage,
    configBuilder: BlockchainConfigBuilder
) extends ResolveBlock:
  import configBuilder.*
  import EthUserService.*

  def getCode(req: GetCodeRequest): ServiceResponse[GetCodeResponse] =
    IO {
      resolveBlock(req.block).map { case ResolvedBlock(block, _) =>
        val world = InMemoryWorldStateProxy(
          evmCodeStorage,
          blockchain.getBackingMptStorage(block.header.number.value),
          (number: BigInt) => blockchainReader.getBlockHeaderByNumber(number).map(_.hash.value),
          blockchainConfig.accountStartNonce,
          block.header.stateRoot.value,
          noEmptyAccounts = false,
          ethCompatibleStorage = blockchainConfig.ethCompatibleStorage
        )
        GetCodeResponse(world.getCode(req.address))
      }
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  def getBalance(req: GetBalanceRequest): ServiceResponse[GetBalanceResponse] =
    withAccount(req.address, req.block) { account =>
      GetBalanceResponse(account.balance)
    }

  def getStorageAt(req: GetStorageAtRequest): ServiceResponse[GetStorageAtResponse] =
    withAccount(req.address, req.block) { account =>
      GetStorageAtResponse(
        blockchain.getAccountStorageAt(account.storageRoot.value, req.position, blockchainConfig.ethCompatibleStorage)
      )
    }

  def getTransactionCount(req: GetTransactionCountRequest): ServiceResponse[GetTransactionCountResponse] =
    withAccount(req.address, req.block) { account =>
      GetTransactionCountResponse(account.nonce)
    }

  def getStorageRoot(req: GetStorageRootRequest): ServiceResponse[GetStorageRootResponse] =
    withAccount(req.address, req.block) { account =>
      GetStorageRootResponse(account.storageRoot.value)
    }

  /** Batch form of eth_getStorageAt. Resolves the block once and reuses it for every address in the request, rather
    * than re-resolving per entry.
    */
  def getStorageValues(req: GetStorageValuesRequest): ServiceResponse[GetStorageValuesResponse] =
    IO {
      resolveBlock(req.block).map { case ResolvedBlock(block, _) =>
        val values = req.entries.map { entry =>
          val account = blockchainReader
            .getAccount(blockchainReader.getBestBranch, entry.address, block.header.number.value)
            .getOrElse(Account.empty(blockchainConfig.accountStartNonce))
          val slotValues = entry.slots.map { position =>
            blockchain.getAccountStorageAt(account.storageRoot.value, position, blockchainConfig.ethCompatibleStorage)
          }
          entry.rawAddress -> slotValues
        }.toMap
        GetStorageValuesResponse(values)
      }
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }

  private def withAccount[T](address: Address, blockParam: BlockParam)(makeResponse: Account => T): ServiceResponse[T] =
    IO {
      resolveBlock(blockParam)
        .map { case ResolvedBlock(block, _) =>
          blockchainReader
            .getAccount(blockchainReader.getBestBranch, address, block.header.number.value)
            .getOrElse(Account.empty(blockchainConfig.accountStartNonce))
        }
        .map(makeResponse)
    }.recover { case _: MissingNodeException =>
      Left(JsonRpcError.NodeNotFound)
    }
