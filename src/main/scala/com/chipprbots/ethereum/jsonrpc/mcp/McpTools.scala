package com.chipprbots.ethereum.jsonrpc.mcp

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.util.Timeout

import cats.effect.IO

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.ExecutionContext

import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST._
import org.json4s.MonadicJValue.jvalueToMonadic

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.consensus.mining.Mining
import com.chipprbots.ethereum.db.storage.TransactionMappingStorage
import com.chipprbots.ethereum.domain.{Address, BlockchainReader, SuccessOutcome, FailureOutcome, HashOutcome}
import com.chipprbots.ethereum.jsonrpc.AkkaTaskOps._
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.consensus.pow.EthashUtils
import com.chipprbots.ethereum.utils.{BlockchainConfig, BuildInfo, ByteStringUtils, NodeStatus}
import com.chipprbots.ethereum.utils.ServerStatus

object NodeInfoTool {
  val name = "mcp_node_info"
  val description = Some("Get detailed information about the Fukuii node including version, network, and chain configuration")

  def execute(
      blockchainConfig: BlockchainConfig
  ): IO[String] = {
    val networkName = blockchainConfig.chainId match {
      case id if id == 1  => "Ethereum Classic Mainnet"
      case id if id == 63 => "Mordor Testnet"
      case id if id == 6  => "Kotti Testnet"
      case id             => s"Chain $id"
    }
    IO.pure(s"""Fukuii Node Information:
      |• Version: ${BuildInfo.version}
      |• Scala Version: ${BuildInfo.scalaVersion}
      |• Git Commit: ${BuildInfo.gitHeadCommit}
      |• Git Branch: ${BuildInfo.gitCurrentBranch}
      |• Network: $networkName
      |• Chain ID: ${blockchainConfig.chainId}
      |• Network ID: ${blockchainConfig.networkId}
      |• Client ID: Fukuii/${BuildInfo.version}""".stripMargin)
  }
}

object NodeStatusTool {
  val name = "mcp_node_status"
  val description = Some("Get the current operational status of the Fukuii node including sync state, peer count, and best block")

  def execute(
      peerManager: ActorRef,
      syncController: ActorRef,
      blockchainReader: BlockchainReader,
      nodeStatusHolder: AtomicReference[NodeStatus]
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    val syncStatusIO = syncController
      .askFor[SyncProtocol.Status](SyncProtocol.GetStatus)
      .handleErrorWith(_ => IO.pure(SyncProtocol.Status.NotSyncing: SyncProtocol.Status))

    val peersIO = peerManager
      .askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers)
      .handleErrorWith(_ => IO.pure(PeerManagerActor.Peers(Map.empty)))

    for {
      syncStatus <- syncStatusIO
      peers <- peersIO
    } yield {
      val bestBlockNum = blockchainReader.getBestBlockNumber()
      val peerCount = peers.handshaked.size
      val nodeStatus = nodeStatusHolder.get()
      val listening = nodeStatus.serverStatus match {
        case _: ServerStatus.Listening => true
        case ServerStatus.NotListening => false
      }

      val syncInfo = syncStatus match {
        case SyncProtocol.Status.Syncing(start, blocks, stateNodes) =>
          val progress = if (blocks.target > 0) {
            f"${(blocks.current.toDouble / blocks.target.toDouble * 100)}%.1f%%"
          } else "calculating..."
          s"""• Syncing: true
            |• Sync Start Block: $start
            |• Current Block: ${blocks.current}
            |• Target Block: ${blocks.target}
            |• Sync Progress: $progress""".stripMargin
        case SyncProtocol.Status.SyncDone =>
          s"• Syncing: false (sync complete)"
        case SyncProtocol.Status.NotSyncing =>
          s"• Syncing: false"
      }

      s"""Node Status:
        |• Running: true
        |• Listening: $listening
        |• Peers: $peerCount
        |• Best Block: $bestBlockNum
        |$syncInfo""".stripMargin
    }
  }
}

object BlockchainInfoTool {
  val name = "mcp_blockchain_info"
  val description = Some("Get information about the blockchain state including best block, chain ID, and total difficulty")

  def execute(
      blockchainReader: BlockchainReader,
      blockchainConfig: BlockchainConfig
  ): IO[String] = IO {
    val bestBlockNum = blockchainReader.getBestBlockNumber()
    val genesisHeader = blockchainReader.genesisHeader
    val genesisHash = s"0x${genesisHeader.hashAsHexString}"

    val bestBlockInfo = blockchainReader.getBestBlock() match {
      case Some(block) =>
        val hash = s"0x${block.header.hashAsHexString}"
        val weight = blockchainReader.getChainWeightByHash(block.header.hash)
        val td = weight.map(_.totalDifficulty.toString).getOrElse("unknown")
        s"""• Best Block Number: $bestBlockNum
          |• Best Block Hash: $hash
          |• Total Difficulty: $td""".stripMargin
      case None =>
        s"• Best Block Number: $bestBlockNum"
    }

    val networkName = blockchainConfig.chainId match {
      case id if id == 1  => "Ethereum Classic (ETC)"
      case id if id == 63 => "Mordor Testnet"
      case id             => s"Chain $id"
    }

    s"""Blockchain Information:
      |• Network: $networkName
      |$bestBlockInfo
      |• Chain ID: ${blockchainConfig.chainId}
      |• Genesis Hash: $genesisHash""".stripMargin
  }
}

object SyncStatusTool {
  val name = "mcp_sync_status"
  val description = Some("Get detailed synchronization status including mode, progress, and block counts")

