package com.chipprbots.ethereum.jsonrpc.mcp

import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.concurrent.ExecutionContext
import scala.util.Try

import com.chipprbots.ethereum.blockchain.sync.SyncProtocol
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.{AkkaTaskOps, McpDependencies}
import com.chipprbots.ethereum.mpt.MerklePatriciaTrie.MissingNodeException
import com.chipprbots.ethereum.network.PeerManagerActor
import com.chipprbots.ethereum.utils.BuildInfo
import com.chipprbots.ethereum.utils.ByteStringUtils

import AkkaTaskOps._

// --- Static Resources ---

object NodeStatusResource {
  val uri = "fukuii://node/status"
  val name = "Node Status"
  val description = Some("Current status of the Fukuii node including sync state and peer count")
  val mimeType = Some("application/json")

  def read(deps: McpDependencies)(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    val syncStatusIO = deps.syncController.askFor[SyncProtocol.Status](SyncProtocol.GetStatus)
    val peersIO = deps.peerManager.askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers)

    for {
      syncStatus <- syncStatusIO.recover { case _ => SyncProtocol.Status.NotSyncing }
      peers <- peersIO.recover { case _ => PeerManagerActor.Peers(Map.empty) }
    } yield {
      val bestBlock = deps.blockchainReader.getBestBlockNumber()
      val peerCount = peers.peers.size
      val handshakedCount = peers.handshaked.size
      val (syncing, syncState) = syncStatus match {
        case SyncProtocol.Status.Syncing(start, blocks, _) =>
          (true, s""""syncing", "startBlock": $start, "currentBlock": ${blocks.current}, "targetBlock": ${blocks.target}""")
        case SyncProtocol.Status.SyncDone => (false, """"synced"""")
        case SyncProtocol.Status.NotSyncing => (false, """"not_syncing"""")
      }
      s"""{
        |  "running": true,
        |  "syncing": $syncing,
        |  "syncState": $syncState,
        |  "bestBlock": $bestBlock,
        |  "peerCount": $peerCount,
        |  "handshakedPeers": $handshakedCount,
        |  "networkId": ${deps.blockchainConfig.networkId},
        |  "chainId": ${deps.blockchainConfig.chainId}
        |}""".stripMargin
    }
  }
}

object NodeConfigResource {
  val uri = "fukuii://node/config"
  val name = "Node Configuration"
  val description = Some("Current node configuration including chain ID, network, and monetary policy")
  val mimeType = Some("application/json")

  def read(deps: McpDependencies): IO[String] = IO {
    val cfg = deps.blockchainConfig
    s"""{
      |  "chainId": ${cfg.chainId},
      |  "networkId": ${cfg.networkId},
      |  "network": "${if (cfg.chainId == BigInt(61)) "etc" else if (cfg.chainId == BigInt(63)) "mordor" else s"chain-${cfg.chainId}"}",
      |  "accountStartNonce": ${cfg.accountStartNonce},
      |  "maxCodeSize": ${cfg.maxCodeSize.map(_.toString).getOrElse("null")},
      |  "monetaryPolicy": {
      |    "eraDuration": ${cfg.monetaryPolicyConfig.eraDuration},
      |    "rewardReductionRate": ${cfg.monetaryPolicyConfig.rewardReductionRate},
      |    "firstEraBlockReward": "${cfg.monetaryPolicyConfig.firstEraBlockReward}"
      |  },
      |  "version": "${BuildInfo.version}",
      |  "scalaVersion": "${BuildInfo.scalaVersion}"
      |}""".stripMargin
  }
}

object SyncStatusResource {
  val uri = "fukuii://sync/status"
  val name = "Sync Status"
  val description = Some("Current blockchain synchronization status and progress")
  val mimeType = Some("application/json")

