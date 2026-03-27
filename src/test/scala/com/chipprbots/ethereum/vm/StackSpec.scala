package com.chipprbots.ethereum.vm

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags._

class StackSpec extends AnyFunSuite with Matchers with ScalaCheckPropertyChecks {

  val maxStackSize = 32
  val stackGen: Gen[Stack] = Generators.getStackGen(maxSize = maxStackSize)
  val intGen: Gen[Int] = Gen.choose(0, maxStackSize).filter(_ >= 0)
  val uint256Gen: Gen[UInt256] = Generators.getUInt256Gen()
  val uint256ListGen: Gen[List[UInt256]] = Generators.getListGen(0, 16, uint256Gen)
  val fullStackGen: Gen[Stack] = intGen.flatMap(n => Generators.getStackGen(n, n, uint256Gen, n))
  val nonFullStackGen: Gen[Stack] =
    Generators.getStackGen(maxElems = maxStackSize - 1, maxSize = maxStackSize, valueGen = uint256Gen)

  test("pop single element", UnitTest, VMTest) {
    forAll(stackGen) { stack =>
      val origSeq = stack.toSeq
      val origSize = stack.size
      val v = stack.pop()
      if (origSize > 0) {
        v shouldEqual origSeq.head
        stack.toSeq shouldEqual origSeq.tail
      } else {
        v shouldEqual UInt256.Zero
        stack.size shouldEqual 0
      }
    }
  }

  test("pop single element from an empty stack", UnitTest, VMTest) {
    forAll(intGen.map(Stack.empty)) { emptyStack =>
      val value = emptyStack.pop()
      value shouldEqual UInt256.Zero
      emptyStack.size shouldEqual 0
    }
  }

  test("pop multiple elements", UnitTest, VMTest) {
    forAll(stackGen, intGen) { (stack, i) =>
      val origSeq = stack.toSeq
      val origSize = stack.size
      val vs = stack.pop(i)
      if (origSize >= i) {
        vs shouldEqual origSeq.take(i)
        stack.toSeq shouldEqual origSeq.drop(i)
      } else {
        vs shouldEqual Seq.fill(i)(UInt256.Zero)
        stack.size shouldEqual origSize
      }
    }
  }

  test("push single element", UnitTest, VMTest) {
    forAll(nonFullStackGen, uint256Gen) { (stack, v) =>
      val origSeq = stack.toSeq
      stack.push(v)
      stack.toSeq shouldEqual (v +: origSeq)
    }
  }

  test("push single element to full stack", UnitTest, VMTest) {
    forAll(fullStackGen, uint256Gen) { (stack, v) =>
      val origSeq = stack.toSeq
      stack.push(v)
      stack.toSeq shouldEqual origSeq
    }
  }

  test("push multiple elements", UnitTest, VMTest) {
    forAll(stackGen, uint256ListGen) { (stack, vs) =>
      val origSeq = stack.toSeq
      val origSize = stack.size
      stack.push(vs)
      if (origSize + vs.size <= stack.maxSize) {
        stack.toSeq shouldEqual (vs.reverse ++ origSeq)
      } else {
        stack.toSeq shouldEqual origSeq
      }
    }
  }

  test("duplicate element", UnitTest, VMTest) {
    forAll(stackGen, intGen) { (stack, i) =>
      val origSeq = stack.toSeq
      val origSize = stack.size
      stack.dup(i)
      if (i < origSize && origSize < stack.maxSize) {
        val x = origSeq(i)
        stack.toSeq shouldEqual (x +: origSeq)
      } else {
        stack.toSeq shouldEqual origSeq
      }
    }
  }

  test("swap elements", UnitTest, VMTest) {
    forAll(stackGen, intGen) { (stack, i) =>
      val origSeq = stack.toSeq
      val origSize = stack.size
      stack.swap(i)
      if (i > 0 && i < origSize) {
        val x = origSeq.head
        val y = origSeq(i)
        stack.toSeq shouldEqual origSeq.updated(0, y).updated(i, x)
      } else {
        stack.toSeq shouldEqual origSeq
      }
    }
  }

  test("peek does not mutate", UnitTest, VMTest) {
    forAll(stackGen, intGen) { (stack, i) =>
      val origSeq = stack.toSeq
      val origSize = stack.size
      val v = stack.peek(i)
      stack.size shouldEqual origSize
      stack.toSeq shouldEqual origSeq
      if (i < origSize) {
        v shouldEqual origSeq(i)
      } else {
        v shouldEqual UInt256.Zero
      }
    }
  }

  test("peekN does not mutate", UnitTest, VMTest) {
    forAll(stackGen, intGen) { (stack, n) =>
      val origSeq = stack.toSeq
      val origSize = stack.size
      val vs = stack.peekN(n)
      stack.size shouldEqual origSize
      stack.toSeq shouldEqual origSeq
      if (origSize >= n) {
        vs shouldEqual origSeq.take(n)
      } else {
        vs shouldEqual Seq.fill(n)(UInt256.Zero)
      }
    }
  }

  test("set modifies element in place", UnitTest, VMTest) {
    forAll(stackGen, intGen, uint256Gen) { (stack, i, v) =>
      val origSeq = stack.toSeq
      val origSize = stack.size
      stack.set(i, v)
      stack.size shouldEqual origSize
      if (i < origSize) {
        stack.peek(i) shouldEqual v
        stack.toSeq shouldEqual origSeq.updated(i, v)
      } else {
        stack.toSeq shouldEqual origSeq
      }
    }
  }

  test("set out of bounds is no-op", UnitTest, VMTest) {
    val stack = Stack.empty(maxStackSize)
    stack.push(UInt256(42))
    val origSeq = stack.toSeq
    stack.set(5, UInt256(99))
    stack.toSeq shouldEqual origSeq
    stack.set(-1, UInt256(99))
    stack.toSeq shouldEqual origSeq
  }

}