  def execute(
      syncController: ActorRef,
      blockchainReader: BlockchainReader
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    syncController
      .askFor[SyncProtocol.Status](SyncProtocol.GetStatus)
      .map {
        case SyncProtocol.Status.Syncing(startBlock, blocksProgress, stateNodesProgress) =>
          val remaining = blocksProgress.target - blocksProgress.current
          val progress = if (blocksProgress.target > 0) {
            f"${(blocksProgress.current.toDouble / blocksProgress.target.toDouble * 100)}%.2f%%"
          } else "0.00%"

          val stateInfo = stateNodesProgress match {
            case Some(sp) if sp.nonEmpty =>
              s"""|• State Nodes Downloaded: ${sp.current}
                  |• State Nodes Target: ${sp.target}""".stripMargin
            case _ => ""
          }

          s"""Sync Status:
            |• Syncing: true
            |• Starting Block: $startBlock
            |• Current Block: ${blocksProgress.current}
            |• Target Block: ${blocksProgress.target}
            |• Remaining Blocks: $remaining
            |• Progress: $progress$stateInfo""".stripMargin

        case SyncProtocol.Status.SyncDone =>
          val bestBlock = blockchainReader.getBestBlockNumber()
          s"""Sync Status:
            |• Syncing: false
            |• Status: Sync complete
            |• Best Block: $bestBlock""".stripMargin

        case SyncProtocol.Status.NotSyncing =>
          val bestBlock = blockchainReader.getBestBlockNumber()
          s"""Sync Status:
            |• Syncing: false
            |• Status: Not syncing
            |• Best Block: $bestBlock""".stripMargin
      }
      .handleErrorWith { err =>
        IO.pure(s"""Sync Status:
          |• Error querying sync controller: ${err.getMessage}""".stripMargin)
      }
  }
}

object PeerListTool {
  val name = "mcp_peer_list"
  val description = Some("List all connected peers with their addresses and connection direction")

  def execute(
      peerManager: ActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    peerManager
      .askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers)
      .map { peers =>
        val handshaked = peers.handshaked
        if (handshaked.isEmpty) {
          "Connected Peers: 0\nNo peers currently connected."
        } else {
          val peerLines = handshaked.zipWithIndex.map { case (peer, idx) =>
            val direction = if (peer.incomingConnection) "inbound" else "outbound"
            val addr = peer.remoteAddress.toString
            val nodeIdStr = peer.nodeId.map(id => s"0x${id.take(8).map("%02x".format(_)).mkString}...").getOrElse("unknown")
            s"  ${idx + 1}. $addr ($direction) node=$nodeIdStr"
          }
          s"""Connected Peers: ${handshaked.size}
            |${peerLines.mkString("\n")}""".stripMargin
        }
      }
      .handleErrorWith { err =>
        IO.pure(s"Connected Peers: error querying peer manager (${err.getMessage})")
      }
  }
}

object SetEtherbaseTool {
  val name = "mcp_etherbase_info"
  val description = Some("Get information about the etherbase (coinbase) address configuration for mining rewards")

  def execute(): IO[String] = {
    IO.pure(s"""Etherbase (Coinbase) Configuration:
      |• Method: eth_setEtherbase
      |• Description: Sets the coinbase address for mining rewards
      |• Usage: Send JSON-RPC request with method "eth_setEtherbase" and address parameter
      |• Example: {"jsonrpc":"2.0","method":"eth_setEtherbase","params":["0x1234..."],"id":1}
      |• Related Methods:
      |  - eth_coinbase: Get current coinbase address
      |  - miner_start: Start mining
      |  - miner_stop: Stop mining
      |  - miner_getStatus: Get mining status
      |• Note: Changes take effect immediately for newly generated blocks""".stripMargin)
  }
}

object MiningRpcSummaryTool {
  val name = "mcp_mining_rpc_summary"
  val description = Some("List available mining RPC methods and their descriptions")

  def execute(): IO[String] = {
    IO.pure(s"""Mining RPC Methods:
      |• eth_mining — Returns whether the node is actively mining
      |• eth_hashrate — Returns the current hashrate (H/s)
      |• eth_getWork — Returns current mining work: [powHash, seedHash, target]
      |• eth_submitWork — Submit a PoW solution: [nonce, powHash, mixHash]
      |• eth_submitHashrate — Report external miner hashrate: [hashrate, minerId]
      |• eth_coinbase — Returns the current coinbase address
      |• eth_setEtherbase — Set the coinbase address for mining rewards
      |• miner_start — Start the CPU miner
      |• miner_stop — Stop the CPU miner
      |• miner_getStatus — Returns mining state: {isMining, coinbase, hashRate}
      |
      |Note: Use eth_mining and miner_getStatus to check current mining state.
      |All methods are available via JSON-RPC on the node's HTTP endpoint.""".stripMargin)
  }
}

object GetBlockTool {
  val name = "get_block"
  val description = Some("Get a block by number or hash, including header fields and transaction count")

