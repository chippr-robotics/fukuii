package com.chipprbots.ethereum.nodebuilder

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** Which repairs a start makes of the datadir (spec 018 C4b part 4; B-251): the reconciliation on the unified layout,
  * the hash layout's own two repairs otherwise, and never both. The third case, a datadir stamped for the unified
  * layout opened with the guard off, is refused before either: `StorageBuilderStateGuardSpec`.
  */
class RepairsAtStartSpec extends AnyFlatSpec with Matchers:

  private def ran(holdsStateInPlace: Boolean): List[String] =
    var steps = List.empty[String]
    StdNode.repairsAtStart(holdsStateInPlace)(
      onUnified = () => steps :+= "reconcile",
      onHash = () => steps :+= "fixDatabase+repairBestBlockMapping"
    )
    steps

  "The repairs at start" should "reconcile the state on the unified layout, and make none of the hash layout's" taggedAs
    UnitTest in {
      ran(holdsStateInPlace = true) shouldBe List("reconcile")
    }

  it should "make the hash layout's own repairs on it, and no reconciliation" taggedAs UnitTest in {
    ran(holdsStateInPlace = false) shouldBe List("fixDatabase+repairBestBlockMapping")
  }
