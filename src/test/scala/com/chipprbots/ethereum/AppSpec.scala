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

  private def isModifier(arg: String): Boolean =
    getIsModifierMethod.invoke(App, arg).asInstanceOf[Boolean]

  behavior.of("App argument parsing")

  it should "recognize 'public' as a modifier" taggedAs (UnitTest) in {
    getIsModifierMethod.invoke(App, "public").asInstanceOf[Boolean] shouldBe true
    getIsModifierMethod.invoke(App, "etc").asInstanceOf[Boolean] shouldBe false
    getIsModifierMethod.invoke(App, "--tui").asInstanceOf[Boolean] shouldBe false
  }

  it should "recognize network names" taggedAs (UnitTest) in {
    getIsNetworkMethod.invoke(App, "etc").asInstanceOf[Boolean] shouldBe true
    getIsNetworkMethod.invoke(App, "mordor").asInstanceOf[Boolean] shouldBe true
    getIsNetworkMethod.invoke(App, "pottery").asInstanceOf[Boolean] shouldBe true
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
    
    // Apply public modifier
    getApplyModifiersMethod.invoke(App, Set("public"))
    
    // Verify system property is set
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
    
    // Cleanup
    System.clearProperty("fukuii.network.discovery.discovery-enabled")
  }

  it should "not set discovery system property when no modifiers are present" taggedAs (UnitTest) in {
    // Clear any existing property
    System.clearProperty("fukuii.network.discovery.discovery-enabled")
    
    // Apply empty modifier set
    getApplyModifiersMethod.invoke(App, Set.empty[String])
    
    // Verify system property is not set
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe null
  }

  behavior.of("App argument filtering")

  it should "filter out modifiers from arguments" taggedAs (UnitTest) in {
    val args1 = Array("public", "etc", "--tui")
    val modifiers1 = args1.filter(isModifier)
    val filtered1 = args1.filterNot(isModifier)
    
    modifiers1 should contain("public")
    filtered1 should contain("etc")
    filtered1 should contain("--tui")
    filtered1 should not contain("public")
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
}