  def execute(
      blockchainReader: BlockchainReader,
      arguments: Option[JValue]
  ): IO[String] = IO {
    val blockId = arguments.flatMap { a =>
      (a \ "block") match {
        case JString(s) => Some(s)
        case JInt(n)    => Some(n.toString)
        case _          => None
      }
    }.getOrElse("latest")

    val blockOpt = if (blockId == "latest") {
      blockchainReader.getBestBlock()
    } else if (blockId.startsWith("0x") && blockId.length == 66) {
      val hash = ByteString(Hex.decode(blockId.stripPrefix("0x")))
      blockchainReader.getBlockByHash(hash)
    } else {
      val number = if (blockId.startsWith("0x")) BigInt(blockId.stripPrefix("0x"), 16) else BigInt(blockId)
      val branch = blockchainReader.getBestBranch()
      blockchainReader.getBlockByNumber(branch, number)
    }

    blockOpt match {
      case Some(block) =>
        val h = block.header
        val txCount = block.body.transactionList.size
        val uncleCount = block.body.uncleNodesList.size
        val weight = blockchainReader.getChainWeightByHash(h.hash)
        val td = weight.map(_.totalDifficulty.toString).getOrElse("unknown")
        s"""Block ${h.number}:
          |• Hash: 0x${h.hashAsHexString}
          |• Parent Hash: 0x${ByteStringUtils.hash2string(h.parentHash)}
          |• Miner: 0x${ByteStringUtils.hash2string(h.beneficiary)}
          |• Timestamp: ${h.unixTimestamp} (${java.time.Instant.ofEpochSecond(h.unixTimestamp)})
          |• Gas Used: ${h.gasUsed}
          |• Gas Limit: ${h.gasLimit}
          |• Difficulty: ${h.difficulty}
          |• Total Difficulty: $td
          |• Transactions: $txCount
          |• Uncles: $uncleCount
          |• State Root: 0x${ByteStringUtils.hash2string(h.stateRoot)}
          |• Receipts Root: 0x${ByteStringUtils.hash2string(h.receiptsRoot)}
          |• Extra Data: 0x${Hex.toHexString(h.extraData.toArray)}""".stripMargin
      case None =>
        s"Block not found: $blockId"
    }
  }
}

object GetTransactionTool {
  val name = "get_transaction"
  val description = Some("Get a transaction by hash, including block info, gas used, and status")

  def execute(
      blockchainReader: BlockchainReader,
      transactionMappingStorage: Option[TransactionMappingStorage],
      arguments: Option[JValue]
  ): IO[String] = IO {
    val txHashStr = arguments.flatMap { a =>
      (a \ "hash") match {
        case JString(s) => Some(s)
        case _          => None
      }
    }.getOrElse("")

    if (txHashStr.isEmpty) {
      "Error: 'hash' parameter is required (0x-prefixed transaction hash)"
    } else transactionMappingStorage match {
      case None =>
        "Transaction lookup by hash is not available (transactionMappingStorage not configured)"
      case Some(storage) =>
        val txHash = ByteString(Hex.decode(txHashStr.stripPrefix("0x")))
        storage.get(txHash.toIndexedSeq) match {
          case Some(location) =>
            val blockOpt = blockchainReader.getBlockByHash(location.blockHash)
            val receiptsOpt = blockchainReader.getReceiptsByHash(location.blockHash)
            blockOpt match {
              case Some(block) =>
                val tx = block.body.transactionList(location.txIndex)
                val receipt = receiptsOpt.flatMap(_.lift(location.txIndex))
                val gasUsed = receipt.map(_.cumulativeGasUsed.toString).getOrElse("unknown")
                val status = receipt.map(_.postTransactionStateHash).map {
                  case SuccessOutcome     => "success"
                  case FailureOutcome     => "failed"
                  case _: HashOutcome     => "pre-byzantium"
                }.getOrElse("unknown")
                val to = tx.tx.receivingAddress.map(_.toString).getOrElse("(contract creation)")
                s"""Transaction 0x${ByteStringUtils.hash2string(txHash)}:
                  |• Block: ${block.header.number} (0x${block.header.hashAsHexString})
                  |• Index: ${location.txIndex}
                  |• To: $to
                  |• Value: ${tx.tx.value} wei
                  |• Gas Limit: ${tx.tx.gasLimit}
                  |• Gas Price: ${tx.tx.gasPrice} wei
                  |• Gas Used: $gasUsed
                  |• Status: $status
                  |• Nonce: ${tx.tx.nonce}
                  |• Data Size: ${tx.tx.payload.size} bytes""".stripMargin
              case None =>
                s"Transaction found in mapping but block 0x${ByteStringUtils.hash2string(location.blockHash)} not available"
            }
          case None =>
            s"Transaction not found: $txHashStr"
        }
    }
  }
}

object GetAccountTool {
  val name = "get_account"
  val description = Some("Get account balance, nonce, and code hash for an address at a given block")

  def execute(
      blockchainReader: BlockchainReader,
      arguments: Option[JValue]
  ): IO[String] = IO {
    val addressStr = arguments.flatMap { a =>
      (a \ "address") match {
        case JString(s) => Some(s)
        case _          => None
      }
    }.getOrElse("")

    val blockStr = arguments.flatMap { a =>
      (a \ "block") match {
        case JString(s) => Some(s)
        case JInt(n)    => Some(n.toString)
        case _          => None
      }
    }.getOrElse("latest")

    if (addressStr.isEmpty) {
      "Error: 'address' parameter is required (0x-prefixed Ethereum address)"
    } else {
      val address = Address(addressStr)
      val blockNumber = if (blockStr == "latest") blockchainReader.getBestBlockNumber() else BigInt(blockStr)
      val branch = blockchainReader.getBestBranch()

      blockchainReader.getAccount(branch, address, blockNumber) match {
        case Some(account) =>
          val balanceWei = account.balance.toBigInt
          val balanceEther = BigDecimal(balanceWei) / BigDecimal("1000000000000000000")
          val hasCode = account.codeHash != com.chipprbots.ethereum.domain.Account.EmptyCodeHash
          s"""Account $addressStr at block $blockNumber:
            |• Balance: $balanceWei wei ($balanceEther ETC)
            |• Nonce: ${account.nonce}
            |• Has Code: $hasCode
            |• Storage Root: 0x${ByteStringUtils.hash2string(account.storageRoot)}
            |• Code Hash: 0x${ByteStringUtils.hash2string(account.codeHash)}""".stripMargin
        case None =>
          s"""Account $addressStr at block $blockNumber:
            |• Balance: 0 wei (0 ETC)
            |• Nonce: 0
            |• Has Code: false
            |• Note: Account does not exist (empty account)""".stripMargin
      }
    }
  }
}