  def read(deps: McpDependencies)(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    deps.syncController.askFor[SyncProtocol.Status](SyncProtocol.GetStatus).recover {
      case _ => SyncProtocol.Status.NotSyncing
    }.map { status =>
      val bestBlock = deps.blockchainReader.getBestBlockNumber()
      status match {
        case SyncProtocol.Status.Syncing(start, blocks, stateNodes) =>
          val pct = if (blocks.target > 0) f"${(blocks.current.toDouble / blocks.target.toDouble * 100)}%.2f" else "0"
          val stateJson = stateNodes.filter(_.nonEmpty).map(s =>
            s""", "stateNodes": {"current": ${s.current}, "target": ${s.target}}"""
          ).getOrElse("")
          s"""{
            |  "syncing": true,
            |  "mode": "fast",
            |  "startingBlock": $start,
            |  "currentBlock": ${blocks.current},
            |  "targetBlock": ${blocks.target},
            |  "remainingBlocks": ${blocks.target - blocks.current},
            |  "progressPercent": $pct$stateJson
            |}""".stripMargin
        case SyncProtocol.Status.SyncDone =>
          s"""{
            |  "syncing": false,
            |  "mode": "regular",
            |  "bestBlock": $bestBlock,
            |  "status": "synced"
            |}""".stripMargin
        case SyncProtocol.Status.NotSyncing =>
          s"""{
            |  "syncing": false,
            |  "mode": "idle",
            |  "bestBlock": $bestBlock,
            |  "status": "not_syncing"
            |}""".stripMargin
      }
    }
  }
}

object ConnectedPeersResource {
  val uri = "fukuii://peers/connected"
  val name = "Connected Peers"
  val description = Some("List of currently connected peers with addresses and status")
  val mimeType = Some("application/json")

  def read(deps: McpDependencies)(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    deps.peerManager.askFor[PeerManagerActor.Peers](PeerManagerActor.GetPeers).recover {
      case _ => PeerManagerActor.Peers(Map.empty)
    }.map { peers =>
      val peerEntries = peers.peers.toList.sortBy(_._1.id.value).map { case (peer, status) =>
        val direction = if (peer.incomingConnection) "inbound" else "outbound"
        val addr = peer.remoteAddress.toString
        val statusStr = status match {
          case com.chipprbots.ethereum.network.PeerActor.Status.Handshaked => "handshaked"
          case com.chipprbots.ethereum.network.PeerActor.Status.Connecting => "connecting"
          case com.chipprbots.ethereum.network.PeerActor.Status.Disconnected => "disconnected"
          case s: com.chipprbots.ethereum.network.PeerActor.Status.Handshaking => s"handshaking"
          case _ => "idle"
        }
        s"""    {"id": "${peer.id.value}", "address": "$addr", "direction": "$direction", "status": "$statusStr"}"""
      }
      s"""{
        |  "count": ${peers.peers.size},
        |  "handshakedCount": ${peers.handshaked.size},
        |  "peers": [
        |${peerEntries.mkString(",\n")}
        |  ]
        |}""".stripMargin
    }
  }
}

object MiningRpcResource {
  val uri = "fukuii://mining/rpc"
  val name = "Mining RPC Endpoints"
  val description = Some("Mining JSON-RPC method coverage and usage information")
  val mimeType = Some("application/json")

  def read(): IO[String] = {
    IO.pure("""{
      |  "endpoints": [
      |    {"method": "eth_mining", "description": "Check if the node is mining", "params": []},
      |    {"method": "eth_hashrate", "description": "Get the current hash rate", "params": []},
      |    {"method": "eth_getWork", "description": "Get current work package (powHash, dagSeed, target)", "params": []},
      |    {"method": "eth_coinbase", "description": "Get the coinbase (etherbase) address", "params": []},
      |    {"method": "eth_submitWork", "description": "Submit a proof-of-work solution", "params": ["nonce", "powHash", "mixDigest"]},
      |    {"method": "eth_submitHashrate", "description": "Submit external hashrate for monitoring", "params": ["hashrate", "id"]},
      |    {"method": "miner_start", "description": "Start mining", "params": ["threadCount (optional)"]},
      |    {"method": "miner_stop", "description": "Stop mining", "params": []},
      |    {"method": "miner_getStatus", "description": "Get mining status", "params": []}
      |  ]
      |}""".stripMargin)
  }
}

object LatestBlockResource {
  val uri = "fukuii://blockchain/latest"
  val name = "Latest Block"
  val description = Some("Information about the latest block on the chain")
  val mimeType = Some("application/json")

