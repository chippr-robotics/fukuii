package com.chipprbots.ethereum.jsonrpc.mcp

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout

import cats.effect.IO

import scala.concurrent.ExecutionContext

/**
 * Node status resource for MCP.
 * Provides current node status as JSON.
 */
object NodeStatusResource {
  val uri = "fukuii://node/status"
  val name = "Node Status"
  val description = Some("Current status of the Fukuii node")
  val mimeType = Some("application/json")
  
  def read(
      peerManager: ActorRef,
      syncController: ActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    // TODO: Query actual node state
    IO.pure("""{
      |  "running": true,
      |  "syncing": "querying",
      |  "peerCount": "querying",
      |  "blockNumber": "querying",
      |  "bestKnownBlock": "querying",
      |  "networkId": 61,
      |  "chainId": 61
      |}""".stripMargin)
  }
}

/**
 * Node configuration resource for MCP.
 * Provides current node configuration as JSON.
 */
object NodeConfigResource {
  val uri = "fukuii://node/config"
  val name = "Node Configuration"
  val description = Some("Current node configuration")
  val mimeType = Some("application/json")
  
  def read(): IO[String] = {
    IO.pure("""{
      |  "network": "etc",
      |  "datadir": "~/.fukuii/datadir",
      |  "rpc": {
      |    "enabled": true,
      |    "port": 8545,
      |    "interface": "localhost"
      |  },
      |  "discovery": {
      |    "enabled": true,
      |    "port": 30303
      |  }
      |}""".stripMargin)
  }
}

/**
 * Latest block resource for MCP.
 * Provides information about the latest block.
 */
object LatestBlockResource {
  val uri = "fukuii://blockchain/latest"
  val name = "Latest Block"
  val description = Some("Information about the latest block")
  val mimeType = Some("application/json")
  
  def read(
      syncController: ActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    // TODO: Query actual blockchain state
    IO.pure("""{
      |  "number": "querying",
      |  "hash": "querying",
      |  "timestamp": 1700000000,
      |  "difficulty": "querying",
      |  "gasLimit": 8000000,
      |  "gasUsed": "querying",
      |  "transactionCount": "querying"
      |}""".stripMargin)
  }
}

/**
 * Connected peers resource for MCP.
 * Lists currently connected peers.
 */
object ConnectedPeersResource {
  val uri = "fukuii://peers/connected"
  val name = "Connected Peers"
  val description = Some("List of currently connected peers")
  val mimeType = Some("application/json")
  
  def read(
      peerManager: ActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    // TODO: Query PeerManagerActor for actual peers
    IO.pure("""{
      |  "count": "querying",
      |  "peers": []
      |}""".stripMargin)
  }
}

/**
 * Sync status resource for MCP.
 * Provides detailed synchronization status.
 */
object SyncStatusResource {
  val uri = "fukuii://sync/status"
  val name = "Sync Status"
  val description = Some("Current blockchain synchronization status")
  val mimeType = Some("application/json")
  
  def read(
      syncController: ActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): IO[String] = {
    // TODO: Query SyncController for actual status
    IO.pure("""{
      |  "syncing": "querying",
      |  "mode": "regular",
      |  "currentBlock": "querying",
      |  "targetBlock": "querying",
      |  "remainingBlocks": "calculating",
      |  "progress": "calculating"
      |}""".stripMargin)
  }
}

/**
 * Registry of all available MCP resources.
 * This makes it easy to add/remove resources and track changes.
 */
object McpResourceRegistry {
  
  /**
   * Get all available resource definitions.
   * Each resource is defined in its own object for modularity.
   */
  def getAllResources(): List[McpResourceDefinition] = List(
    McpResourceDefinition(NodeStatusResource.uri, NodeStatusResource.name, 
      NodeStatusResource.description, NodeStatusResource.mimeType),
    McpResourceDefinition(NodeConfigResource.uri, NodeConfigResource.name,
      NodeConfigResource.description, NodeConfigResource.mimeType),
    McpResourceDefinition(LatestBlockResource.uri, LatestBlockResource.name,
      LatestBlockResource.description, LatestBlockResource.mimeType),
    McpResourceDefinition(ConnectedPeersResource.uri, ConnectedPeersResource.name,
      ConnectedPeersResource.description, ConnectedPeersResource.mimeType),
    McpResourceDefinition(SyncStatusResource.uri, SyncStatusResource.name,
      SyncStatusResource.description, SyncStatusResource.mimeType)
  )
  
  /**
   * Read a resource by URI.
   */
  def readResource(
      uri: String,
      peerManager: ActorRef,
      syncController: ActorRef
  )(implicit timeout: Timeout, ec: ExecutionContext): Either[String, IO[String]] = {
    uri match {
      case NodeStatusResource.uri => Right(NodeStatusResource.read(peerManager, syncController))
      case NodeConfigResource.uri => Right(NodeConfigResource.read())
      case LatestBlockResource.uri => Right(LatestBlockResource.read(syncController))
      case ConnectedPeersResource.uri => Right(ConnectedPeersResource.read(peerManager))
      case SyncStatusResource.uri => Right(SyncStatusResource.read(syncController))
      case _ => Left(s"Unknown resource: $uri")
    }
  }
}

/**
 * Simple resource definition for registration.
 */
case class McpResourceDefinition(
    uri: String,
    name: String,
    description: Option[String],
    mimeType: Option[String]
)