object GetBlockReceiptsTool {
  val name = "get_block_receipts"
  val description = Some("Get all transaction receipts for a block by number or hash")

  def execute(
      blockchainReader: BlockchainReader,
      arguments: Option[JValue]
  ): IO[String] = IO {
    val blockId = arguments.flatMap { a =>
      (a \ "block") match {
        case JString(s) => Some(s)
        case JInt(n)    => Some(n.toString)
        case _          => None
      }
    }.getOrElse("latest")

    val blockOpt = if (blockId == "latest") {
      blockchainReader.getBestBlock()
    } else if (blockId.startsWith("0x") && blockId.length == 66) {
      val hash = ByteString(Hex.decode(blockId.stripPrefix("0x")))
      blockchainReader.getBlockByHash(hash)
    } else {
      val number = if (blockId.startsWith("0x")) BigInt(blockId.stripPrefix("0x"), 16) else BigInt(blockId)
      val branch = blockchainReader.getBestBranch()
      blockchainReader.getBlockByNumber(branch, number)
    }

    blockOpt match {
      case Some(block) =>
        val receiptsOpt = blockchainReader.getReceiptsByHash(block.header.hash)
        val txs = block.body.transactionList
        receiptsOpt match {
          case Some(receipts) =>
            val lines = txs.zip(receipts).zipWithIndex.map { case ((tx, receipt), idx) =>
              val to = tx.tx.receivingAddress.map(_.toString).getOrElse("(create)")
              val status = receipt.postTransactionStateHash match {
                case SuccessOutcome => "ok"
                case FailureOutcome => "fail"
                case _              => "?"
              }
              val logs = receipt.logs.size
              s"  $idx: 0x${ByteStringUtils.hash2string(tx.hash)} → $to [$status] gas=${receipt.cumulativeGasUsed} logs=$logs"
            }
            s"""Block ${block.header.number} Receipts (${receipts.size} transactions):
              |${lines.mkString("\n")}""".stripMargin
          case None =>
            s"Receipts not available for block ${block.header.number}"
        }
      case None =>
        s"Block not found: $blockId"
    }
  }
}

object GetGasPriceTool {
  val name = "get_gas_price"
  val description = Some("Estimate current gas price based on recent block headers")

  def execute(
      blockchainReader: BlockchainReader
  ): IO[String] = IO {
    val bestNum = blockchainReader.getBestBlockNumber()
    val sampleSize = 20
    val startBlock = if (bestNum > sampleSize) bestNum - sampleSize else BigInt(0)
    val branch = blockchainReader.getBestBranch()

    val gasPrices: IndexedSeq[BigInt] = (startBlock to bestNum).flatMap { num =>
      blockchainReader.getBlockByNumber(branch, num).toSeq.flatMap { block =>
        block.body.transactionList.map(_.tx.gasPrice)
      }
    }.sorted

    if (gasPrices.isEmpty) {
      s"""Gas Price Estimate:
        |• No transactions in recent $sampleSize blocks
        |• Blocks sampled: $startBlock to $bestNum
        |• Suggested: Use minimum gas price (1 Gwei = 1000000000 wei)""".stripMargin
    } else {
      val median = gasPrices(gasPrices.size / 2)
      val min = gasPrices.head
      val max = gasPrices.last
      val avg = gasPrices.sum / gasPrices.size
      s"""Gas Price Estimate (from ${gasPrices.size} transactions in blocks $startBlock-$bestNum):
        |• Median: $median wei (${BigDecimal(median) / BigDecimal("1000000000")} Gwei)
        |• Average: $avg wei (${BigDecimal(avg) / BigDecimal("1000000000")} Gwei)
        |• Min: $min wei
        |• Max: $max wei""".stripMargin
    }
  }
}

object DecodeCalldataTool {
  val name = "decode_calldata"
  val description = Some("Decode hex calldata into function selector and ABI-encoded parameter words")

  def execute(
      arguments: Option[JValue]
  ): IO[String] = IO {
    val dataStr = arguments.flatMap { a =>
      (a \ "data") match {
        case JString(s) => Some(s)
        case _          => None
      }
    }.getOrElse("")

    if (dataStr.isEmpty || dataStr.length < 10) {
      "Error: 'data' parameter is required (0x-prefixed hex calldata, minimum 4 bytes for selector)"
    } else {
      val hex = dataStr.stripPrefix("0x")
      val selector = hex.take(8)
      val params = hex.drop(8)
      val words = params.grouped(64).zipWithIndex.map { case (word, idx) =>
        val padded = word.padTo(64, '0')
        val asInt = BigInt(padded, 16)
        s"  [$idx] 0x$padded ($asInt)"
      }.toList

      val result = new StringBuilder
      result.append(s"Calldata Decode (${hex.length / 2} bytes):\n")
      result.append(s"• Selector: 0x$selector\n")
      result.append(s"• Parameters: ${words.size} words (${params.length / 2} bytes)")
      if (words.nonEmpty) {
        result.append("\n")
        result.append(words.mkString("\n"))
      }
      result.toString
    }
  }
}

