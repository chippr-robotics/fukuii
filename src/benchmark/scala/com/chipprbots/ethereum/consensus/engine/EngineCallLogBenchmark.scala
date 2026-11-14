package com.chipprbots.ethereum.consensus.engine

import scala.util.Success

import org.json4s.*
import org.json4s.native.JsonMethods.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.jsonrpc.JsonRpcResponse
import com.chipprbots.ethereum.testing.Tags.*

/** What noting an Engine API call costs (spec 029 FR-042): the time of one `EngineCallLog.observe`, and that it does
  * not grow with the payload.
  *
  * `sbt "Benchmark / testOnly *EngineCallLogBenchmark"`, and read the `[ENGINE-CALL-LOG]` lines of the report. The note
  * is made by a callback beside the answer, so its time is not in the answer's; this is what it adds to the Engine
  * API's dispatcher. The bounds asserted are loose, for a shared machine: the figures printed are the measurement.
  */
class EngineCallLogBenchmark extends AnyFlatSpec with Matchers:

  private def payload(transactions: Int): JValue =
    val txs = (0 until transactions).map(i => s""""0x${"ab" * 110}${f"$i%08x"}"""").mkString(",")
    parse(
      s"""{"jsonrpc":"2.0","id":1,"method":"engine_newPayloadV4","params":[{"parentHash":"0x${"11" * 32}","feeRecipient":"0x${"22" * 20}","stateRoot":"0x${"33" * 32}","receiptsRoot":"0x${"44" * 32}","logsBloom":"0x${"00" * 256}","prevRandao":"0x${"55" * 32}","blockNumber":"0x71a2c9","gasLimit":"0x1c9c380","gasUsed":"0x5208","timestamp":"0x6ac6fd60","extraData":"0x","baseFeePerGas":"0x7","blockHash":"0x${"66" * 32}","transactions":[$txs],"withdrawals":[],"blobGasUsed":"0x0","excessBlobGas":"0x0"},[],"0x${"77" * 32}",[]]}"""
    )

  private val answer = Success(
    JsonRpcResponse(
      "2.0",
      Some(parse(s"""{"status":"VALID","latestValidHash":"0x${"66" * 32}","validationError":null}""")),
      None,
      JInt(1)
    )
  )

  /** The 50th and 99th percentile of one `observe`, in nanoseconds, over `rounds` of them after a warm-up. */
  private def measure(request: JValue, rounds: Int = 200_000): (Long, Long) =
    val log = new EngineCallLog
    var i = 0
    while i < 100_000 do
      log.observe("engine_newPayloadV4", request, answer, System.nanoTime())
      i += 1
    val times = new Array[Long](rounds)
    i = 0
    while i < rounds do
      val start = System.nanoTime()
      log.observe("engine_newPayloadV4", request, answer, start)
      times(i) = System.nanoTime() - start
      i += 1
    java.util.Arrays.sort(times)
    (times(rounds / 2), times(rounds * 99 / 100))

  "Noting an Engine API call" should "take about a microsecond, whatever the payload carries" taggedAs (BenchmarkTest) in {
    val (emptyP50, emptyP99) = measure(payload(0))
    val (fullP50, fullP99) = measure(payload(1_500))
    info(s"[ENGINE-CALL-LOG] empty payload:            p50 $emptyP50 ns, p99 $emptyP99 ns")
    info(s"[ENGINE-CALL-LOG] 1,500-transaction payload: p50 $fullP50 ns, p99 $fullP99 ns")
    // Loose, for a shared machine: the note is two field lookups, a hex parse and one small allocation.
    emptyP50 should be < 20_000L
    fullP50 should be < 20_000L
    // The payload's transactions are never walked: a payload of 1,500 costs what an empty one does, within noise.
    fullP50 should be < emptyP50 * 5 + 2_000L
  }
