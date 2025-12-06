package com.chipprbots.ethereum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags._

class AppSpec extends AnyFlatSpec with Matchers {

  behavior.of("App argument parsing")

  it should "recognize 'public' as a modifier" taggedAs (UnitTest) in {
    // Access private method via reflection for testing
    val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
    isModifierMethod.setAccessible(true)
    
    isModifierMethod.invoke(App, "public").asInstanceOf[Boolean] shouldBe true
    isModifierMethod.invoke(App, "etc").asInstanceOf[Boolean] shouldBe false
    isModifierMethod.invoke(App, "--tui").asInstanceOf[Boolean] shouldBe false
  }

  it should "recognize network names" taggedAs (UnitTest) in {
    val isNetworkMethod = App.getClass.getDeclaredMethod("isNetwork", classOf[String])
    isNetworkMethod.setAccessible(true)
    
    isNetworkMethod.invoke(App, "etc").asInstanceOf[Boolean] shouldBe true
    isNetworkMethod.invoke(App, "mordor").asInstanceOf[Boolean] shouldBe true
    isNetworkMethod.invoke(App, "pottery").asInstanceOf[Boolean] shouldBe true
    isNetworkMethod.invoke(App, "public").asInstanceOf[Boolean] shouldBe false
    isNetworkMethod.invoke(App, "unknown").asInstanceOf[Boolean] shouldBe false
  }

  it should "recognize option flags" taggedAs (UnitTest) in {
    val isOptionFlagMethod = App.getClass.getDeclaredMethod("isOptionFlag", classOf[String])
    isOptionFlagMethod.setAccessible(true)
    
    isOptionFlagMethod.invoke(App, "--tui").asInstanceOf[Boolean] shouldBe true
    isOptionFlagMethod.invoke(App, "--help").asInstanceOf[Boolean] shouldBe true
    isOptionFlagMethod.invoke(App, "-h").asInstanceOf[Boolean] shouldBe true
    isOptionFlagMethod.invoke(App, "etc").asInstanceOf[Boolean] shouldBe false
    isOptionFlagMethod.invoke(App, "public").asInstanceOf[Boolean] shouldBe false
  }

  behavior.of("App modifier application")

  it should "set discovery system property when 'public' modifier is present" taggedAs (UnitTest) in {
    val applyModifiersMethod = App.getClass.getDeclaredMethod("applyModifiers", classOf[Set[String]])
    applyModifiersMethod.setAccessible(true)
    
    // Clear any existing property
    System.clearProperty("fukuii.network.discovery.discovery-enabled")
    
    // Apply public modifier
    applyModifiersMethod.invoke(App, Set("public"))
    
    // Verify system property is set
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
    
    // Cleanup
    System.clearProperty("fukuii.network.discovery.discovery-enabled")
  }

  it should "not set discovery system property when no modifiers are present" taggedAs (UnitTest) in {
    val applyModifiersMethod = App.getClass.getDeclaredMethod("applyModifiers", classOf[Set[String]])
    applyModifiersMethod.setAccessible(true)
    
    // Clear any existing property
    System.clearProperty("fukuii.network.discovery.discovery-enabled")
    
    // Apply empty modifier set
    applyModifiersMethod.invoke(App, Set.empty[String])
    
    // Verify system property is not set
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe null
  }

  behavior.of("App argument filtering")

  it should "filter out modifiers from arguments" taggedAs (UnitTest) in {
    val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
    isModifierMethod.setAccessible(true)
    
    val isModifier = (arg: String) => isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    
    val args1 = Array("public", "etc", "--tui")
    val modifiers1 = args1.filter(isModifier)
    val filtered1 = args1.filterNot(isModifier)
    
    modifiers1 should contain("public")
    filtered1 should contain("etc")
    filtered1 should contain("--tui")
    filtered1 should not contain("public")
  }

  it should "handle multiple 'public' keywords" taggedAs (UnitTest) in {
    val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
    isModifierMethod.setAccessible(true)
    
    val isModifier = (arg: String) => isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    
    val args = Array("public", "public", "etc")
    val modifiers = args.filter(isModifier)
    
    modifiers.toSet shouldBe Set("public")
  }

  it should "handle 'public' in any position" taggedAs (UnitTest) in {
    val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
    isModifierMethod.setAccessible(true)
    
    val isModifier = (arg: String) => isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    
    val args1 = Array("public", "etc")
    val args2 = Array("etc", "public")
    val args3 = Array("public", "--tui", "etc")
    
    args1.filter(isModifier).toSet shouldBe Set("public")
    args2.filter(isModifier).toSet shouldBe Set("public")
    args3.filter(isModifier).toSet shouldBe Set("public")
  }
}