object GetNetworkHealthTool {
  val name = "get_network_health"
  val description = Some("Get a composite health assessment including sync lag, peer count, and block production rate")

  def execute(
      peerManager: ActorRef,
      syncController: ActorRef,
      blockchainReader: BlockchainReader,
      nodeStatusHolder: AtomicReference[NodeStatus]
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    val syncStatusIO = syncController
      .askFor[SyncProtocol.Status](SyncProtocol.GetStatus)
      .handleErrorWith(_ => IO.pure(SyncProtocol.Status.NotSyncing: SyncProtocol.Status))

    val peersIO = peerManager
      .askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers)
      .handleErrorWith(_ => IO.pure(PeerManagerActor.Peers(Map.empty)))

    for {
      syncStatus <- syncStatusIO
      peers <- peersIO
    } yield {
      val bestBlockNum = blockchainReader.getBestBlockNumber()
      val peerCount = peers.handshaked.size
      val branch = blockchainReader.getBestBranch()

      // Check block production rate from last 10 blocks
      val blockRate = if (bestBlockNum > 10) {
        val recent = blockchainReader.getBlockByNumber(branch, bestBlockNum)
        val older = blockchainReader.getBlockByNumber(branch, bestBlockNum - 10)
        (recent, older) match {
          case (Some(r), Some(o)) =>
            val timeDiff = r.header.unixTimestamp - o.header.unixTimestamp
            if (timeDiff > 0) Some(f"${10.0 / timeDiff * 60}%.1f blocks/min (avg ${timeDiff / 10}s/block)")
            else None
          case _ => None
        }
      } else None

      val (syncLag, syncing) = syncStatus match {
        case SyncProtocol.Status.Syncing(_, blocks, _) =>
          (Some(blocks.target - blocks.current), true)
        case _ => (None, false)
      }

      // Health assessment
      val issues = List.newBuilder[String]
      if (peerCount == 0) issues += "NO PEERS - node is isolated"
      else if (peerCount < 3) issues += s"LOW PEERS ($peerCount) - may have connectivity issues"
      if (syncing) syncLag.foreach(lag => if (lag > 100) issues += s"SYNCING - $lag blocks behind")
      val issueList = issues.result()
      val status = if (issueList.isEmpty) "HEALTHY" else "DEGRADED"

      val blockRateStr = blockRate.getOrElse("insufficient data")
      val syncStr = if (syncing) s"yes (${syncLag.getOrElse(0)} blocks behind)" else "no"

      s"""Network Health: $status
        |• Peers: $peerCount
        |• Best Block: $bestBlockNum
        |• Syncing: $syncStr
        |• Block Rate: $blockRateStr
        |${if (issueList.nonEmpty) "• Issues:\n" + issueList.map(i => s"  ⚠ $i").mkString("\n") else "• No issues detected"}""".stripMargin
    }
  }
}

object DetectReorgTool {
  val name = "detect_reorg"
  val description = Some("Check recent blocks for chain reorganizations by verifying parent hash consistency")

  def execute(
      blockchainReader: BlockchainReader,
      arguments: Option[JValue]
  ): IO[String] = IO {
    val depth = arguments.flatMap { a =>
      (a \ "depth") match {
        case JInt(n)    => Some(n.toInt)
        case JString(s) => scala.util.Try(s.toInt).toOption
        case _          => None
      }
    }.getOrElse(10)

    val bestNum = blockchainReader.getBestBlockNumber()
    val startBlock = if (bestNum > depth) bestNum - depth else BigInt(1)
    val branch = blockchainReader.getBestBranch()

    val inconsistencies = List.newBuilder[String]
    var prevHash: Option[ByteString] = None

    (startBlock to bestNum).foreach { num =>
      blockchainReader.getBlockByNumber(branch, num) match {
        case Some(block) =>
          prevHash.foreach { expected =>
            if (block.header.parentHash != expected) {
              inconsistencies += s"  Block $num: parentHash 0x${ByteStringUtils.hash2string(block.header.parentHash)} != expected 0x${ByteStringUtils.hash2string(expected)}"
            }
          }
          prevHash = Some(block.header.hash)
        case None =>
          inconsistencies += s"  Block $num: MISSING from local chain"
          prevHash = None
      }
    }

    val issues = inconsistencies.result()
    if (issues.isEmpty) {
      s"""Reorg Check (blocks $startBlock to $bestNum): CLEAN
        |• All $depth blocks have consistent parent hashes
        |• Chain tip: $bestNum""".stripMargin
    } else {
      s"""Reorg Check (blocks $startBlock to $bestNum): INCONSISTENCIES FOUND
        |• ${issues.size} issue(s) detected:
        |${issues.mkString("\n")}""".stripMargin
    }
  }
}

object ConvertUnitsTool {
  val name = "convert_units"
  val description = Some("Convert between Ethereum denominations: wei, gwei, and ether/ETC")

