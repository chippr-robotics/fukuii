package com.chipprbots.ethereum.domain

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures.Blocks._
import com.chipprbots.ethereum.testing.Tags._

class BlockSpec extends AnyFlatSpec with Matchers {
  "Block size" should "be correct" taggedAs(UnitTest) in {
    assert(Block.size(Genesis.block) == Genesis.size)
    assert(Block.size(Block3125369.block) == Block3125369.size)
    assert(Block.size(DaoForkBlock.block) == DaoForkBlock.size)
  }
}