  def read(deps: McpDependencies): IO[String] = IO {
    val bestBlock = deps.blockchainReader.getBestBlock()
    bestBlock match {
      case Some(block) =>
        val h = block.header
        val td = deps.blockchainReader.getChainWeightByHash(h.hash)
          .map(_.totalDifficulty.toString).getOrElse("unknown")
        val txCount = block.body.transactionList.size
        s"""{
          |  "number": ${h.number},
          |  "hash": "${ByteStringUtils.hash2string(h.hash)}",
          |  "parentHash": "${ByteStringUtils.hash2string(h.parentHash)}",
          |  "miner": "0x${org.bouncycastle.util.encoders.Hex.toHexString(h.beneficiary.toArray)}",
          |  "difficulty": "${h.difficulty}",
          |  "totalDifficulty": "$td",
          |  "gasLimit": ${h.gasLimit},
          |  "gasUsed": ${h.gasUsed},
          |  "timestamp": ${h.unixTimestamp},
          |  "transactionCount": $txCount,
          |  "stateRoot": "${ByteStringUtils.hash2string(h.stateRoot)}",
          |  "extraData": "0x${org.bouncycastle.util.encoders.Hex.toHexString(h.extraData.toArray)}"
          |}""".stripMargin
      case None =>
        """{"error": "No blocks available"}"""
    }
  }
}

// --- URI-Templated Resources ---

object BlockByNumberResource {
  val uri = "fukuii://block/{number}"
  val name = "Block by Number"
  val description = Some("Get a specific block by its number")
  val mimeType = Some("application/json")

  def read(number: BigInt, deps: McpDependencies): IO[String] = IO {
    deps.blockchainReader.getBlockHeaderByNumber(number) match {
      case Some(h) =>
        val td = deps.blockchainReader.getChainWeightByHash(h.hash)
          .map(_.totalDifficulty.toString).getOrElse("unknown")
        s"""{
          |  "number": ${h.number},
          |  "hash": "${ByteStringUtils.hash2string(h.hash)}",
          |  "parentHash": "${ByteStringUtils.hash2string(h.parentHash)}",
          |  "miner": "0x${org.bouncycastle.util.encoders.Hex.toHexString(h.beneficiary.toArray)}",
          |  "difficulty": "${h.difficulty}",
          |  "totalDifficulty": "$td",
          |  "gasLimit": ${h.gasLimit},
          |  "gasUsed": ${h.gasUsed},
          |  "timestamp": ${h.unixTimestamp},
          |  "stateRoot": "${ByteStringUtils.hash2string(h.stateRoot)}",
          |  "extraData": "0x${org.bouncycastle.util.encoders.Hex.toHexString(h.extraData.toArray)}"
          |}""".stripMargin
      case None =>
        s"""{"error": "Block $number not found"}"""
    }
  }
}

object TransactionByHashResource {
  val uri = "fukuii://tx/{hash}"
  val name = "Transaction by Hash"
  val description = Some("Get transaction location by its hash")
  val mimeType = Some("application/json")

  def read(hashStr: String, deps: McpDependencies): IO[String] = IO {
    val hashBytes = Try(org.bouncycastle.util.encoders.Hex.decode(hashStr.stripPrefix("0x"))).getOrElse(Array.empty[Byte])
    if (hashBytes.length != 32) {
      s"""{"error": "Invalid transaction hash: $hashStr"}"""
    } else {
      deps.transactionMappingStorage.get(hashBytes.toIndexedSeq) match {
        case Some(loc) =>
          s"""{
            |  "hash": "$hashStr",
            |  "blockHash": "${ByteStringUtils.hash2string(loc.blockHash)}",
            |  "transactionIndex": ${loc.txIndex}
            |}""".stripMargin
        case None =>
          s"""{"error": "Transaction not found: $hashStr"}"""
      }
    }
  }
}

object AccountByAddressResource {
  val uri = "fukuii://account/{address}"
  val name = "Account by Address"
  val description = Some("Get account state (nonce, balance) by address at the latest block")
  val mimeType = Some("application/json")