  def execute(
      arguments: Option[JValue]
  ): IO[String] = IO {
    val value = arguments.flatMap { a =>
      (a \ "value") match {
        case JString(s) => Some(s)
        case JInt(n)    => Some(n.toString)
        case _          => None
      }
    }.getOrElse("")

    val unit = arguments.flatMap { a =>
      (a \ "unit") match {
        case JString(s) => Some(s.toLowerCase)
        case _          => None
      }
    }.getOrElse("wei")

    if (value.isEmpty) {
      "Error: 'value' parameter is required (numeric value to convert)"
    } else {
      val weiValueOpt: Option[BigDecimal] = unit match {
        case "wei"           => Some(BigDecimal(value))
        case "gwei"          => Some(BigDecimal(value) * BigDecimal("1000000000"))
        case "ether" | "etc" => Some(BigDecimal(value) * BigDecimal("1000000000000000000"))
        case _               => None
      }
      weiValueOpt match {
        case None =>
          s"Error: Unknown unit '$unit'. Use 'wei', 'gwei', or 'ether'/'etc'"
        case Some(weiValue) =>
          val weiStr = weiValue.toBigInt.toString
          val gweiStr = (weiValue / BigDecimal("1000000000")).toString
          val etherStr = (weiValue / BigDecimal("1000000000000000000")).toString
          s"""Unit Conversion ($value $unit):
            |• Wei: $weiStr
            |• Gwei: $gweiStr
            |• ETC: $etherStr""".stripMargin
      }
    }
  }
}

object GetEtcEmissionTool {
  val name = "get_etc_emission"
  val description = Some("Get ETC monetary policy: era duration, reward schedule, current era, and estimated supply")

  def execute(
      blockchainReader: BlockchainReader,
      blockchainConfig: BlockchainConfig
  ): IO[String] = IO {
    val mp = blockchainConfig.monetaryPolicyConfig
    val bestBlock = blockchainReader.getBestBlockNumber()
    val currentEra = bestBlock / mp.eraDuration
    val blocksIntoEra = bestBlock % mp.eraDuration
    val blocksRemainingInEra = mp.eraDuration - blocksIntoEra

    // Calculate current era reward
    val weiPerEther = BigDecimal("1000000000000000000")
    val currentReward: BigInt = if (currentEra == 0) {
      mp.firstEraBlockReward
    } else {
      val reduction = Math.pow(1.0 - mp.rewardReductionRate, currentEra.toDouble)
      (BigDecimal(mp.firstEraBlockReward) * BigDecimal(reduction)).toBigInt
    }
    val rewardInEtc = BigDecimal(currentReward) / weiPerEther

    // Estimate total supply (simplified: sum of era rewards)
    val totalSupply = (0L to currentEra.toLong).foldLeft(BigDecimal(0)) { (acc, era) =>
      val eraBlocks: Long = if (era < currentEra) mp.eraDuration.toLong else blocksIntoEra.toLong
      val eraReward = if (era == 0) {
        BigDecimal(mp.firstEraBlockReward)
      } else {
        val reduction = Math.pow(1.0 - mp.rewardReductionRate, era.toDouble)
        BigDecimal(mp.firstEraBlockReward) * BigDecimal(reduction)
      }
      acc + eraReward * eraBlocks / weiPerEther
    }

    s"""ETC Monetary Policy
       |
       |Era Duration: ${mp.eraDuration} blocks
       |Reward Reduction Rate: ${(mp.rewardReductionRate * 100)}% per era
       |First Era Block Reward: ${BigDecimal(mp.firstEraBlockReward) / weiPerEther} ETC
       |
       |Current State:
       |• Current Era: $currentEra (of unlimited)
       |• Best Block: $bestBlock
       |• Blocks Into Era: $blocksIntoEra / ${mp.eraDuration}
       |• Blocks Remaining In Era: $blocksRemainingInEra
       |• Current Block Reward: $rewardInEtc ETC ($currentReward wei)
       |
       |Estimated Mined Supply: ~${totalSupply.setScale(2, BigDecimal.RoundingMode.HALF_UP)} ETC
       |
       |Note: ETC has no hard supply cap. Supply grows at a decreasing rate
       |as block rewards reduce by ${(mp.rewardReductionRate * 100)}% every ${mp.eraDuration} blocks.""".stripMargin
  }
}

object GetEtcForksTool {
  val name = "get_etc_forks"
  val description = Some("Get all ETC fork activation block numbers including Atlantis, Agharta, Phoenix, Magneto, Mystique, Spiral, and ECIPs")

