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
    // getThreadAllocatedBytes returns -1 when unsupported or disabled, which would make the delta ~0 and pass.
    assume(mx.isThreadAllocatedMemorySupported && mx.isThreadAllocatedMemoryEnabled)
    val id = Thread.currentThread.threadId
    val before = mx.getThreadAllocatedBytes(id)
    val destinations = Program(code).validJumpDestinations
    val allocated = mx.getThreadAllocatedBytes(id) - before
    destinations.size shouldBe 15000
    allocated should be < 512L * 1024
  }

  it should "fetch the byte at pc, and 0 outside the code, exactly as code.lift did" taggedAs (UnitTest, VMTest) in {
    // A plain array, a slice into a larger one, and a concatenation: the three ByteString shapes code arrives in.
    val shapes: Gen[ByteString] = for
      bytes <- Gen.listOf(byteGen).map(l => ByteString(l.toArray))
      shape <- Gen.choose(0, 2)
    yield shape match
      case 0 => bytes
      case 1 => (ByteString(1.toByte, 2.toByte) ++ bytes ++ ByteString(3.toByte)).compact.slice(2, 2 + bytes.length)
      case _ => ByteString(bytes.take(bytes.length / 2).toArray) ++ ByteString(bytes.drop(bytes.length / 2).toArray)

    forAll(shapes, minSuccessful(500)) { code =>
      val program = Program(code)
      (-3 to code.length + 3).foreach { pc =>
        program.getByte(pc) shouldBe code.lift(pc).getOrElse(0.toByte)
      }
    }
  }

  "OpCodeList.opCodeFor" should "answer byteToOpCode.get for every byte of every fork's table" taggedAs (
    UnitTest,
    VMTest
  ) in {
    val tables = Seq(
      EvmConfig.FrontierOpCodes,
      EvmConfig.HomesteadOpCodes,
      EvmConfig.ByzantiumOpCodes,
      EvmConfig.ConstantinopleOpCodes,
      EvmConfig.PhoenixOpCodes,
      EvmConfig.SpiralOpCodes,
      EvmConfig.OlympiaOpCodes,
      EvmConfig.EtcOlympiaOpCodes,
      EvmConfig.LondonOpCodes,
      EvmConfig.ShanghaiOpCodes,
      EvmConfig.CancunOpCodes,
      EvmConfig.OsakaOpCodes
    )
    for
      table <- tables
      byte <- Byte.MinValue to Byte.MaxValue
    do table.opCodeFor(byte.toByte) shouldBe table.byteToOpCode.get(byte.toByte)
  }

  "Program.immediate" should "read the value UInt256(getBytes(from, size)) does, zero past the end of the code" taggedAs (
    UnitTest,
    VMTest
  ) in {
    val codeGen: Gen[ByteString] =
      Gen.choose(0, 70).flatMap(n => Gen.listOfN(n, byteGen)).map(l => ByteString(l.toArray))
    forAll(codeGen, minSuccessful(500)) { code =>
      val program = Program(code)
      for
        from <- 0 to code.length + 2
        size <- 0 to 32
      do program.immediate(from, size) shouldBe com.chipprbots.ethereum.domain.UInt256(program.getBytes(from, size))
    }
    // the widths where the Long accumulator ends and the BigInt path begins, at their extreme values
    for size <- Seq(6, 7, 8, 9, 31, 32) do
      val ones = Program(ByteString(Array.fill(40)(0xff.toByte)))
      ones.immediate(1, size) shouldBe com.chipprbots.ethereum.domain.UInt256(ones.getBytes(1, size))
  }
