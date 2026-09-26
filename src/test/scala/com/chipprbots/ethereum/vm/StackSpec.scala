package com.chipprbots.ethereum.vm

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.testing.Tags.*

class StackSpec extends AnyFunSuite with Matchers with ScalaCheckPropertyChecks:

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
      val (v, stack1) = stack.pop()
      if stack.size > 0 then
        v shouldEqual stack.toSeq.head
        stack1.toSeq shouldEqual stack.toSeq.tail
      else
        v shouldEqual 0
        stack1 shouldEqual stack
    }
  }

  test("pop single element from an empty stack", UnitTest, VMTest) {
    forAll(intGen.map(Stack.empty)) { emptyStack =>
      val (value, newStack) = emptyStack.pop()
      value shouldEqual UInt256.Zero
      newStack should be(emptyStack)
    }
  }

  test("pop multiple elements", UnitTest, VMTest) {
    forAll(stackGen, intGen) { (stack, i) =>
      val (vs, stack1) = stack.pop(i)
      if stack.size >= i then
        vs shouldEqual stack.toSeq.take(i)
        stack1.toSeq shouldEqual stack.toSeq.drop(i)
      else
        vs shouldEqual Seq.fill(i)(UInt256.Zero)
        stack1 shouldEqual stack
    }
  }

  test("push single element", UnitTest, VMTest) {
    forAll(nonFullStackGen, uint256Gen) { (stack, v) =>
      val stack1 = stack.push(v)

      stack1.toSeq shouldEqual (v +: stack.toSeq)
    }
  }

  test("push single element to full stack", UnitTest, VMTest) {
    forAll(fullStackGen, uint256Gen) { (stack, v) =>
      val newStack = stack.push(v)

      newStack shouldBe stack
    }
  }

  test("push multiple elements", UnitTest, VMTest) {
    forAll(stackGen, uint256ListGen) { (stack, vs) =>
      val stack1 = stack.push(vs)

      if stack.size + vs.size <= stack.maxSize then stack1.toSeq shouldEqual (vs.reverse ++ stack.toSeq)
      else stack1 shouldEqual stack
    }
  }

  test("duplicate element", UnitTest, VMTest) {
    forAll(stackGen, intGen) { (stack, i) =>
      val stack1 = stack.dup(i)

      if i < stack.size && stack.size < stack.maxSize then
        val x = stack.toSeq(i)
        stack1.toSeq shouldEqual (x +: stack.toSeq)
      else stack1 shouldEqual stack
    }
  }

  test("swap elements", UnitTest, VMTest) {
    forAll(stackGen, intGen) { (stack, i) =>
      val stack1 = stack.swap(i)

      if i < stack.size then
        val x = stack.toSeq.head
        val y = stack.toSeq(i)
        stack1.toSeq shouldEqual stack.toSeq.updated(0, y).updated(i, x)
      else stack1 shouldEqual stack
    }
  }

  /** The Vector-backed Stack this class used to be, verbatim, as an oracle: the list-backed one must give the same
    * results for every operation, in range or not.
    */
  final class VectorStack(val underlying: Vector[UInt256], val maxSize: Int):
    def pop(): (UInt256, VectorStack) = underlying.lastOption match
      case Some(word) => (word, VectorStack(underlying.dropRight(1), maxSize))
      case None       => (UInt256.Zero, this)
    def pop(n: Int): (Seq[UInt256], VectorStack) =
      val (updated, popped) = underlying.splitAt(underlying.length - n)
      if popped.length == n then (popped.reverse, VectorStack(updated, maxSize))
      else (Seq.fill(n)(UInt256.Zero), this)
    def push(word: UInt256): VectorStack =
      val updated = underlying :+ word
      if updated.length <= maxSize then VectorStack(updated, maxSize) else this
    def push(words: Seq[UInt256]): VectorStack =
      val updated = underlying ++ words
      if updated.length > maxSize then this else VectorStack(updated, maxSize)
    def dup(i: Int): VectorStack =
      val j = underlying.length - i - 1
      if i < 0 || i >= underlying.length || underlying.length >= maxSize then this
      else VectorStack(underlying :+ underlying(j), maxSize)
    def swap(i: Int): VectorStack =
      val j = underlying.length - i - 1
      if i <= 0 || i >= underlying.length then this
      else VectorStack(underlying.updated(j, underlying.last).init :+ underlying(j), maxSize)
    def size: Int = underlying.size
    def toSeq: Seq[UInt256] = underlying.reverse
    override def hashCode(): Int = underlying.hashCode
    override def toString: String = underlying.reverse.mkString("Stack(", ",", ")")

  sealed private trait Op
  private case object Pop extends Op
  final private case class PopN(n: Int) extends Op
  final private case class Push(word: UInt256) extends Op
  final private case class PushAll(words: List[UInt256]) extends Op
  final private case class Dup(i: Int) extends Op
  final private case class Swap(i: Int) extends Op

  test("behaves exactly as the Vector-backed stack it replaced, over random operation sequences", UnitTest, VMTest) {
    // indices and counts run past both ends of the stack, and the capacity is small enough to be hit
    val opGen: Gen[Op] = Gen.oneOf(
      Gen.const(Pop),
      Gen.choose(-2, 20).map(PopN(_)),
      uint256Gen.map(Push(_)),
      Generators.getListGen(0, 6, uint256Gen).map(PushAll(_)),
      Gen.choose(-2, 20).map(Dup(_)),
      Gen.choose(-2, 20).map(Swap(_))
    )
    val caseGen = for
      maxSize <- Gen.choose(0, 20)
      ops <- Gen.listOfN(60, opGen)
    yield (maxSize, ops)

    forAll(caseGen, minSuccessful(2000)) { case (maxSize, ops) =>
      ops.foldLeft((Stack.empty(maxSize), VectorStack(Vector.empty, maxSize))) { case ((stack, model), op) =>
        val (next, nextModel) = op match
          case Pop =>
            val ((word, s), (w, m)) = (stack.pop(), model.pop())
            word shouldEqual w
            (s, m)
          case PopN(n) =>
            val ((words, s), (ws, m)) = (stack.pop(n), model.pop(n))
            words shouldEqual ws
            (s, m)
          case Push(word)     => (stack.push(word), model.push(word))
          case PushAll(words) => (stack.push(words), model.push(words))
          case Dup(i)         => (stack.dup(i), model.dup(i))
          case Swap(i)        => (stack.swap(i), model.swap(i))
        next.toSeq shouldEqual nextModel.toSeq
        next.size shouldEqual nextModel.size
        next.maxSize shouldEqual nextModel.maxSize
        next.hashCode shouldEqual nextModel.hashCode
        next.toString shouldEqual nextModel.toString
        (next, nextModel)
      }
    }
  }
