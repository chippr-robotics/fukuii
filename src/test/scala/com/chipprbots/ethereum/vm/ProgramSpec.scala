package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.testing.Tags.*

import Generators.*

class ProgramSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  val CodeSize = Byte.MaxValue
  val PositionsSize = 10

  val nonPushOp: Byte = JUMP.code
  val invalidOpCode: Byte = 0xef.toByte

  def positionsSetGen: Gen[Set[Int]] =
    getListGen(minSize = 0, maxSize = PositionsSize, genT = intGen(0, CodeSize)).map(_.toSet)

  it should "detect all jump destinations if there are no push op" taggedAs (UnitTest, VMTest) in {
    forAll(positionsSetGen) { jumpDestLocations =>
      val code = ByteString((0 to CodeSize).map { i =>
        if jumpDestLocations.contains(i) then JUMPDEST.code
        else nonPushOp
      }.toArray)
      val program = Program(code)
      program.validJumpDestinations shouldBe jumpDestLocations
    }
  }

  it should "detect all jump destinations if there are push op" taggedAs (UnitTest, VMTest) in {
    forAll(positionsSetGen, positionsSetGen) { (jumpDestLocations, pushOpLocations) =>
      val code = ByteString((0 to CodeSize).map { i =>
        if jumpDestLocations.contains(i) then JUMPDEST.code
        else if pushOpLocations.contains(i) then PUSH1.code
        else nonPushOp
      }.toArray)
      val program = Program(code)

      // Removing the PUSH1 that would be used as a parameter of another PUSH1
      //  Example: In "PUSH1 PUSH1 JUMPDEST", the JUMPDEST is a valid jump destination
      val pushOpLocationsNotParameters = pushOpLocations
        .diff(jumpDestLocations)
        .toList
        .sorted
        .foldLeft(List.empty[Int]) { case (recPushOpLocations, i) =>
          if recPushOpLocations.lastOption.contains(i - 1) then recPushOpLocations else recPushOpLocations :+ i
        }

      val jumpDestLocationsWithoutPushBefore = jumpDestLocations
        .filterNot(i => pushOpLocationsNotParameters.contains(i - 1))
        .filter(i => 0 <= i && i <= CodeSize)
      program.validJumpDestinations shouldBe jumpDestLocationsWithoutPushBefore
    }
  }

  it should "detect all jump destinations if there are invalid ops" taggedAs (UnitTest, VMTest) in {
    forAll(positionsSetGen, positionsSetGen) { (jumpDestLocations, invalidOpLocations) =>
      val code = ByteString((0 to CodeSize).map { i =>
        if jumpDestLocations.contains(i) then JUMPDEST.code
        else if invalidOpLocations.contains(i) then invalidOpCode
        else nonPushOp
      }.toArray)
      val program = Program(code)
      program.validJumpDestinations shouldBe jumpDestLocations
    }
  }

  it should "detect all instructions as jump destinations if they are" taggedAs (UnitTest, VMTest) in {
    val code = ByteString((0 to CodeSize).map(_ => JUMPDEST.code).toArray)
    val program = Program(code)
    program.validJumpDestinations shouldBe (0 to CodeSize).toSet
  }

  // The HashSet scan `validJumpDestinations` used before it became a bit set, kept as the oracle.
  private def referenceJumpDestinations(code: ByteString): Set[Int] =
    @scala.annotation.tailrec
    def go(pos: Int, accum: Set[Int]): Set[Int] =
      if pos < 0 || pos >= code.length then accum
      else
        EvmConfig.FrontierOpCodes.byteToOpCode.get(code(pos)) match
          case Some(pushOp: PushOp) => go(pos + pushOp.i + 2, accum)
          case Some(JUMPDEST)       => go(pos + 1, accum + pos)
          case _                    => go(pos + 1, accum)
    go(0, Set.empty)

  it should "match the HashSet scan on arbitrary code, every PUSH width and code length" taggedAs (
    UnitTest,
    VMTest
  ) in {
    // JUMPDEST-heavy bytes with every PUSH1..PUSH32 in the mix, so PUSH data hides JUMPDESTs, and code that ends
    // inside a PUSH's data.
    val byteGen: Gen[Byte] = Gen.frequency(
      4 -> Gen.const(JUMPDEST.code),
      2 -> Gen.choose(0x60, 0x7f).map(_.toByte),
      1 -> Gen.choose(Byte.MinValue, Byte.MaxValue)
    )
    val codeGen: Gen[ByteString] = for
      n <- Gen.oneOf(Gen.choose(0, 200), Gen.oneOf(63, 64, 65, 127, 128, 129, 4095, 4096, 4097))
      bytes <- Gen.listOfN(n, byteGen)
    yield ByteString(bytes.toArray)

    forAll(codeGen, minSuccessful(500)) { code =>
      val program = Program(code)
      val expected = referenceJumpDestinations(code)
      program.validJumpDestinations shouldBe expected
      (-2 to code.length + 70).foreach { dest =>
        program.validJumpDestinations.contains(dest) shouldBe expected.contains(dest)
      }
    }
  }

  it should "cost bits, not a hash set, for code full of JUMPDESTs" taggedAs (UnitTest, VMTest) in {
    // ethereum/tests JUMPDEST_AttackwithJump: 15 KB of JUMPDESTs, and one Program per frame of a 1,024-deep
    // self-call on Homestead. Bytes allocated by this thread (HotSpot) are deterministic, unlike heap occupancy.
    val mx = java.lang.management.ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    val code = ByteString(Array.fill(15000)(JUMPDEST.code))
    Program(code).validJumpDestinations.size shouldBe 15000 // warm-up
    val id = Thread.currentThread.threadId
    val before = mx.getThreadAllocatedBytes(id)
    val destinations = Program(code).validJumpDestinations
    val allocated = mx.getThreadAllocatedBytes(id) - before
    destinations.size shouldBe 15000
    allocated should be < 512L * 1024
  }