  def read(addrStr: String, deps: McpDependencies): IO[String] = IO {
    Try {
      val addrBytes = org.bouncycastle.util.encoders.Hex.decode(addrStr.stripPrefix("0x"))
      val address = Address(org.apache.pekko.util.ByteString(addrBytes))
      val blockNum = deps.blockchainReader.getBestBlockNumber()
      val accountOpt = deps.blockchainReader.getAccount(deps.blockchainReader.getBestBranch(), address, blockNum)
      accountOpt match {
        case Some(account) =>
          val balanceEtc = BigDecimal(account.balance.toBigInt) / BigDecimal("1000000000000000000")
          s"""{
            |  "address": "$addrStr",
            |  "block": $blockNum,
            |  "nonce": ${account.nonce},
            |  "balance": "${account.balance}",
            |  "balanceETC": "$balanceEtc",
            |  "storageRoot": "${ByteStringUtils.hash2string(account.storageRoot)}",
            |  "codeHash": "${ByteStringUtils.hash2string(account.codeHash)}"
            |}""".stripMargin
        case None =>
          s"""{
            |  "address": "$addrStr",
            |  "block": $blockNum,
            |  "status": "empty"
            |}""".stripMargin
      }
    }.recover {
      case _: MissingNodeException => s"""{"error": "Account state unavailable (node is syncing)"}"""
      case e: Exception => s"""{"error": "Error querying account: ${e.getMessage}"}"""
    }.get
  }
}

// --- Resource Registry ---

object McpResourceRegistry {

  def getAllResources(): List[McpResourceDefinition] = List(
    McpResourceDefinition(NodeStatusResource.uri, NodeStatusResource.name,
      NodeStatusResource.description, NodeStatusResource.mimeType),
    McpResourceDefinition(NodeConfigResource.uri, NodeConfigResource.name,
      NodeConfigResource.description, NodeConfigResource.mimeType),
    McpResourceDefinition(SyncStatusResource.uri, SyncStatusResource.name,
      SyncStatusResource.description, SyncStatusResource.mimeType),
    McpResourceDefinition(ConnectedPeersResource.uri, ConnectedPeersResource.name,
      ConnectedPeersResource.description, ConnectedPeersResource.mimeType),
    McpResourceDefinition(MiningRpcResource.uri, MiningRpcResource.name,
      MiningRpcResource.description, MiningRpcResource.mimeType),
    McpResourceDefinition(LatestBlockResource.uri, LatestBlockResource.name,
      LatestBlockResource.description, LatestBlockResource.mimeType),
    McpResourceDefinition(BlockByNumberResource.uri, BlockByNumberResource.name,
      BlockByNumberResource.description, BlockByNumberResource.mimeType),
    McpResourceDefinition(TransactionByHashResource.uri, TransactionByHashResource.name,
      TransactionByHashResource.description, TransactionByHashResource.mimeType),
    McpResourceDefinition(AccountByAddressResource.uri, AccountByAddressResource.name,
      AccountByAddressResource.description, AccountByAddressResource.mimeType)
  )

  def readResource(
      uri: String,
      deps: McpDependencies
  )(implicit timeout: Timeout, ec: ExecutionContext): Either[String, IO[String]] = {
    uri match {
      case NodeStatusResource.uri => Right(NodeStatusResource.read(deps))
      case NodeConfigResource.uri => Right(NodeConfigResource.read(deps))
      case SyncStatusResource.uri => Right(SyncStatusResource.read(deps))
      case ConnectedPeersResource.uri => Right(ConnectedPeersResource.read(deps))
      case MiningRpcResource.uri => Right(MiningRpcResource.read())
      case LatestBlockResource.uri => Right(LatestBlockResource.read(deps))
      case s if s.startsWith("fukuii://block/") =>
        val numStr = s.stripPrefix("fukuii://block/")
        Try(BigInt(numStr)).toOption match {
          case Some(n) => Right(BlockByNumberResource.read(n, deps))
          case None => Left(s"Invalid block number: $numStr")
        }
      case s if s.startsWith("fukuii://tx/") =>
        val hash = s.stripPrefix("fukuii://tx/")
        Right(TransactionByHashResource.read(hash, deps))
      case s if s.startsWith("fukuii://account/") =>
        val addr = s.stripPrefix("fukuii://account/")
        Right(AccountByAddressResource.read(addr, deps))
      case _ => Left(s"Unknown resource: $uri")
    }
  }
}

case class McpResourceDefinition(
    uri: String,
    name: String,
    description: Option[String],
    mimeType: Option[String]
)
