package com.chipprbots.ethereum.utils

import com.typesafe.config.Config as TypesafeConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** B-246: the Engine API is on by default for exactly the chains that need a consensus client, which are the ones that
  * select `mining.protocol = "engine-api"`, and for no other chain.
  */
class EngineApiDefaultSpec extends AnyFlatSpec with Matchers:

  private val NeedConsensusClient: Seq[String] = Seq("eth", "sepolia", "hoodi", "ephemery", "plataberget")
  private val NoConsensusClient: Seq[String] = Seq("etc", "mordor", "gorgoroth")

  /** The configuration of `fukuii <network>`: the shipped conf/<network>.conf over the defaults. */
  private def shipped(name: String): TypesafeConfig =
    ConfigFactory.load(ConfigFactory.parseResources(s"conf/$name.conf"))

  "the confs of the chains that need a consensus client" should "enable the Engine API" taggedAs UnitTest in {
    NeedConsensusClient.foreach { name =>
      withClue(s"conf/$name.conf: ") {
        shipped(name).getBoolean("fukuii.network.engine-api.enabled") shouldBe true
      }
    }
  }

  it should "be exactly the confs that select the engine-api mining protocol" taggedAs UnitTest in {
    val all = NeedConsensusClient ++ NoConsensusClient
    all.filter(n => shipped(n).getString("fukuii.mining.protocol") == "engine-api") should contain theSameElementsAs
      NeedConsensusClient
  }

  it should "keep the Engine API on localhost:8551, with no secret path of its own" taggedAs UnitTest in {
    NeedConsensusClient.foreach { name =>
      withClue(s"conf/$name.conf: ") {
        val c = shipped(name).getConfig("fukuii.network.engine-api")
        c.getString("interface") shouldBe "localhost"
        c.getInt("port") shouldBe 8551
        // the file as shipped, read without the system properties other specs set
        ConfigFactory
          .parseResources(s"conf/$name.conf")
          .hasPath("fukuii.network.engine-api.jwt-secret-path") shouldBe false
      }
    }
  }

  "the confs of the chains that need none" should "leave the Engine API off" taggedAs UnitTest in {
    NoConsensusClient.foreach { name =>
      withClue(s"conf/$name.conf: ") {
        shipped(name).getBoolean("fukuii.network.engine-api.enabled") shouldBe false
      }
    }
  }
