package com.chipprbots.ethereum

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import com.chipprbots.ethereum.testing.Tags._

/** Integration tests for App launcher command line argument parsing. These tests verify that the launcher
  * correctly handles different argument combinations for network selection and modifiers.
  */
class AppLauncherIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

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
    // Simulate parsing "public" argument
    val modifiers = Array("public").filter { arg =>
      val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
      isModifierMethod.setAccessible(true)
      isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    }.toSet

    // Apply modifiers
    val applyModifiersMethod = App.getClass.getDeclaredMethod("applyModifiers", classOf[Set[String]])
    applyModifiersMethod.setAccessible(true)
    applyModifiersMethod.invoke(App, modifiers)

    // Verify discovery is enabled
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
  }

  it should "set discovery property when 'fukuii public etc' is used" taggedAs (IntegrationTest) in {
    val args = Array("public", "etc")
    
    // Extract modifiers
    val modifiers = args.filter { arg =>
      val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
      isModifierMethod.setAccessible(true)
      isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    }.toSet

    // Apply modifiers
    val applyModifiersMethod = App.getClass.getDeclaredMethod("applyModifiers", classOf[Set[String]])
    applyModifiersMethod.setAccessible(true)
    applyModifiersMethod.invoke(App, modifiers)

    // Verify discovery is enabled
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
    
    // Verify network argument is still present after filtering
    val argsWithoutModifiers = args.filterNot { arg =>
      val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
      isModifierMethod.setAccessible(true)
      isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    }
    argsWithoutModifiers should contain("etc")
  }

  it should "set discovery property when 'fukuii public mordor' is used" taggedAs (IntegrationTest) in {
    val args = Array("public", "mordor")
    
    // Extract modifiers
    val modifiers = args.filter { arg =>
      val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
      isModifierMethod.setAccessible(true)
      isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    }.toSet

    // Apply modifiers
    val applyModifiersMethod = App.getClass.getDeclaredMethod("applyModifiers", classOf[Set[String]])
    applyModifiersMethod.setAccessible(true)
    applyModifiersMethod.invoke(App, modifiers)

    // Verify discovery is enabled
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
    
    // Verify network argument is still present after filtering
    val argsWithoutModifiers = args.filterNot { arg =>
      val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
      isModifierMethod.setAccessible(true)
      isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    }
    argsWithoutModifiers should contain("mordor")
  }

  it should "preserve options when 'fukuii public --tui' is used" taggedAs (IntegrationTest) in {
    val args = Array("public", "--tui")
    
    // Extract modifiers
    val modifiers = args.filter { arg =>
      val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
      isModifierMethod.setAccessible(true)
      isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    }.toSet

    // Apply modifiers
    val applyModifiersMethod = App.getClass.getDeclaredMethod("applyModifiers", classOf[Set[String]])
    applyModifiersMethod.setAccessible(true)
    applyModifiersMethod.invoke(App, modifiers)

    // Verify discovery is enabled
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
    
    // Verify option flag is still present after filtering
    val argsWithoutModifiers = args.filterNot { arg =>
      val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
      isModifierMethod.setAccessible(true)
      isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    }
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
      
      // Extract modifiers
      val modifiers = args.filter { arg =>
        val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
        isModifierMethod.setAccessible(true)
        isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
      }.toSet

      // Apply modifiers
      val applyModifiersMethod = App.getClass.getDeclaredMethod("applyModifiers", classOf[Set[String]])
      applyModifiersMethod.setAccessible(true)
      applyModifiersMethod.invoke(App, modifiers)

      // All orderings should set discovery
      System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe "true"
      
      // All orderings should preserve non-modifier args
      val argsWithoutModifiers = args.filterNot { arg =>
        val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
        isModifierMethod.setAccessible(true)
        isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
      }
      argsWithoutModifiers should contain("etc")
      argsWithoutModifiers should contain("--tui")
    }
  }

  behavior.of("App launcher without 'public' modifier")

  it should "not set discovery property when 'fukuii etc' is used" taggedAs (IntegrationTest) in {
    val args = Array("etc")
    
    // Extract modifiers (should be empty)
    val modifiers = args.filter { arg =>
      val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
      isModifierMethod.setAccessible(true)
      isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    }.toSet

    // Apply modifiers
    val applyModifiersMethod = App.getClass.getDeclaredMethod("applyModifiers", classOf[Set[String]])
    applyModifiersMethod.setAccessible(true)
    applyModifiersMethod.invoke(App, modifiers)

    // Verify discovery property is not set
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe null
  }

  it should "not set discovery property when no args are provided" taggedAs (IntegrationTest) in {
    val args = Array.empty[String]
    
    // Extract modifiers (should be empty)
    val modifiers = args.filter { arg =>
      val isModifierMethod = App.getClass.getDeclaredMethod("isModifier", classOf[String])
      isModifierMethod.setAccessible(true)
      isModifierMethod.invoke(App, arg).asInstanceOf[Boolean]
    }.toSet

    // Apply modifiers
    val applyModifiersMethod = App.getClass.getDeclaredMethod("applyModifiers", classOf[Set[String]])
    applyModifiersMethod.setAccessible(true)
    applyModifiersMethod.invoke(App, modifiers)

    // Verify discovery property is not set
    System.getProperty("fukuii.network.discovery.discovery-enabled") shouldBe null
  }
}
