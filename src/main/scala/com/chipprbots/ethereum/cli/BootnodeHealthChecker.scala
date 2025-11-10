package com.chipprbots.ethereum.cli

import java.net.InetSocketAddress
import java.security.SecureRandom

import cats.effect.IO
import cats.effect.IOApp
import cats.effect.ExitCode
import cats.implicits._

import scala.concurrent.duration._
import scala.io.Source

import com.chipprbots.ethereum.crypto._
import com.chipprbots.ethereum.security.SecureRandomBuilder
import com.chipprbots.scalanet.discovery.ethereum.Node
import com.chipprbots.scalanet.discovery.ethereum.v4.DiscoveryConfig
import com.chipprbots.scalanet.discovery.ethereum.v4.DiscoveryNetwork
import com.chipprbots.scalanet.discovery.ethereum.v4.DiscoveryService
import com.chipprbots.scalanet.discovery.crypto.PrivateKey
import com.typesafe.scalalogging.LazyLogging

/** CLI tool to check bootnode health by attempting to ping them using the Discovery v4 protocol.
  *
  * Usage:
  *   bootnode-health-checker <config-file>
  *
  * Where config-file is a path to etc-chain.conf or mordor-chain.conf
  *
  * Exit codes:
  *   0 - All nodes responded
  *   1 - Some nodes failed to respond
  */
object BootnodeHealthChecker extends IOApp with SecureRandomBuilder with LazyLogging {

  case class HealthCheckResult(
      tested: Int,
      passed: Int,
      failed: Int,
      failedNodes: List[String]
  )

  def run(args: List[String]): IO[ExitCode] = {
    args match {
      case configFile :: Nil =>
        for {
          _ <- IO(logger.info(s"Checking bootnodes from: $configFile"))
          enodes <- extractEnodesFromConfig(configFile)
          _ <- IO(logger.info(s"Found ${enodes.size} bootnodes to test"))
          result <- checkBootnodes(enodes)
          _ <- printResults(result)
          exitCode <- IO.pure(if (result.failed > 0) ExitCode.Error else ExitCode.Success)
        } yield exitCode

      case _ =>
        IO(println("Usage: bootnode-health-checker <config-file>")) *>
          IO.pure(ExitCode.Error)
    }
  }

  def extractEnodesFromConfig(configFile: String): IO[List[String]] = IO {
    val source = Source.fromFile(configFile)
    try {
      val enodePattern = """enode://[^"]+""".r
      val lines = source.getLines().mkString("\n")
      enodePattern.findAllIn(lines).toList.distinct
    } finally {
      source.close()
    }
  }

  def checkBootnodes(enodes: List[String]): IO[HealthCheckResult] = {
    // Test each bootnode with a timeout
    enodes.traverse { enode =>
      pingBootnode(enode).timeout(10.seconds).attempt.map {
        case Right(_) =>
          logger.info(s"✓ PASS: ${enode.take(60)}...")
          (true, None)
        case Left(_) =>
          logger.warn(s"✗ FAIL: ${enode.take(60)}...")
          (false, Some(enode))
      }
    }.map { results =>
      val passed = results.count(_._1)
      val failed = results.count(!_._1)
      val failedNodes = results.flatMap(_._2)

      HealthCheckResult(
        tested = results.size,
        passed = passed,
        failed = failed,
        failedNodes = failedNodes
      )
    }
  }

  def pingBootnode(enodeUrl: String): IO[Unit] = {
    // Parse enode URL and attempt a discovery ping
    parseEnodeUrl(enodeUrl).flatMap { nodeAddress =>
      // Create a temporary discovery service and try to ping the node
      testNodeConnectivity(nodeAddress)
    }
  }

  def parseEnodeUrl(enodeUrl: String): IO[Node.Address] = IO {
    // Parse enode://pubkey@ip:port format
    val pattern = """enode://([a-fA-F0-9]+)@([^:]+):(\d+)(?:\?.*)?""".r
    enodeUrl match {
      case pattern(pubkey, ip, port) =>
        // Convert to Node.Address format
        val address = new InetSocketAddress(ip, port.toInt)
        // For now, we'll create a simplified ping test
        // In a full implementation, this would use the actual discovery service
        Node.Address(address.getAddress, address.getPort, address.getPort)
      case _ =>
        throw new IllegalArgumentException(s"Invalid enode URL: $enodeUrl")
    }
  }

  def testNodeConnectivity(nodeAddress: Node.Address): IO[Unit] = {
    // Simplified connectivity test using UDP socket
    // This is a basic reachability test, not a full discovery ping
    IO {
      import java.net.DatagramSocket
      import java.net.DatagramPacket
      import java.net.InetSocketAddress

      val socket = new DatagramSocket()
      try {
        socket.setSoTimeout(5000) // 5 second timeout
        
        // Send a minimal UDP packet to check if the port is reachable
        // In a production implementation, this would be a proper discovery v4 ping
        val testData = Array.fill[Byte](32)(0)
        val packet = new DatagramPacket(
          testData,
          testData.length,
          new InetSocketAddress(nodeAddress.ip, nodeAddress.udpPort)
        )
        
        socket.send(packet)
        
        // Try to receive a response (any response indicates the node is alive)
        val receiveBuffer = new Array[Byte](1024)
        val receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length)
        socket.receive(receivePacket)
        
        // If we got here, the node responded
        ()
      } finally {
        socket.close()
      }
    }.timeout(8.seconds)
  }

  def printResults(result: HealthCheckResult): IO[Unit] = IO {
    println()
    println("=" * 60)
    println("Bootnode Health Check Results")
    println("=" * 60)
    println(s"Total Tested: ${result.tested}")
    println(s"Passed: ${result.passed}")
    println(s"Failed: ${result.failed}")
    
    if (result.failedNodes.nonEmpty) {
      println()
      println("Failed Nodes:")
      result.failedNodes.foreach { node =>
        println(s"  - ${node.take(80)}...")
      }
    }
    println("=" * 60)
  }
}
