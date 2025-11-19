package com.chipprbots.ethereum.utils

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.chipprbots.ethereum.testing.Tags._

class ConfigSpec extends AnyFlatSpec with Matchers {
  "clientId" should "by default come from VersionInfo" taggedAs (UnitTest) in {
    Config.clientId shouldBe VersionInfo.nodeName()
  }
}
