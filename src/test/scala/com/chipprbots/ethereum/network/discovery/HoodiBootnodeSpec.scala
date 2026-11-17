package com.chipprbots.ethereum.network.discovery

import java.net.URI

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.utils.Config

/** The pin for spec 028 R15's open bootnode question.
  *
  * eth-clients/hoodi `enodes.yaml` lists the EF NodeOps bootnodes as `enode://...@ip:0?discport=N`: TCP port 0,
  * discovery on N. This spec pins what fukuii does with such an entry, and that decides the shipped list:
  *   - it is accepted by the parser, with TCP 0 and UDP N, so discovery would ping UDP N (DiscoveryServiceBuilder
  *     enrols `knownPeers` with `udpPort = node.udpPort`);
  *   - it is ALSO handed to the dialler: `PeerDiscoveryManager` serves every bootstrap node in `DiscoveredNodesInfo`,
  *     and `PeerManagerActor` (`canConnectTo`, then `ConnectToPeerCmd(n.toUri)`) filters on nothing about the port, so
  *     the connection URI it dials carries port 0, which can never connect.
  *
  * Result: ship only the `:30303` enodes (go-ethereum's three legacy EF enodes plus Lodestar) and leave the NodeOps
  * five to DNS discovery (`all.hoodi.ethdisco.net`), which skips ENRs without a TCP port (`DnsDiscovery.scala:515`).
  */
class HoodiBootnodeSpec extends AnyFlatSpec with Matchers:

  private val NodeOps =
    "enode://70bab91175f9bbcebbfbf155644f46c03ed44ef4927d816e3601ba5fd32f7a240ffb8953e737dd8595cf3773a62230ca27dd5390d268570f980f7d47a40dd1a3@212.99.218.66:0?discport=20153"

  "Node.fromUri" should "give a NodeOps enode TCP port 0 and the discport as UDP" taggedAs (UnitTest, NetworkTest) in {
    val node = Node.fromUri(new URI(NodeOps))
    node.tcpPort shouldBe 0
    node.udpPort shouldBe 20153
  }

  "a TCP-0 bootstrap node" should "be kept by DiscoveryConfig and handed to the dialler with port 0" taggedAs (
    UnitTest,
    NetworkTest
  ) in {
    val config = DiscoveryConfig(Config.config, Set(NodeOps), Seq.empty, useBootstrapNodes = true)

    val node = config.bootstrapNodes.headOption.getOrElse(fail("the TCP-0 enode was dropped by the parser"))
    node.udpSocketAddress.getPort shouldBe 20153 // where discovery pings
    node.tcpSocketAddress.getPort shouldBe 0 // what canConnectTo and the dialler use
    node.toUri.getPort shouldBe 0 // the URI PeerManagerActor dials via ConnectToPeerCmd
    node.toUri.getQuery shouldBe "discport=20153"
  }

  "the shipped hoodi bootstrap list" should "hold no TCP-0 enode" taggedAs (UnitTest, NetworkTest) in {
    val shipped = Config.blockchains.blockchains("hoodi").bootstrapNodes
    shipped should not be empty
    shipped.foreach { enode =>
      withClue(enode)(Node.fromUri(new URI(enode)).tcpPort should be > 0)
    }
  }
