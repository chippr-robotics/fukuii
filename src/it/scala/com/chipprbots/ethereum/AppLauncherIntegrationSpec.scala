package com.chipprbots.ethereum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import com.chipprbots.ethereum.testing.Tags._

/** Integration tests for App launcher command line argument parsing. These tests verify that the launcher
  * correctly handles different argument combinations for network selection and modifiers.
  */
class AppLauncherIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  // Helper methods to reduce reflection code duplication
  private def getIsModifierMethod = {
    val method = App.getClass.getDeclaredMethod("isModifier", classOf[String])
    method.setAccessible(true)
    method
  }

  private def getApplyModifiersMethod = {
    val method = App.getClass.getDeclaredMethod("applyModifiers", classOf[Set[String]])
    method.setAccessible(true)
    method
  }

  private def extractModifiers(args: Array[String]): Set[String] = {
    args.filter { arg =>
      getIsModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    }.toSet
  }

  private def applyModifiers(modifiers: Set[String]): Unit = {
    getApplyModifiersMethod.invoke(App, modifiers)
  }

  private def filterOutModifiers(args: Array[String]): Array[String] = {
    args.filterNot { arg =>
      getIsModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    }
  }

  override def beforeEach(): Unit = {
    // Clear system properties before each test
    System.clearProperty("fukuii.network.discovery.discovery-enabled")
    System.clearProperty("config.file")
  }

  override def afterEach(): Unit = {
    // Clean up system properties after each test
    System.clearProperty("fukuii.network.discovery.discovery-enabled")
    System.clearProperty("config.file")
  }

  behavior.of("App launcher with 'public' modifier")

  it should "set discovery property when 'fukuii public' is used" taggedAs (IntegrationTest) in {
    val modifiers = extractModifiers(Array("public"))
    applyModifiers(modifiers)

    // Verify discovery is enabled
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
  }

  it should "set discovery property when 'fukuii public etc' is used" taggedAs (IntegrationTest) in {
    val args = Array("public", "etc")
    val modifiers = extractModifiers(args)
    applyModifiers(modifiers)

    // Verify discovery is enabled
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
    
    // Verify network argument is still present after filtering
    val argsWithoutModifiers = filterOutModifiers(args)
    argsWithoutModifiers should contain("etc")
  }

  it should "set discovery property when 'fukuii public mordor' is used" taggedAs (IntegrationTest) in {
    val args = Array("public", "mordor")
    val modifiers = extractModifiers(args)
    applyModifiers(modifiers)

    // Verify discovery is enabled
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
    
    // Verify network argument is still present after filtering
    val argsWithoutModifiers = filterOutModifiers(args)
    argsWithoutModifiers should contain("mordor")
  }

  it should "preserve options when 'fukuii public --tui' is used" taggedAs (IntegrationTest) in {
    val args = Array("public", "--tui")
    val modifiers = extractModifiers(args)
    applyModifiers(modifiers)

    // Verify discovery is enabled
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
    
    // Verify option flag is still present after filtering
    val argsWithoutModifiers = filterOutModifiers(args)
    argsWithoutModifiers should contain("--tui")
  }

  it should "handle arguments in any order" taggedAs (IntegrationTest) in {
    // Test various orderings
    val orderings = Seq(
      Array("public", "etc", "--tui"),
      Array("etc", "public", "--tui"),
      Array("public", "--tui", "etc")
    )

    orderings.foreach { args =>
      // Clear properties
      System.clearProperty("fukuii.network.discovery.discovery-enabled")
      
      val modifiers = extractModifiers(args)
      applyModifiers(modifiers)

      // All orderings should set discovery
      System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
      
      // All orderings should preserve non-modifier args
      val argsWithoutModifiers = filterOutModifiers(args)
      argsWithoutModifiers should contain("etc")
      argsWithoutModifiers should contain("--tui")
    }
  }

  behavior.of("App launcher without 'public' modifier")

  it should "not set discovery property when 'fukuii etc' is used" taggedAs (IntegrationTest) in {
    val modifiers = extractModifiers(Array("etc"))
    applyModifiers(modifiers)

    // Verify discovery property is not set
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe null
  }

  it should "not set discovery property when no args are provided" taggedAs (IntegrationTest) in {
    val modifiers = extractModifiers(Array.empty[String])
    applyModifiers(modifiers)

    // Verify discovery property is not set
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe null
  }
}
