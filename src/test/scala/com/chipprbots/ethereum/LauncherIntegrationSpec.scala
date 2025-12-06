package com.chipprbots.ethereum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

/** Integration tests for launcher configurations.
  *
  * These tests validate all supported launch configurations for Fukuii, ensuring that argument parsing and
  * configuration setup work correctly across different network and modifier combinations.
  *
  * This replaces the standalone bash script `test-launcher-integration.sh` and integrates launcher validation into the
  * automated CI/CD pipeline.
  *
  * @see
  *   test-launcher-integration.sh (deprecated)
  */
class LauncherIntegrationSpec extends AnyFlatSpec with Matchers {

  // Cached reflection methods for efficiency
  private lazy val isModifierMethod = {
    val method = App.getClass.getDeclaredMethod("isModifier", classOf[String])
    method.setAccessible(true)
    method
  }

  private lazy val isNetworkMethod = {
    val method = App.getClass.getDeclaredMethod("isNetwork", classOf[String])
    method.setAccessible(true)
    method
  }

  private lazy val isOptionFlagMethod = {
    val method = App.getClass.getDeclaredMethod("isOptionFlag", classOf[String])
    method.setAccessible(true)
    method
  }

  private lazy val applyModifiersMethod = {
    val method = App.getClass.getDeclaredMethod("applyModifiers", classOf[Set[String]])
    method.setAccessible(true)
    method
  }

  private lazy val setNetworkConfigMethod = {
    val method = App.getClass.getDeclaredMethod("setNetworkConfig", classOf[String])
    method.setAccessible(true)
    method
  }

