package com.chipprbots.ethereum.network.discovery

import java.io.File
import java.nio.file.Paths

import scala.io.Source
import scala.util.Try
import scala.util.Failure
import scala.util.Success

import org.json4s._
import org.json4s.native.JsonMethods._

import com.chipprbots.ethereum.utils.Logger

/** Utility to load static nodes from a static-nodes.json file
  *
  * The static-nodes.json file should be a JSON array of enode URLs:
  * [
  *   "enode://...",
  *   "enode://..."
  * ]
  */
object StaticNodesLoader extends Logger {

  /** Load static nodes from a file path
    *
    * @param filePath path to the static-nodes.json file
    * @return Set of enode URL strings, or empty set if file doesn't exist or is invalid
    */
  def loadFromFile(filePath: String): Set[String] = {
    val file = new File(filePath)
    
    if (!file.exists()) {
      log.debug(s"Static nodes file not found: $filePath")
      return Set.empty
    }

    if (!file.canRead()) {
      log.warn(s"Cannot read static nodes file: $filePath")
      return Set.empty
    }

    Try {
      val source = Source.fromFile(file)
      try {
        val content = source.mkString
        parseStaticNodes(content)
      } finally {
        source.close()
      }
    } match {
      case Success(nodes) =>
        if (nodes.nonEmpty) {
          log.info(s"Loaded ${nodes.size} static node(s) from $filePath")
        }
        nodes
      case Failure(exception) =>
        log.warn(s"Failed to load static nodes from $filePath: ${exception.getMessage}")
        Set.empty
    }
  }

  /** Load static nodes from the datadir
    *
    * Looks for static-nodes.json in the specified datadir
    *
    * @param datadir the data directory path
    * @return Set of enode URL strings, or empty set if file doesn't exist or is invalid
    */
  def loadFromDatadir(datadir: String): Set[String] = {
    val staticNodesPath = Paths.get(datadir, "static-nodes.json").toString
    loadFromFile(staticNodesPath)
  }

  /** Parse static nodes JSON content
    *
    * @param jsonContent JSON string content
    * @return Set of enode URL strings
    */
  private def parseStaticNodes(jsonContent: String): Set[String] = {
    parse(jsonContent) match {
      case JArray(values) =>
        values.collect {
          case JString(enode) if enode.startsWith("enode://") => enode
          case JString(enode) =>
            log.warn(s"Invalid enode URL (must start with 'enode://'): $enode")
            null
        }.filter(_ != null).toSet
      case _ =>
        log.warn(s"Invalid static-nodes.json format: expected JSON array")
        Set.empty
    }
  }
}
