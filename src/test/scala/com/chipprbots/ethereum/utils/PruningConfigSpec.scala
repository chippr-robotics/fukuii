package com.chipprbots.ethereum.utils

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.db.storage.pruning.ArchivePruning
import com.chipprbots.ethereum.db.storage.pruning.BasicPruning
import com.chipprbots.ethereum.testing.Tags.*

/** `pruning.mode` as the node reads it at start (#1692, spec 018 data-model 4.6): `basic` and `archive` stay, and the
  * retired `inmemory` and anything unknown stop the node before an actor starts.
  */
class PruningConfigSpec extends AnyFlatSpec with Matchers:

  private def pruning(mode: String): com.typesafe.config.Config =
    ConfigFactory.parseString(s"""pruning { mode = "$mode", history = 64 }""")

  "PruningConfig" should "read basic pruning with its history" taggedAs UnitTest in {
    PruningConfig(pruning("basic")).mode shouldBe BasicPruning(64)
  }

  it should "read archive pruning" taggedAs UnitTest in {
    PruningConfig(pruning("archive")).mode shouldBe ArchivePruning
  }

  it should "reject the retired inmemory mode, naming it and the two that remain" taggedAs UnitTest in {
    val error = the[IllegalArgumentException] thrownBy PruningConfig(pruning("inmemory"))
    error.getMessage should include("inmemory")
    error.getMessage should include("basic")
    error.getMessage should include("archive")
  }

  it should "reject a mode it does not know, rather than fail with a match error" taggedAs UnitTest in {
    val error = the[IllegalArgumentException] thrownBy PruningConfig(pruning("fast"))
    error.getMessage should include("fast")
    error.getMessage should include("basic")
    error.getMessage should include("archive")
  }
