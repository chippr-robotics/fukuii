package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import com.chipprbots.ethereum.crypto.kec256
import com.chipprbots.ethereum.testing.Tags.*

/** [[JumpDestAnalysis]] against the scan it replaced. The oracle below is that scan, verbatim in semantics: the
  * `FrontierOpCodes` lookup per byte, a PUSH skipping `pushOp.i + 2`, a JUMPDEST setting its bit.
  */
class JumpDestAnalysisSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks:

  private def oracle(code: ByteString): Set[Int] =
    @scala.annotation.tailrec
    def go(pos: Int, accum: Set[Int]): Set[Int] =
      if pos < 0 || pos >= code.length then accum
      else
        EvmConfig.FrontierOpCodes.byteToOpCode.get(code(pos)) match
          case Some(pushOp: PushOp) => go(pos + pushOp.i + 2, accum)
          case Some(JUMPDEST)       => go(pos + 1, accum + pos)
          case _                    => go(pos + 1, accum)
    go(0, Set.empty)

  private def same(code: ByteString): Unit =
    val actual = JumpDestAnalysis.analyse(code)
    actual shouldBe oracle(code)
    // The bit mask itself, not just membership: trailing zero words are the only slack, and a 64-bit word per 64
    // code bytes is exactly what the oracle's consumers (`contains`, `size`) see.
    actual.toBitMask.toSeq.reverse.dropWhile(_ == 0L).reverse shouldBe
      oracle(code).groupBy(_ >>> 6).toSeq.sortBy(_._1).foldLeft(Vector.empty[Long]) { case (acc, (w, ps)) =>
        acc ++ Vector.fill(w - acc.length)(0L) :+ ps.foldLeft(0L)((m, p) => m | (1L << p))
      }

  "JumpDestAnalysis.analyse" should "match the old scan on empty code and single bytes" taggedAs (UnitTest, VMTest) in {
    same(ByteString.empty)
    (0 to 255).foreach(b => same(ByteString(b.toByte)))
  }

  it should "hide a 0x5b inside PUSH data, and expose it just past the data" taggedAs (UnitTest, VMTest) in {
    same(ByteString(0x60, 0x5b, 0x5b)) // PUSH1 <5b> JUMPDEST: only index 2
    JumpDestAnalysis.analyse(ByteString(0x60, 0x5b, 0x5b)).toSet shouldBe Set(2)
    same(ByteString(0x7f.toByte) ++ ByteString(Array.fill(32)(0x5b.toByte)) ++ ByteString(0x5b)) // PUSH32, 32 data
    JumpDestAnalysis
      .analyse(ByteString(0x7f.toByte) ++ ByteString(Array.fill(32)(0x5b.toByte)) ++ ByteString(0x5b))
      .toSet shouldBe Set(33)
  }

  it should "end the scan inside a truncated final PUSH" taggedAs (UnitTest, VMTest) in {
    (0x60 to 0x7f).foreach { push =>
      val width = push - 0x5f
      (0 to width + 1).foreach { present =>
        same(ByteString(0x5b, push.toByte) ++ ByteString(Array.fill(present)(0x5b.toByte)))
      }
    }
  }

  it should "match on random 64 KB code and on 64 KB of a single byte" taggedAs (UnitTest, VMTest) in {
    val r = new java.util.Random(7)
    val random = new Array[Byte](65536); r.nextBytes(random)
    same(ByteString(random))
    Seq(0x5b, 0x60, 0x7f, 0x00, 0xfe).foreach(b => same(ByteString(Array.fill(65536)(b.toByte))))
    same(ByteString(Array.fill(65537)(0x5b.toByte))) // not a multiple of 64
  }

  it should "match on arbitrary code of arbitrary length, for every PUSH width" taggedAs (UnitTest, VMTest) in {
    val byteGen: Gen[Byte] = Gen.frequency(
      4 -> Gen.const(0x5b.toByte),
      3 -> Gen.choose(0x60, 0x7f).map(_.toByte),
      1 -> Gen.choose(Byte.MinValue, Byte.MaxValue)
    )
    val codeGen = for
      n <- Gen.oneOf(Gen.choose(0, 300), Gen.oneOf(63, 64, 65, 127, 128, 129, 4095, 4096, 4097))
      bytes <- Gen.listOfN(n, byteGen)
    yield ByteString(bytes.toArray)
    forAll(codeGen, minSuccessful(1000))(same)
  }

  it should "not modify or retain the code's array" taggedAs (UnitTest, VMTest) in {
    val arr = Array.fill[Byte](100)(0x5b)
    val copy = arr.clone()
    JumpDestAnalysis.analyse(ByteString.fromArrayUnsafe(arr))
    arr shouldBe copy
  }

  "Program.validJumpDestinations" should "be identical with and without a known code hash" taggedAs (
    UnitTest,
    VMTest
  ) in {
    val r = new java.util.Random(11)
    (1 to 50).foreach { _ =>
      val bytes = new Array[Byte](r.nextInt(2000)); r.nextBytes(bytes)
      val code = ByteString(bytes)
      val hashed = Program.withCodeHash(code, kec256(code))
      hashed.validJumpDestinations shouldBe Program(code).validJumpDestinations
      hashed shouldBe Program(code) // the hash is not part of a program's identity
    }
  }

  "JumpDestAnalysis.Cache" should "return the cached analysis for a known hash and stay within its byte budget" taggedAs (
    UnitTest,
    VMTest
  ) in {
    val cache = new JumpDestAnalysis.Cache(maxBytes = 20 * 1024)
    def codeOf(i: Int) =
      ByteString(Array.fill(8192)(if i % 2 == 0 then 0x5b.toByte else 0x00.toByte)) ++ ByteString(i.toByte)
    val first = cache.getOrCompute(kec256(codeOf(0)), codeOf(0))
    (cache.getOrCompute(kec256(codeOf(0)), codeOf(0)) should be).theSameInstanceAs(first)
    (1 to 50).foreach { i =>
      val code = codeOf(i)
      cache.getOrCompute(kec256(code), code) shouldBe JumpDestAnalysis.analyse(code)
      cache.sizeBytes should be <= (20L * 1024)
    }
    cache.entries should be < 50
  }

  it should "not cache anything when the budget is zero, or when one entry exceeds it" taggedAs (UnitTest, VMTest) in {
    val code = ByteString(Array.fill(1000)(0x5b.toByte))
    val off = new JumpDestAnalysis.Cache(0)
    off.getOrCompute(kec256(code), code) shouldBe JumpDestAnalysis.analyse(code)
    off.entries shouldBe 0
    val tiny = new JumpDestAnalysis.Cache(64)
    tiny.getOrCompute(kec256(code), code) shouldBe JumpDestAnalysis.analyse(code)
    tiny.entries shouldBe 0
  }
