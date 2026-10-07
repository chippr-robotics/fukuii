package com.chipprbots.ethereum.nodebuilder

import scala.io.Source

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*

/** The RPC server bind order must follow the port the supervisor probes for readiness.
  *
  * hive probes 8545 by default and 8551 on the `ethereum/sync` sink node. Whichever it probes has to bind last, so a
  * successful probe implies the other port is already listening. Measured on 14acbe402: with 8551 probed and bound
  * first, `sync fukuii from fukuii` failed on `connection refused` at 8545, ~10 ms before 8545 bound.
  */
class RpcBindOrderSpec extends AnyFlatSpec with Matchers:

  "StdNode.engineApiBindsLast" should "bind the Engine API last only when it is the probed port" taggedAs UnitTest in {
    StdNode.engineApiBindsLast(Some(8551), 8551) shouldBe true
    StdNode.engineApiBindsLast(Some(8545), 8551) shouldBe false
  }

  it should "keep the default order (Engine API first) when no readiness port is configured" taggedAs UnitTest in {
    StdNode.engineApiBindsLast(None, 8551) shouldBe false
  }

  it should "follow a non-default Engine API port" taggedAs UnitTest in {
    StdNode.engineApiBindsLast(Some(9551), 9551) shouldBe true
    StdNode.engineApiBindsLast(Some(8551), 9551) shouldBe false
  }

  "hive/fukuii/fukuii.sh" should "pass hive's readiness port through to fukuii" taggedAs UnitTest in {
    val src = Source.fromFile("hive/fukuii/fukuii.sh")
    val script =
      try src.mkString
      finally src.close()
    script should include("-Dfukuii.network.readiness-port=${HIVE_CHECK_LIVE_PORT:-8545}")
  }