  def execute(
      blockchainReader: BlockchainReader,
      blockchainConfig: BlockchainConfig
  ): IO[String] = IO {
    val f = blockchainConfig.forkBlockNumbers
    val bestBlock = blockchainReader.getBestBlockNumber()

    def forkStatus(blockNum: BigInt): String =
      if (bestBlock >= blockNum) s"ACTIVE (block $blockNum)" else s"PENDING (block $blockNum, ${blockNum - bestBlock} blocks away)"

    def optForkStatus(blockNum: Option[BigInt]): String = blockNum match {
      case Some(n) => forkStatus(n)
      case None => "NOT SCHEDULED"
    }

    s"""ETC Fork History (Chain ID: ${blockchainConfig.chainId})
       |Best Block: $bestBlock
       |
       |== Ethereum-Heritage Forks ==
       |Frontier:       ${forkStatus(f.frontierBlockNumber)}
       |Homestead:      ${forkStatus(f.homesteadBlockNumber)}
       |EIP-150 (Gas):  ${forkStatus(f.eip150BlockNumber)}
       |EIP-155 (Replay): ${forkStatus(f.eip155BlockNumber)}
       |EIP-160:        ${forkStatus(f.eip160BlockNumber)}
       |EIP-161:        ${forkStatus(f.eip161BlockNumber)}
       |Byzantium:      ${forkStatus(f.byzantiumBlockNumber)}
       |Constantinople: ${forkStatus(f.constantinopleBlockNumber)}
       |Petersburg:     ${forkStatus(f.petersburgBlockNumber)}
       |Istanbul:       ${forkStatus(f.istanbulBlockNumber)}
       |Muir Glacier:   ${forkStatus(f.muirGlacierBlockNumber)}
       |Berlin:         ${forkStatus(f.berlinBlockNumber)}
       |
       |== ETC-Specific Forks ==
       |Atlantis:       ${forkStatus(f.atlantisBlockNumber)}
       |Agharta:        ${forkStatus(f.aghartaBlockNumber)}
       |Phoenix:        ${forkStatus(f.phoenixBlockNumber)}
       |Magneto:        ${forkStatus(f.magnetoBlockNumber)}
       |Mystique:       ${forkStatus(f.mystiqueBlockNumber)}
       |Spiral:         ${forkStatus(f.spiralBlockNumber)}
       |
       |== ECIPs ==
       |ECIP-1097 (Strict Difficulty): ${forkStatus(f.ecip1097BlockNumber)}
       |ECIP-1098 (Treasury):          ${forkStatus(f.ecip1098BlockNumber)}
       |ECIP-1099 (DAG Epoch 60k):     ${forkStatus(f.ecip1099BlockNumber)}
       |ECIP-1049 (SHA-3):             ${optForkStatus(f.ecip1049BlockNumber)}
       |
       |== Difficulty Bomb ==
       |Pause:    ${forkStatus(f.difficultyBombPauseBlockNumber)}
       |Continue: ${forkStatus(f.difficultyBombContinueBlockNumber)}
       |Removal:  ${forkStatus(f.difficultyBombRemovalBlockNumber)}""".stripMargin
  }
}

object GetTreasuryStatusTool {
  val name = "get_treasury_status"
  val description = Some("Get ETC treasury address and balance from the ECIP-1098 treasury configuration")

  def execute(
      blockchainReader: BlockchainReader,
      blockchainConfig: BlockchainConfig
  ): IO[String] = IO {
    val treasuryAddr = blockchainConfig.treasuryAddress
    val bestBlock = blockchainReader.getBestBlockNumber()
    val f = blockchainConfig.forkBlockNumbers

    val treasuryActive = bestBlock >= f.ecip1098BlockNumber
    val activationBlock = f.ecip1098BlockNumber

    // Try to get treasury account balance
    val bestBranch = blockchainReader.getBestBranch()
    val accountOpt = blockchainReader.getAccount(bestBranch, Address(treasuryAddr.toArray), bestBlock)

    val balanceInfo = accountOpt match {
      case Some(account) =>
        val weiPerEther = BigDecimal("1000000000000000000")
        val balanceEtc = BigDecimal(account.balance.toBigInt) / weiPerEther
        s"""Balance: ${account.balance.toBigInt} wei ($balanceEtc ETC)
           |Nonce: ${account.nonce}""".stripMargin
      case None =>
        "Balance: Account not found in state trie (node may still be syncing)"
    }

    s"""ETC Treasury Status
       |
       |Treasury Address: 0x${Hex.toHexString(treasuryAddr.toArray)}
       |ECIP-1098 Activation: Block $activationBlock
       |Status: ${if (treasuryActive) "ACTIVE" else s"PENDING (${activationBlock - bestBlock} blocks away)"}
       |Current Block: $bestBlock
       |
       |$balanceInfo
       |
       |The treasury receives a portion of block rewards per ECIP-1098.
       |Funds are governed by the ETC community.""".stripMargin
  }
}

object VerifyEthashBlockTool {
  val name = "verify_ethash_block"
  val description = Some("Get Ethash proof-of-work details for a block: epoch, difficulty, nonce, mixHash, and DAG size")

  def execute(
      blockchainReader: BlockchainReader,
      blockchainConfig: BlockchainConfig,
      arguments: Option[JValue]
  ): IO[String] = IO {
    val blockArg = arguments.flatMap { a =>
      (a \ "block") match {
        case JString(s) => Some(s)
        case _ => None
      }
    }.getOrElse("latest")

    val headerOpt = if (blockArg == "latest") {
      val bestNum = blockchainReader.getBestBlockNumber()
      blockchainReader.getBlockHeaderByNumber(bestNum)
    } else if (blockArg.startsWith("0x") && blockArg.length == 66) {
      val hash = ByteString(Hex.decode(blockArg.drop(2)))
      blockchainReader.getBlockHeaderByHash(hash)
    } else {
      scala.util.Try(BigInt(blockArg)).toOption.flatMap(n =>
        blockchainReader.getBlockHeaderByNumber(n)
      )
    }

    headerOpt match {
      case None => s"Block not found: $blockArg"
      case Some(h) =>
        val blockNum = h.number.toLong
        val ecip1099Block = blockchainConfig.forkBlockNumbers.ecip1099BlockNumber.toLong
        val epochNum = EthashUtils.epoch(blockNum, ecip1099Block)
        val epochLength = if (blockNum < ecip1099Block) EthashUtils.EPOCH_LENGTH_BEFORE_ECIP_1099 else EthashUtils.EPOCH_LENGTH_AFTER_ECIP_1099
        val dagSizeBytes = EthashUtils.dagSize(epochNum)
        val dagSizeMB = dagSizeBytes / (1024L * 1024L)
        val cacheSizeBytes = EthashUtils.cacheSize(epochNum)
        val cacheSizeMB = cacheSizeBytes / (1024L * 1024L)

        s"""Ethash PoW Details — Block ${h.number}
           |
           |Block Hash: 0x${ByteStringUtils.hash2string(h.hash)}
           |Difficulty: ${h.difficulty}
           |Nonce: 0x${Hex.toHexString(h.nonce.toArray)}
           |MixHash: 0x${ByteStringUtils.hash2string(h.mixHash)}
           |
           |Ethash Parameters:
           |• Epoch: $epochNum
           |• Epoch Length: $epochLength blocks ${if (blockNum >= ecip1099Block) "(ECIP-1099 doubled)" else "(pre-ECIP-1099)"}
           |• DAG Size: $dagSizeMB MB ($dagSizeBytes bytes)
           |• Cache Size: $cacheSizeMB MB ($cacheSizeBytes bytes)
           |
           |ECIP-1099 (DAG epoch doubling): ${if (blockNum >= ecip1099Block) "ACTIVE" else "NOT YET ACTIVE"}
           |ECIP-1099 Activation Block: $ecip1099Block""".stripMargin
    }
  }
}

