package com.chipprbots.ethereum.utils

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.chipprbots.ethereum.testing.Tags._

class VersionInfoSpec extends AnyFlatSpec with Matchers {
  behavior.of("nodeName")

  it should "match ethstats expected structure and preserve major and minor Java version" taggedAs (UnitTest) in {
    (VersionInfo
      .nodeName() should fullyMatch)
      .regex("""fukuii/v\d(\.\d+)*(-SNAPSHOT)?-[a-z0-9]{7}/[^/]+-[^/]+/[^/]+-.[^/]+-java-\d+\.\d+[._0-9]*""")
  }

  it should "augment the name with an identity" taggedAs (UnitTest) in {
    val name = VersionInfo.nodeName(Some("chipprbots"))
    name should startWith("fukuii/chipprbots/v")
    name.count(_ == '/') shouldBe 4
  }
}
