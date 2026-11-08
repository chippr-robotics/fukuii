package com.chipprbots.ethereum.jsonrpc.server.controllers

import java.nio.file.Files
import java.nio.file.Paths

import scala.jdk.CollectionConverters.*

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.ShippedRpcConfig
import com.chipprbots.ethereum.jsonrpc.server.controllers.JsonRpcBaseController.JsonRpcConfig
import com.chipprbots.ethereum.testing.Tags.*

/** Which JSON-RPC namespaces each transport serves: the defaults that ship in `conf/base/network.conf`, and how the
  * per-transport keys (`network.rpc.http.apis`, `ws.apis`, `ipc.apis`) and the shared `network.rpc.apis` combine. The
  * defaults are go-ethereum's: HTTP and WebSocket serve only the standard chain namespaces (eth, net, web3) and fukuii,
  * IPC serves every production namespace.
  */
class RpcApisConfigSpec extends AnyFlatSpec with Matchers:

  private def apisOf(overrides: String = ""): JsonRpcConfig = ShippedRpcConfig.jsonRpcConfig(overrides)

  private val ChainApis = Seq("eth", "net", "web3", "fukuii")

  "The shipped config" should "set none of the four apis keys, so that a key that is set is the operator's" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val shipped = ShippedRpcConfig.config()
    Seq("apis", "http.apis", "ws.apis", "ipc.apis").foreach { key =>
      withClue(s"fukuii.network.rpc.$key: ")(shipped.hasPath(s"fukuii.network.rpc.$key") shouldBe false)
    }
  }

  it should "set none of the four apis keys in any other shipped conf file, except the templates that need them" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // faucet.conf serves its own `faucet` namespace, testmode.conf needs `test`, and enterprise-template.conf is a
    // template an operator copies; every other file must leave the keys to the operator
    val exceptions = Set("faucet.conf", "testmode.conf", "enterprise-template.conf")
    val confDir = Paths.get(getClass.getClassLoader.getResource("conf/app.conf").toURI).getParent
    val files = Files
      .walk(confDir)
      .iterator
      .asScala
      .filter(_.getFileName.toString.endsWith(".conf"))
      .filterNot(file => exceptions.contains(file.getFileName.toString))
      .toList
    files should not be empty

    files.foreach { file =>
      // Each file's own text, with its include directives dropped (also the inline `etc {include required(...)}`):
      // `include classpath("application.conf")` would pull in whatever application.conf is on this test's classpath,
      // which sets the keys for the controller specs.
      val own = Files.readString(file).replaceAll("""(?m)\binclude\s+[^\n}]*""", "")
      val parsed = ConfigFactory.parseString(own) // parsed only: nothing is resolved
      Seq("apis", "http.apis", "ws.apis", "ipc.apis").foreach { key =>
        withClue(s"${file.getFileName} fukuii.network.rpc.$key: ")(
          parsed.hasPath(s"fukuii.network.rpc.$key") shouldBe false
        )
      }
    }
  }

  it should "serve only eth, net, web3 and fukuii over HTTP and WebSocket" taggedAs (UnitTest, RPCTest) in {
    val config = apisOf()
    config.httpApis shouldBe ChainApis
    config.wsApis shouldBe ChainApis
    config.apis shouldBe ChainApis

    val optIn = Seq("personal", "debug", "admin", "qa", "txpool", "trace", "test", "testing", "mcp", "miner")
    optIn.foreach { namespace =>
      withClue(s"$namespace: ") {
        config.httpApis should not contain namespace
        config.wsApis should not contain namespace
      }
    }
  }

  it should "serve every production namespace over IPC, but not the dev-mode, test-mode and opt-in-feature ones" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val ipc = apisOf().ipcApis
    ipc shouldBe Seq("eth", "net", "web3", "fukuii", "personal", "debug", "admin", "miner", "txpool", "trace")
    ipc shouldBe JsonRpcConfig.DefaultIpcApis
    Seq("qa", "test", "testing", "mcp", "rpc").foreach(namespace =>
      withClue(s"$namespace: ")(ipc should not contain namespace)
    )
  }

  "network.rpc.apis" should "apply to all three transports when it is set" taggedAs (UnitTest, RPCTest) in {
    val config = apisOf("""fukuii.network.rpc.apis = "eth,net"""")
    config.apis shouldBe Seq("eth", "net")
    config.httpApis shouldBe Seq("eth", "net")
    config.wsApis shouldBe Seq("eth", "net")
    config.ipcApis shouldBe Seq("eth", "net")
  }

  it should "bring back the previous wide default, with the namespaces that moved, for an operator who wants it" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    // the old default plus `miner` (the mining control methods moved out of eth); `admin` also carries the non-standard
    // net_ peer methods (net_listPeers, net_connectToPeer, ...)
    val wide = Seq("eth", "web3", "net", "personal", "fukuii", "debug", "qa", "admin", "miner")
    val config = apisOf("""fukuii.network.rpc.apis = "eth,web3,net,personal,fukuii,debug,qa,admin,miner"""")
    config.httpApis shouldBe wide
    config.wsApis shouldBe wide
    config.ipcApis shouldBe wide
  }

  "A per-transport key" should "win over network.rpc.apis for its own transport only" taggedAs (UnitTest, RPCTest) in {
    val config = apisOf("""
      fukuii.network.rpc.apis = "eth"
      fukuii.network.rpc.http.apis = "eth,web3"
      fukuii.network.rpc.ipc.apis = "eth,admin"
    """)
    config.apis shouldBe Seq("eth")
    config.httpApis shouldBe Seq("eth", "web3")
    config.wsApis shouldBe Seq("eth") // no ws key: the shared set
    config.ipcApis shouldBe Seq("eth", "admin")
  }

  it should "leave the other transports on their defaults when network.rpc.apis is not set" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    val config = apisOf("""fukuii.network.rpc.http.apis = "eth,net,web3,personal"""")
    config.httpApis shouldBe Seq("eth", "net", "web3", "personal")
    config.wsApis shouldBe ChainApis
    config.ipcApis shouldBe JsonRpcConfig.DefaultIpcApis
    config.apis shouldBe ChainApis // what the Engine API's eth_, net_ and web3_ pass-through serves
  }

  it should "let one transport narrow below its default" taggedAs (UnitTest, RPCTest) in {
    val config = apisOf("""fukuii.network.rpc.ipc.apis = "eth,net"""")
    config.ipcApis shouldBe Seq("eth", "net")
    config.httpApis shouldBe ChainApis
  }

  "An apis value" should "be a comma-separated string or a list, ignoring case, blanks and repeats" taggedAs (
    UnitTest,
    RPCTest
  ) in {
    apisOf("""fukuii.network.rpc.ws.apis = " ETH , net,, web3 , eth """").wsApis shouldBe Seq("eth", "net", "web3")
    apisOf("""fukuii.network.rpc.ws.apis = ["ETH", " Net ", "web3"]""").wsApis shouldBe Seq("eth", "net", "web3")
  }

  it should "also read numbered keys, as -Dfukuii.network.rpc.apis.0=eth sets them" taggedAs (UnitTest, RPCTest) in {
    val config = apisOf("""
      fukuii.network.rpc.apis.0 = eth
      fukuii.network.rpc.apis.1 = web3
    """)
    config.apis shouldBe Seq("eth", "web3")
    config.httpApis shouldBe Seq("eth", "web3")
  }

  it should "be allowed to be empty, which serves no namespace but rpc" taggedAs (UnitTest, RPCTest) in {
    val config = apisOf("fukuii.network.rpc.http.apis = \"\"")
    config.httpApis shouldBe Seq.empty
    config.wsApis shouldBe ChainApis
  }

  it should "be refused at startup when it names an unknown namespace, naming the key" taggedAs (UnitTest, RPCTest) in {
    Seq("apis", "http.apis", "ws.apis", "ipc.apis").foreach { key =>
      val error = intercept[IllegalArgumentException] {
        apisOf(s"""fukuii.network.rpc.$key = "eth,nosuch"""")
      }
      withClue(key) {
        error.getMessage should include(s"network.rpc.$key")
        error.getMessage should include("nosuch")
      }
    }
  }

  "The defaults" should "be limited to the namespaces a node can serve" taggedAs (UnitTest, RPCTest) in {
    // a controller with fewer namespaces (the faucet serves only `faucet`) gets no default it cannot serve
    val faucetOnly = List("faucet")
    val shipped = ShippedRpcConfig.config().getConfig("fukuii")
    val bare = JsonRpcConfig(shipped, faucetOnly)
    bare.httpApis shouldBe Seq.empty
    bare.ipcApis shouldBe Seq.empty

    val configured = JsonRpcConfig(
      ShippedRpcConfig.config("""fukuii.network.rpc.apis = "faucet"""").getConfig("fukuii"),
      faucetOnly
    )
    configured.httpApis shouldBe Seq("faucet")
    configured.ipcApis shouldBe Seq("faucet")
  }