  private def isModifier(arg: String): Boolean =
    isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]

  private def isNetwork(arg: String): Boolean =
    isNetworkMethod.invoke(App, arg).asInstanceOf[Boolean]

  private def isOptionFlag(arg: String): Boolean =
    isOptionFlagMethod.invoke(App, arg).asInstanceOf[Boolean]

  private def applyModifiers(modifiers: Set[String]): Unit =
    applyModifiersMethod.invoke(App, modifiers)

  private def setNetworkConfig(network: String): Unit =
    setNetworkConfigMethod.invoke(App, network)

  // Helper to clear system properties used by modifiers
  private def clearModifierProperties(): Unit = {
    System.clearProperty("fukuii.network.discovery.discovery-enabled")
    System.clearProperty("fukuii.network.automatic-port-forwarding")
    System.clearProperty("fukuii.network.discovery.reuse-known-nodes")
    System.clearProperty("fukuii.sync.blacklist-duration")
    System.clearProperty("fukuii.network.rpc.http.interface")
  }

  behavior.of("Basic launch configurations")

  it should "validate default ETC mainnet launch (no arguments)" taggedAs (UnitTest) in {
    // Default behavior: no arguments means ETC mainnet
    val args = Array.empty[String]
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)

    modifiers shouldBe empty
    networks shouldBe empty
  }

  it should "validate explicit ETC mainnet launch" taggedAs (UnitTest) in {
    val args = Array("etc")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)

    modifiers shouldBe empty
    networks should contain only "etc"
    isNetwork("etc") shouldBe true
  }

  it should "validate Mordor testnet launch" taggedAs (UnitTest) in {
    val args = Array("mordor")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)

    modifiers shouldBe empty
    networks should contain only "mordor"
    isNetwork("mordor") shouldBe true
  }

  it should "validate Pottery network launch" taggedAs (UnitTest) in {
    val args = Array("pottery")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)

    modifiers shouldBe empty
    networks should contain only "pottery"
    isNetwork("pottery") shouldBe true
  }

  it should "validate Sagano network launch" taggedAs (UnitTest) in {
    val args = Array("sagano")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)

    modifiers shouldBe empty
    networks should contain only "sagano"
    isNetwork("sagano") shouldBe true
  }

  it should "validate all known networks are recognized" taggedAs (UnitTest) in {
    val knownNetworks = Seq("etc", "eth", "mordor", "pottery", "sagano", "bootnode", "testnet-internal-nomad")
    
    knownNetworks.foreach { network =>
      withClue(s"Network '$network' should be recognized: ") {
        isNetwork(network) shouldBe true
      }
    }
  }

  behavior.of("Public discovery configurations")

  it should "validate public modifier alone (defaults to ETC)" taggedAs (UnitTest) in {
    clearModifierProperties()
    
    val args = Array("public")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)

    modifiers should contain only "public"
    networks shouldBe empty
    
    applyModifiers(modifiers)
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
    
    clearModifierProperties()
  }

  it should "validate public modifier with explicit ETC" taggedAs (UnitTest) in {
    clearModifierProperties()
    
    val args = Array("public", "etc")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)

    modifiers should contain only "public"
    networks should contain only "etc"
    
    applyModifiers(modifiers)
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
    
    clearModifierProperties()
  }

  it should "validate public modifier with Mordor testnet" taggedAs (UnitTest) in {
    clearModifierProperties()
    
    val args = Array("public", "mordor")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)

    modifiers should contain only "public"
    networks should contain only "mordor"
    
    applyModifiers(modifiers)
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
    
    clearModifierProperties()
  }

  it should "validate public modifier in different argument positions" taggedAs (UnitTest) in {
    clearModifierProperties()
    
    // Test different orderings
    val testCases = Seq(
      Array("public", "mordor"),
      Array("mordor", "public"),
      Array("public", "mordor", "--tui")
    )

    testCases.foreach { args =>
      val modifiers = args.filter(isModifier).toSet
      modifiers should contain("public")
    }
    
    clearModifierProperties()
  }

  behavior.of("Enterprise mode configurations")

  it should "validate enterprise modifier alone (defaults to ETC)" taggedAs (UnitTest) in {
    clearModifierProperties()
    
    val args = Array("enterprise")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)

    modifiers should contain only "enterprise"
    networks shouldBe empty
    
    applyModifiers(modifiers)
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "false"
    System.getProperty("fukuii.network.automatic-port-forwarding") shouldBe "false"
    System.getProperty("fukuii.network.discovery.reuse-known-nodes") shouldBe "true"
    System.getProperty("fukuii.sync.blacklist-duration") shouldBe "0.seconds"
    System.getProperty("fukuii.network.rpc.http.interface") shouldBe "localhost"
    
    clearModifierProperties()
  }

  it should "validate enterprise mode with explicit ETC" taggedAs (UnitTest) in {
    clearModifierProperties()
    
    val args = Array("enterprise", "etc")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)

    modifiers should contain only "enterprise"
    networks should contain only "etc"
    
    applyModifiers(modifiers)
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "false"
    
    clearModifierProperties()
  }

  it should "validate enterprise mode with Mordor testnet" taggedAs (UnitTest) in {
    clearModifierProperties()
    
    val args = Array("enterprise", "mordor")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)

    modifiers should contain only "enterprise"
    networks should contain only "mordor"
    
    clearModifierProperties()
  }

  it should "validate enterprise mode with Pottery network" taggedAs (UnitTest) in {
    clearModifierProperties()
    
    val args = Array("enterprise", "pottery")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)

    modifiers should contain only "enterprise"
    networks should contain only "pottery"
    
    applyModifiers(modifiers)
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "false"
    System.getProperty("fukuii.network.rpc.http.interface") shouldBe "localhost"
    
    clearModifierProperties()
  }

  behavior.of("Combined modifiers and options")

  it should "validate public modifier with ETC and TUI option" taggedAs (UnitTest) in {
    val args = Array("public", "etc", "--tui")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)
    val options = args.filter(isOptionFlag)

    modifiers should contain only "public"
    networks should contain only "etc"
    options should contain only "--tui"
  }

  it should "validate enterprise mode with pottery and TUI option" taggedAs (UnitTest) in {
    val args = Array("enterprise", "pottery", "--tui")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)
    val options = args.filter(isOptionFlag)

    modifiers should contain only "enterprise"
    networks should contain only "pottery"
    options should contain only "--tui"
  }

  it should "validate public mode with force-pivot-sync option" taggedAs (UnitTest) in {
    val args = Array("public", "--force-pivot-sync")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)
    val options = args.filter(isOptionFlag)

    modifiers should contain only "public"
    networks shouldBe empty
    options should contain only "--force-pivot-sync"
  }

  it should "validate enterprise mode with custom config flag" taggedAs (UnitTest) in {
    val args = Array("enterprise", "-Dconfig.file=/custom.conf")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)
    val options = args.filter(isOptionFlag)

    modifiers should contain only "enterprise"
    networks shouldBe empty
    options should contain only "-Dconfig.file=/custom.conf"
  }

  behavior.of("Argument filtering and parsing")

  it should "correctly filter modifiers from mixed arguments" taggedAs (UnitTest) in {
    val args = Array("public", "etc", "--tui", "enterprise")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)
    val options = args.filter(isOptionFlag)

    modifiers should contain allOf("public", "enterprise")
    networks should contain only "etc"
    options should contain only "--tui"
  }

  it should "correctly parse arguments with network first" taggedAs (UnitTest) in {
    val args = Array("mordor", "public", "--tui")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)
    val options = args.filter(isOptionFlag)

    modifiers should contain only "public"
    networks should contain only "mordor"
    options should contain only "--tui"
  }

  it should "correctly parse arguments with options first" taggedAs (UnitTest) in {
    val args = Array("--tui", "public", "mordor")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)
    val options = args.filter(isOptionFlag)

    modifiers should contain only "public"
    networks should contain only "mordor"
    options should contain only "--tui"
  }

  it should "handle multiple option flags" taggedAs (UnitTest) in {
    val args = Array("public", "etc", "--tui", "--force-pivot-sync")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)
    val options = args.filter(isOptionFlag)

    modifiers should contain only "public"
    networks should contain only "etc"
    options should contain allOf("--tui", "--force-pivot-sync")
  }

  behavior.of("Network configuration")

  it should "set network config for known networks" taggedAs (UnitTest) in {
    // This test validates that setNetworkConfig can be called without errors
    // Actual config file existence is not validated as it depends on the environment
    noException should be thrownBy {
      setNetworkConfig("etc")
      setNetworkConfig("mordor")
      setNetworkConfig("pottery")
    }
  }

  behavior.of("Enterprise mode features validation")

  it should "verify all enterprise mode properties are set correctly" taggedAs (UnitTest) in {
    clearModifierProperties()
    
    applyModifiers(Set("enterprise"))
    
    // Verify all enterprise mode features are enabled
    withClue("Public discovery should be disabled: ") {
      System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "false"
    }
    
    withClue("Automatic port forwarding should be disabled: ") {
      System.getProperty("fukuii.network.automatic-port-forwarding") shouldBe "false"
    }
    
    withClue("Known nodes should be reused: ") {
      System.getProperty("fukuii.network.discovery.reuse-known-nodes") shouldBe "true"
    }
    
    withClue("Peer blacklisting should be disabled: ") {
      System.getProperty("fukuii.sync.blacklist-duration") shouldBe "0.seconds"
    }
    
    withClue("RPC should be bound to localhost: ") {
      System.getProperty("fukuii.network.rpc.http.interface") shouldBe "localhost"
    }
    
    clearModifierProperties()
  }

  behavior.of("Modifier validation")

  it should "recognize all valid modifiers" taggedAs (UnitTest) in {
    val validModifiers = Seq("public", "enterprise")
    
    validModifiers.foreach { modifier =>
      withClue(s"Modifier '$modifier' should be recognized: ") {
        isModifier(modifier) shouldBe true
      }
    }
  }

  it should "reject invalid modifiers" taggedAs (UnitTest) in {
    val invalidModifiers = Seq("invalid", "test", "production", "staging", "etc", "--tui")
    
    invalidModifiers.foreach { modifier =>
      withClue(s"'$modifier' should not be recognized as a modifier: ") {
        isModifier(modifier) shouldBe false
      }
    }
  }

  behavior.of("Option flag validation")

  it should "recognize common option flags" taggedAs (UnitTest) in {
    val validOptions = Seq("--tui", "--help", "-h", "--force-pivot-sync", "-Dconfig.file=/path")
    
    validOptions.foreach { option =>
      withClue(s"Option '$option' should be recognized: ") {
        isOptionFlag(option) shouldBe true
      }
    }
  }

  it should "reject non-option arguments" taggedAs (UnitTest) in {
    val nonOptions = Seq("etc", "mordor", "public", "enterprise")
    
    nonOptions.foreach { arg =>
      withClue(s"'$arg' should not be recognized as an option flag: ") {
        isOptionFlag(arg) shouldBe false
      }
    }
  }

  behavior.of("Complex launch scenarios")

  it should "validate public discovery on Mordor with TUI" taggedAs (UnitTest) in {
    clearModifierProperties()
    
    val args = Array("public", "mordor", "--tui")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)
    val options = args.filter(isOptionFlag)

    modifiers should contain only "public"
    networks should contain only "mordor"
    options should contain only "--tui"
    
    applyModifiers(modifiers)
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
    
    clearModifierProperties()
  }

  it should "validate enterprise mode on pottery with custom config" taggedAs (UnitTest) in {
    clearModifierProperties()
    
    val args = Array("enterprise", "pottery", "-Dconfig.file=/custom.conf")
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)
    val options = args.filter(isOptionFlag)

    modifiers should contain only "enterprise"
    networks should contain only "pottery"
    options should contain only "-Dconfig.file=/custom.conf"
    
    applyModifiers(modifiers)
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "false"
    System.getProperty("fukuii.network.rpc.http.interface") shouldBe "localhost"
    
    clearModifierProperties()
  }

  behavior.of("Edge cases")

  it should "handle empty arguments" taggedAs (UnitTest) in {
    val args = Array.empty[String]
    val modifiers = args.filter(isModifier).toSet
    val networks = args.filter(isNetwork)
    val options = args.filter(isOptionFlag)

    modifiers shouldBe empty
    networks shouldBe empty
    options shouldBe empty
  }

  it should "handle duplicate modifiers gracefully" taggedAs (UnitTest) in {
    val args = Array("public", "public", "etc")
    val modifiers = args.filter(isModifier).toSet
    
    // Set should deduplicate
    modifiers should contain only "public"
  }

  it should "handle conflicting modifiers" taggedAs (UnitTest) in {
    clearModifierProperties()
    
    // Both public and enterprise modifiers - when multiple modifiers are present,
    // they are all applied in the order they appear in the Set iteration.
    // Since enterprise mode is applied after public (in applyModifiers), it will
    // override the public discovery setting.
    val args = Array("public", "enterprise", "etc")
    val modifiers = args.filter(isModifier).toSet
    
    modifiers should contain allOf("public", "enterprise")
    
    applyModifiers(modifiers)
    // Enterprise sets discovery to false, which overrides public's true
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "false"
    
    clearModifierProperties()
  }
}
