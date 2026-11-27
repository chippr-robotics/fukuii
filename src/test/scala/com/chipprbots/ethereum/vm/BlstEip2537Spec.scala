package com.chipprbots.ethereum.vm

import java.util.Locale

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.testing.Tags.*
import com.chipprbots.ethereum.vm.NativeBackends.Backend

/** B-151: the blst backend on its own, wherever jblst ships a native build, Windows included.
  *
  * Eip2537DifferentialSpec compares blst with eth_pairings and gnark byte for byte, but neither oracle has a Windows
  * build, so on Windows that spec is cancelled. Here blst is held to what the oracles computed, without them:
  *   - per op, the digest of every corpus input and blst's outcome (output bytes, or failure) must equal the committed
  *     digest of the oracles' outcomes ([[Eip2537OracleDigest]]; the differential checks the file against both oracles
  *     where they load);
  *   - every published vector output and every outcome the spec text fixes.
  *
  * The Windows CI job runs this with `FUKUII_BLS12381_BACKEND=blst`, and the last case checks that the precompiles do
  * run on blst there.
  */
class BlstEip2537Spec extends AnyFlatSpec with Matchers:

  /** Where jblst 0.3.18 ships blst: Windows amd64, Linux amd64 and aarch64, macOS x86_64 and aarch64. */
  private val jblstShipsBinary: Boolean =
    val os = Eip2537Platform.os.toLowerCase(Locale.ROOT)
    val arch = Eip2537Platform.arch.toLowerCase(Locale.ROOT)
    (os.startsWith("windows") && arch == "amd64") ||
    (os.startsWith("linux") && Set("amd64", "aarch64").contains(arch)) ||
    (os.startsWith("mac") && Set("x86_64", "aarch64").contains(arch))

  private def requireBlst(): Unit =
    if !jblstShipsBinary then cancel(s"jblst ships no blst build for ${Eip2537Platform.os}/${Eip2537Platform.arch}")
    BlstNative.loadFailure.foreach { why =>
      fail(s"blst did not load on ${Eip2537Platform.os}/${Eip2537Platform.arch}, where jblst ships it: $why")
    }

  private lazy val outcomes: Vector[(Eip2537Case, Eip2537Outcome)] =
    Eip2537Corpus.all.map(c => c -> BlstEip2537Backend.run(c.op, c.input))

  "blst" should "load wherever jblst ships a native build" taggedAs (VMTest, ConsensusTest) in {
    requireBlst()
    BlstNative.enabled shouldBe true
  }

  it should "compute the oracles' bytes, and fail where they fail, on the whole EIP-2537 corpus" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    requireBlst()
    val computed = Eip2537OracleDigest.lines(outcomes)
    val committed = Eip2537OracleDigest.committed
    Eip2537Op.values.foreach { op =>
      info(f"${op.label} (0x${op.address}%02x): ${outcomes.count(_._1.op == op)} inputs")
    }
    withClue(
      s"blst's outcome digests differ from the oracles' (${Eip2537OracleDigest.Resource}). Run " +
        "Eip2537DifferentialSpec on Linux, which compares blst with eth_pairings and gnark case by case. Computed:\n" +
        computed.mkString("\n") + "\n"
    ) {
      computed shouldBe committed
    }
  }

  it should "match every published vector output and every outcome the spec text fixes" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    requireBlst()
    val misses = outcomes.collect {
      case (c, Eip2537Outcome.Success(out)) if c.expectation == Eip2537Expectation.Failure =>
        s"${c.op.label} ${c.label}: expected an error, got ${out.length} bytes"
      case (c, Eip2537Outcome.Success(out)) if c.expectation match
            case Eip2537Expectation.Output(want) => want != out
            case _                               => false
          =>
        s"${c.op.label} ${c.label}: output differs from the published one"
      case (c, Eip2537Outcome.Failure(_)) if c.expectation match
            case Eip2537Expectation.Output(_) | Eip2537Expectation.AnySuccess => true
            case _                                                            => false
          =>
        s"${c.op.label} ${c.label}: expected success, got an error"
    }
    info(s"${outcomes.count(_._1.expectation != Eip2537Expectation.Unknown)} cases carry an expectation")
    if misses.nonEmpty then
      fail(s"${misses.size} blst results contradict the vectors or the spec:\n" + misses.mkString("\n"))
  }

  "The precompiles" should "run on blst when FUKUII_BLS12381_BACKEND=blst (the Windows CI job)" taggedAs (
    VMTest,
    ConsensusTest
  ) in {
    assume(
      sys.env.get(NativeBackends.BlsChoiceEnvironmentVariable).contains("blst"),
      s"${NativeBackends.BlsChoiceEnvironmentVariable} is not blst"
    )
    requireBlst()
    (NativeBackends.blsBackend eq BlstNative) shouldBe true
    NativeBackends.probeBackend(Backend.Bls12381) shouldBe Right(())
  }