object McpToolRegistry {

  def getAllTools(): List[McpToolDefinition] = List(
    McpToolDefinition(NodeStatusTool.name, NodeStatusTool.description),
    McpToolDefinition(NodeInfoTool.name, NodeInfoTool.description),
    McpToolDefinition(BlockchainInfoTool.name, BlockchainInfoTool.description),
    McpToolDefinition(SyncStatusTool.name, SyncStatusTool.description),
    McpToolDefinition(PeerListTool.name, PeerListTool.description),
    McpToolDefinition(SetEtherbaseTool.name, SetEtherbaseTool.description),
    McpToolDefinition(MiningRpcSummaryTool.name, MiningRpcSummaryTool.description),
    McpToolDefinition(GetBlockTool.name, GetBlockTool.description),
    McpToolDefinition(GetTransactionTool.name, GetTransactionTool.description),
    McpToolDefinition(GetAccountTool.name, GetAccountTool.description),
    McpToolDefinition(GetBlockReceiptsTool.name, GetBlockReceiptsTool.description),
    McpToolDefinition(GetGasPriceTool.name, GetGasPriceTool.description),
    McpToolDefinition(DecodeCalldataTool.name, DecodeCalldataTool.description),
    McpToolDefinition(GetNetworkHealthTool.name, GetNetworkHealthTool.description),
    McpToolDefinition(DetectReorgTool.name, DetectReorgTool.description),
    McpToolDefinition(ConvertUnitsTool.name, ConvertUnitsTool.description),
    McpToolDefinition(GetEtcEmissionTool.name, GetEtcEmissionTool.description),
    McpToolDefinition(GetEtcForksTool.name, GetEtcForksTool.description),
    McpToolDefinition(GetTreasuryStatusTool.name, GetTreasuryStatusTool.description),
    McpToolDefinition(VerifyEthashBlockTool.name, VerifyEthashBlockTool.description)
  )

  def executeTool(
      toolName: String,
      arguments: Option[JValue],
      peerManager: ActorRef,
      syncController: ActorRef,
      blockchainReader: BlockchainReader,
      blockchainConfig: BlockchainConfig,
      mining: Mining,
      nodeStatusHolder: AtomicReference[NodeStatus],
      transactionMappingStorage: Option[TransactionMappingStorage] = None
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    toolName match {
      case NodeStatusTool.name =>
        NodeStatusTool.execute(peerManager, syncController, blockchainReader, nodeStatusHolder)
      case NodeInfoTool.name =>
        NodeInfoTool.execute(blockchainConfig)
      case BlockchainInfoTool.name =>
        BlockchainInfoTool.execute(blockchainReader, blockchainConfig)
      case SyncStatusTool.name =>
        SyncStatusTool.execute(syncController, blockchainReader)
      case PeerListTool.name =>
        PeerListTool.execute(peerManager)
      case SetEtherbaseTool.name =>
        SetEtherbaseTool.execute()
      case MiningRpcSummaryTool.name =>
        MiningRpcSummaryTool.execute()
      case GetBlockTool.name =>
        GetBlockTool.execute(blockchainReader, arguments)
      case GetTransactionTool.name =>
        GetTransactionTool.execute(blockchainReader, transactionMappingStorage, arguments)
      case GetAccountTool.name =>
        GetAccountTool.execute(blockchainReader, arguments)
      case GetBlockReceiptsTool.name =>
        GetBlockReceiptsTool.execute(blockchainReader, arguments)
      case GetGasPriceTool.name =>
        GetGasPriceTool.execute(blockchainReader)
      case DecodeCalldataTool.name =>
        DecodeCalldataTool.execute(arguments)
      case GetNetworkHealthTool.name =>
        GetNetworkHealthTool.execute(peerManager, syncController, blockchainReader, nodeStatusHolder)
      case DetectReorgTool.name =>
        DetectReorgTool.execute(blockchainReader, arguments)
      case ConvertUnitsTool.name =>
        ConvertUnitsTool.execute(arguments)
      case GetEtcEmissionTool.name =>
        GetEtcEmissionTool.execute(blockchainReader, blockchainConfig)
      case GetEtcForksTool.name =>
        GetEtcForksTool.execute(blockchainReader, blockchainConfig)
      case GetTreasuryStatusTool.name =>
        GetTreasuryStatusTool.execute(blockchainReader, blockchainConfig)
      case VerifyEthashBlockTool.name =>
        VerifyEthashBlockTool.execute(blockchainReader, blockchainConfig, arguments)
      case _ =>
        IO.pure(s"Unknown tool: $toolName. Use tools/list to see available tools.")
    }
  }
}

case class McpToolDefinition(
    name: String,
    description: Option[String]
)
