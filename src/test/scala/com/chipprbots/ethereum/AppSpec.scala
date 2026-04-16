package com.chipprbots.ethereum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class AppSpec extends AnyFlatSpec with Matchers {

  // Helper methods to reduce reflection code duplication
  private def getIsModifierMethod = {
    val method = App.getClass.getDeclaredMethod("isModifier", classOf[String])
    method.setAccessible(true)
    method
  }

  private def getIsNetworkMethod = {
    val method = App.getClass.getDeclaredMethod("isNetwork", classOf[String])
    method.setAccessible(true)
    method
  }

  private def getIsOptionFlagMethod = {
    val method = App.getClass.getDeclaredMethod("isOptionFlag", classOf[String])
    method.setAccessible(true)
    method
  }

  private def getApplyModifiersMethod = {
    val method = App.getClass.getDeclaredMethod("applyModifiers", classOf[Set[String]])
    method.setAccessible(true)
    method
  }

  private def getDetermineNetworkArgMethod = {
    val method = App.getClass.getDeclaredMethod("determineNetworkArg", classOf[Array[String]])
    method.setAccessible(true)
    method
  }

  private def determineNetworkArg(args: Array[String]): Option[String] =
    getDetermineNetworkArgMethod
      .invoke(App, args.asInstanceOf[AnyRef])
      .asInstanceOf[Option[String]]

  private def isModifier(arg: String): Boolean =
    getIsModifierMethod.invoke(App, arg).asInstanceOf[Boolean]

  behavior.of("App argument parsing")

  it should "recognize 'public' as a modifier" taggedAs (UnitTest) in {
    getIsModifierMethod.invoke(App, "public").asInstanceOf[Boolean] shouldBe true
    getIsModifierMethod.invoke(App, "etc").asInstanceOf[Boolean] shouldBe false
    getIsModifierMethod.invoke(App, "--tui").asInstanceOf[Boolean] shouldBe false
  }

  it should "recognize 'enterprise' as a modifier" taggedAs (UnitTest) in {
    getIsModifierMethod.invoke(App, "enterprise").asInstanceOf[Boolean] shouldBe true
    getIsModifierMethod.invoke(App, "etc").asInstanceOf[Boolean] shouldBe false
    getIsModifierMethod.invoke(App, "--tui").asInstanceOf[Boolean] shouldBe false
  }

  it should "recognize network names" taggedAs (UnitTest) in {
    getIsNetworkMethod.invoke(App, "etc").asInstanceOf[Boolean] shouldBe true
    getIsNetworkMethod.invoke(App, "mordor").asInstanceOf[Boolean] shouldBe true
    getIsNetworkMethod.invoke(App, "gorgoroth").asInstanceOf[Boolean] shouldBe true
    getIsNetworkMethod.invoke(App, "public").asInstanceOf[Boolean] shouldBe false
    getIsNetworkMethod.invoke(App, "unknown").asInstanceOf[Boolean] shouldBe false
  }

  it should "recognize option flags" taggedAs (UnitTest) in {
    getIsOptionFlagMethod.invoke(App, "--tui").asInstanceOf[Boolean] shouldBe true
    getIsOptionFlagMethod.invoke(App, "--help").asInstanceOf[Boolean] shouldBe true
    getIsOptionFlagMethod.invoke(App, "-h").asInstanceOf[Boolean] shouldBe true
    getIsOptionFlagMethod.invoke(App, "etc").asInstanceOf[Boolean] shouldBe false
    getIsOptionFlagMethod.invoke(App, "public").asInstanceOf[Boolean] shouldBe false
  }

  behavior.of("App modifier application")

  it should "set discovery system property when 'public' modifier is present" taggedAs (UnitTest) in {
    // Clear any existing property
    System.clearProperty("fukuii.network.discovery.discovery-enabled")
    System.clearProperty("fukuii.network.discovery.use-bootstrap-nodes")

    // Apply public modifier
    getApplyModifiersMethod.invoke(App, Set("public"))

    // Verify system property is set
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
    System.getProperty("fukuii.network.discovery.use-bootstrap-nodes") shouldBe "true"

    // Cleanup
    System.clearProperty("fukuii.network.discovery.discovery-enabled")
    System.clearProperty("fukuii.network.discovery.use-bootstrap-nodes")
  }

  it should "not set discovery system property when no modifiers are present" taggedAs (UnitTest) in {
    // Clear any existing property
    System.clearProperty("fukuii.network.discovery.discovery-enabled")

    // Apply empty modifier set
    getApplyModifiersMethod.invoke(App, Set.empty[String])

    // Verify system property is not set
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe null
  }

  it should "disable discovery when 'enterprise' modifier is present" taggedAs (UnitTest) in {
    // Clear any existing properties
    System.clearProperty("fukuii.network.discovery.discovery-enabled")
    System.clearProperty("fukuii.network.automatic-port-forwarding")
    System.clearProperty("fukuii.network.discovery.reuse-known-nodes")
    System.clearProperty("fukuii.network.discovery.use-bootstrap-nodes")
    System.clearProperty("fukuii.sync.blacklist-duration")
    System.clearProperty("fukuii.network.rpc.http.interface")

    // Apply enterprise modifier
    getApplyModifiersMethod.invoke(App, Set("enterprise"))

    // Verify enterprise properties are set
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "false"
    System.getProperty("fukuii.network.automatic-port-forwarding") shouldBe "false"
    System.getProperty("fukuii.network.discovery.reuse-known-nodes") shouldBe "true"
    System.getProperty("fukuii.network.discovery.use-bootstrap-nodes") shouldBe "false"
    System.getProperty("fukuii.sync.blacklist-duration") shouldBe "0.seconds"
    System.getProperty("fukuii.network.rpc.http.interface") shouldBe "localhost"

    // Cleanup
    System.clearProperty("fukuii.network.discovery.discovery-enabled")
    System.clearProperty("fukuii.network.automatic-port-forwarding")
    System.clearProperty("fukuii.network.discovery.reuse-known-nodes")
    System.clearProperty("fukuii.network.discovery.use-bootstrap-nodes")
    System.clearProperty("fukuii.sync.blacklist-duration")
    System.clearProperty("fukuii.network.rpc.http.interface")
  }

  behavior.of("App argument filtering")

  it should "filter out modifiers from arguments" taggedAs (UnitTest) in {
    val args1 = Array("public", "etc", "--tui")
    val modifiers1 = args1.filter(isModifier)
    val filtered1 = args1.filterNot(isModifier)

    modifiers1 should contain("public")
    filtered1 should contain("etc")
    filtered1 should contain("--tui")
    filtered1 should not contain ("public")
  }

  it should "handle multiple 'public' keywords" taggedAs (UnitTest) in {
    val args = Array("public", "public", "etc")
    val modifiers = args.filter(isModifier)

    modifiers.toSet shouldBe Set("public")
  }

  it should "handle 'public' in any position" taggedAs (UnitTest) in {
    val args1 = Array("public", "etc")
    val args2 = Array("etc", "public")
    val args3 = Array("public", "--tui", "etc")

    args1.filter(isModifier).toSet shouldBe Set("public")
    args2.filter(isModifier).toSet shouldBe Set("public")
    args3.filter(isModifier).toSet shouldBe Set("public")
  }

  it should "handle 'enterprise' in any position" taggedAs (UnitTest) in {
    val args1 = Array("enterprise", "gorgoroth")
    val args2 = Array("gorgoroth", "enterprise")
    val args3 = Array("enterprise", "--tui", "gorgoroth")

    args1.filter(isModifier).toSet shouldBe Set("enterprise")
    args2.filter(isModifier).toSet shouldBe Set("enterprise")
    args3.filter(isModifier).toSet shouldBe Set("enterprise")
  }

  behavior.of("App network detection")

  it should "detect network argument after 'fukuii' command" taggedAs (UnitTest) in {
    val args = Array("fukuii", "gorgoroth")
    determineNetworkArg(args) should contain("gorgoroth")
  }

  it should "detect direct network argument" taggedAs (UnitTest) in {
    val args = Array("mordor")
    determineNetworkArg(args) should contain("mordor")
  }

  it should "ignore network when command is non-node" taggedAs (UnitTest) in {
    val args = Array("cli", "gorgoroth")
    determineNetworkArg(args) shouldBe empty
  }
}
