package com.chipprbots.ethereum.network

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import org.json4s._
import org.json4s.native.JsonMethods._

import com.chipprbots.ethereum.utils.Logger

/** Loads static peer nodes from a JSON file at startup.
  *
  * Besu reference:
  *   ethereum/p2p/src/main/java/org/hyperledger/besu/ethereum/p2p/peers/StaticNodesParser.java
  *   - fromPath(): reads static-nodes.json → Set[EnodeURLImpl]
  *   - Each parsed peer is added to MaintainedPeers (DefaultP2PNetwork.Builder.maintainedPeers)
  *   - Missing file: returns empty set + debug log (not an error)
  *   - Malformed entry: throws with warning log (stops startup)
  *   - Empty file: returns empty set
  *
  * We follow the same file location convention as Besu and core-geth: ${datadir}/static-nodes.json
  * Entries are enode URLs: ["enode://pubkey@host:port", ...]
  * Each valid entry is added to PeerManagerActor.maintainedPeersByNodeId via AddMaintainedPeer.
  */
object StaticNodesLoader extends Logger {

  /** File name relative to the datadir. Matches Besu and core-geth convention. */
  val FileName: String = "static-nodes.json"

  /** Load static nodes from ${datadir}/static-nodes.json.
    *
    * @param datadir path to the node data directory
    * @return sequence of enode URIs; empty on missing file, malformed entries skipped with warning
    */
  def load(datadir: String): Seq[URI] = load(java.nio.file.Paths.get(datadir))

  def load(datadir: Path): Seq[URI] = {
    val filePath = datadir.resolve(FileName)
    if (!Files.exists(filePath)) {
      log.debug("Static nodes file {} does not exist — no static peers will be dialled", filePath)
      Seq.empty
    } else {
      Try(new String(Files.readAllBytes(filePath), "UTF-8")) match {
        case Failure(_) =>
          log.warn("Unable to read static nodes file {}", filePath)
          Seq.empty
        case Success(content) =>
          parseContent(content, filePath)
      }
    }
  }

  private def parseContent(content: String, filePath: Path): Seq[URI] = {
    val trimmed = content.trim
    if (trimmed.isEmpty || trimmed == "[]") {
      Seq.empty
    } else {
      Try(parse(trimmed)) match {
        case Failure(ex) =>
          log.warn("Static nodes file {} contains invalid JSON: {}", filePath, ex.getMessage)
          Seq.empty
        case Success(JArray(entries)) =>
          entries.flatMap(decodeEntry(_, filePath))
        case Success(_) =>
          log.warn("Static nodes file {} must contain a JSON array of enode URL strings", filePath)
          Seq.empty
      }
    }
  }

  private def decodeEntry(entry: JValue, filePath: Path): Option[URI] = entry match {
    case JString(url) =>
      Try(new URI(url)) match {
        case Failure(ex) =>
          log.warn("Skipping malformed enode URL '{}' in {}: {}", url, filePath, ex.getMessage)
          None
        case Success(uri) if isValidEnodeUri(uri) => Some(uri)
        case Success(uri) =>
          log.warn("Skipping invalid enode URL '{}' in {} — expected enode://pubkey@host:port", uri, filePath)
          None
      }
    case other =>
      log.warn("Skipping non-string entry {} in static nodes file {}", other, filePath)
      None
  }

  /** Validates that a URI is a well-formed enode URL with a non-empty pubkey and a valid port.
    *
    * Besu: StaticNodesParser.decodeString() calls checkArgument(enode.isListening(), "Static node must
    * be configured with a valid listening port.") — we do the equivalent check here.
    */
  private def isValidEnodeUri(uri: URI): Boolean =
    uri.getScheme == "enode" &&
      uri.getUserInfo != null && uri.getUserInfo.nonEmpty &&
      uri.getHost != null && uri.getHost.nonEmpty &&
      uri.getPort > 0
}
