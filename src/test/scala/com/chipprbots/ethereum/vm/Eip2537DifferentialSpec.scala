package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.testing.Tags.*

/** B-153: does a fukuii node compute EIP-2537 the way Besu does?
  *
  * The node runs the BLS12-381 precompiles through Matter Labs' eth_pairings (`bls12-381:1.0.0`), which is abandoned
  * upstream; Besu runs gnark (`gnark:2.1.0`, `LibGnarkEIP2537`). Every corpus input goes through both, and the output
  * bytes and the success or failure must match. A split means a live ETH node at Prague or later, or an ETC node at
  * Olympia, can disagree with Besu and geth on a block: that is an escalation, not something to "fix" here.
  *
  * The corpus is [[Eip2537Corpus]]: the published EIP-2537 and go-ethereum vectors, generated edge cases for each
  * encoding rule, and a seeded fuzz. A second check holds each backend to the vectors' published outputs and to the
  * outcomes the spec text fixes for the edge cases.
  *
  * gnark ships Linux and macOS binaries only. On other platforms (Windows dev boxes) the native checks are cancelled
  * with a message. Where the binaries exist, a library that fails to load FAILS the suite, so Linux CI cannot skip it.
  */
class Eip2537DifferentialSpec extends AnyFlatSpec with Matchers:

  /** The production path comes first: it is what a node executes. B-151's blst backend is appended here. */
  private val backends: Seq[Eip2537Backend] = Seq(ProductionEip2537Backend, GnarkEip2537Backend)

  private val MaxReported = 25
  private val MaxHexBytes = 2048

  private case class Run(c: Eip2537Case, outcomes: Seq[Eip2537Outcome]):
    def agreed: Boolean = outcomes.forall(_.agreesWith(outcomes.head))

  /** Every case through every backend, once. The differential and the conformance check both read it. */
  private lazy val runs: Vector[Run] =
    Eip2537Corpus.all.map(c => Run(c, backends.map(_.run(c.op, c.input))))

  private def requireBackends(): Unit =
    if !Eip2537Platform.gnarkShipsBinary then
      cancel(
        s"EIP-2537 differential skipped: besu-native gnark 2.1.0 has no native build for " +
          s"${Eip2537Platform.os}/${Eip2537Platform.arch} (it ships linux and darwin, x86-64 and aarch64). " +
          "Linux CI runs it."
      )
    val notLoaded = backends.flatMap(b => b.loadFailure.map(why => s"  ${b.name}: $why"))
    if notLoaded.nonEmpty then
      fail(
        s"EIP-2537 differential: a backend did not load on ${Eip2537Platform.os}/${Eip2537Platform.arch}, where its " +
          "native build exists. Refusing to skip:\n" + notLoaded.mkString("\n")
      )

  private def isSuccess(o: Eip2537Outcome): Boolean = o match
    case Eip2537Outcome.Success(_) => true
    case Eip2537Outcome.Failure(_) => false

  private def hexCapped(bs: ByteString): String =
    if bs.length <= MaxHexBytes then s"0x${Eip2537Corpus.hex(bs)}"
    else s"0x${Eip2537Corpus.hex(bs.take(MaxHexBytes))}... (${bs.length - MaxHexBytes} more bytes)"

  private def describe(e: Eip2537Expectation): String = e match
    case Eip2537Expectation.Unknown       => "none (generated case: the backends are only compared)"
    case Eip2537Expectation.Output(bytes) => s"OK ${hexCapped(bytes)}"
    case Eip2537Expectation.Failure       => "ERROR"
    case Eip2537Expectation.AnySuccess    => "OK (bytes not computed by the corpus)"

  private def meets(o: Eip2537Outcome, e: Eip2537Expectation): Boolean = (o, e) match
    case (_, Eip2537Expectation.Unknown)                                => true
    case (Eip2537Outcome.Success(out), Eip2537Expectation.Output(want)) => out == want
    case (Eip2537Outcome.Success(_), Eip2537Expectation.AnySuccess)     => true
    case (Eip2537Outcome.Failure(_), Eip2537Expectation.Failure)        => true
    case _                                                              => false

  private def disagrees(op: Eip2537Op, input: ByteString): Boolean =
    val outcomes = backends.map(_.run(op, input))
    !outcomes.forall(_.agreesWith(outcomes.head))

  /** Drops whole pairs from a variable-length input while the backends still split: halves first, then single pairs
    * once k <= 16. The report shows the smallest input found.
    */
  private def minimise(op: Eip2537Op, input: ByteString): ByteString =
    if !op.variableLength || input.length % op.unit != 0 || input.length == op.unit then input
    else
      val k = input.length / op.unit
      val chunks = (0 until k).map(i => input.slice(i * op.unit, (i + 1) * op.unit))
      val halves = if k > 2 then Seq(chunks.take(k / 2), chunks.drop(k / 2)) else Seq.empty
      val oneRemoved = if k <= 16 then (0 until k).map(i => chunks.patch(i, Nil, 1)) else Seq.empty
      (halves ++ oneRemoved).iterator
        .map(_.foldLeft(ByteString.empty)(_ ++ _))
        .find(disagrees(op, _)) match
        case Some(smaller) => minimise(op, smaller)
        case None          => input

  private def report(r: Run): String =
    val op = r.c.op
    val smallest = minimise(op, r.c.input)
    val shown = if smallest == r.c.input then r.outcomes else backends.map(_.run(op, smallest))
    val minimal =
      if smallest == r.c.input then Seq.empty
      else Seq(s"  smallest input that still splits them (${smallest.length} bytes): ${hexCapped(smallest)}")
    (Seq(s"DISAGREEMENT ${op.label} ${r.c.label}", s"  input (${r.c.input.length} bytes): ${hexCapped(r.c.input)}") ++
      minimal ++
      backends.zip(shown).map((b, o) => s"  ${b.name}: ${o.render}") ++
      Seq(s"  corpus expectation: ${describe(r.c.expectation)}")).mkString("\n")

  private def summary(op: Eip2537Op, opRuns: Vector[Run]): String =
    val bySource = Seq("vector", "edge", "fuzz").map(g => s"$g ${opRuns.count(_.c.group == g)}").mkString(", ")
    val perBackend = backends.indices
      .map { i =>
        val ok = opRuns.count(r => isSuccess(r.outcomes(i)))
        s"${backends(i).name}: $ok ok / ${opRuns.size - ok} error"
      }
      .mkString("; ")
    s"${op.label}: ${opRuns.size} inputs ($bySource); agreement ${opRuns.count(_.agreed)}/${opRuns.size}; $perBackend"

  "The EIP-2537 differential corpus" should "be built from points that are what they claim, deterministically" taggedAs (
    VMTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    val k = Eip2537Corpus
    Seq(k.G1, k.P1, k.G1Double, k.G1NotInSubgroup, k.G1Order3, k.negG1(k.G1), k.negG1(k.P1), k.negG1(k.G1Order3))
      .foreach(pt => k.onCurveG1(pt) shouldBe true)
    k.onCurveG1(k.G1NotOnCurve) shouldBe false
    Seq(k.G2, k.P2, k.G2Double, k.G2NotInSubgroup, k.negG2(k.G2), k.negG2(k.P2))
      .foreach(pt => k.onCurveG2(pt) shouldBe true)
    k.onCurveG2(k.G2NotOnCurve) shouldBe false
    k.fuzz(k.FuzzSeed).map(_.input) shouldBe k.fuzz(k.FuzzSeed).map(_.input)
    k.VectorFiles.size shouldBe 36
    k.VectorFiles.map(_._1).distinct.size shouldBe 36
    Eip2537Op.values.foreach { op =>
      Seq("vector", "edge", "fuzz").foreach { g =>
        withClue(s"${op.label} $g cases: ")(k.all.count(c => c.op == op && c.group == g) should be > 0)
      }
    }
  }

  "The production EIP-2537 backend" should "be the contract each address 0x0b-0x11 dispatches to at Prague and Osaka" taggedAs (
    VMTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    Eip2537Op.values.foreach { op =>
      val addr = Address(op.address.toLong)
      val expected = ProductionEip2537Backend.contract(op)
      withClue(s"${op.label} in olympiaContracts (ETH Prague): ") {
        PrecompiledContracts.olympiaContracts(addr).eq(expected) shouldBe true
      }
      withClue(s"${op.label} in osakaContracts (ETH Osaka, ETC Olympia): ") {
        PrecompiledContracts.osakaContracts(addr).eq(expected) shouldBe true
      }
    }
  }

  Eip2537Op.values.foreach { op =>
    f"EIP-2537 ${op.label} (0x${op.address}%02x)" should "give the same bytes and the same success or failure on every backend" taggedAs (
      VMTest,
      ConsensusTest,
      OlympiaTest
    ) in {
      requireBackends()
      val opRuns = runs.filter(_.c.op == op)
      info(summary(op, opRuns))
      val split = opRuns.filterNot(_.agreed)
      if split.nonEmpty then
        fail(
          s"${split.size} of ${opRuns.size} ${op.label} inputs split the backends. ESCALATE: the production path may " +
            "disagree with Besu and geth on a live chain.\n\n" +
            split.take(MaxReported).map(report).mkString("\n\n") +
            (if split.size > MaxReported then s"\n\n... and ${split.size - MaxReported} more" else "")
        )
    }
  }

  "Every EIP-2537 backend" should "match the published vector outputs and the outcomes the spec fixes for edge cases" taggedAs (
    VMTest,
    ConsensusTest,
    OlympiaTest
  ) in {
    requireBackends()
    val checked = runs.filter(_.c.expectation != Eip2537Expectation.Unknown)
    val misses =
      for
        r <- checked
        (b, o) <- backends.zip(r.outcomes)
        if !meets(o, r.c.expectation)
      yield s"${b.name} on ${r.c.op.label} ${r.c.label}\n" +
        s"  input (${r.c.input.length} bytes): ${hexCapped(r.c.input)}\n" +
        s"  expected: ${describe(r.c.expectation)}\n" +
        s"  got: ${o.render}"
    info(s"${checked.size} cases carry an expectation (${checked.count(_.c.group == "vector")} from published vectors)")
    if misses.nonEmpty then
      fail(
        s"${misses.size} backend results contradict a published vector or the spec text:\n\n" +
          misses.take(MaxReported).mkString("\n\n") +
          (if misses.size > MaxReported then s"\n\n... and ${misses.size - MaxReported} more" else "")
      )
  }
