package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*
import org.json4s.MonadicJValue.jvalueToMonadic
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.ethereum.Fixtures.Blocks as BlockFixtures
import com.chipprbots.ethereum.domain.*
import com.chipprbots.ethereum.testing.Tags.*

/** Unit tests for StructLogTracer — the default tracer for debug_traceTransaction / debug_traceBlockByNumber
  * (DebugTracingService.selectTracer, case None | Some("structLogger")).
  *
  * Pins two things that were wrong before this fix:
  *   1. getResult returned JNothing unconditionally (StructLogTracer.scala originally had a stale comment claiming the
  *      response was built elsewhere — it wasn't built anywhere), so every default-tracer call produced a response with
  *      no `result` key at all. 2. Stack.toSeq is top-first (see Stack.scala), but go-ethereum's structLog format is
  *      bottom-first (oldest push first). Emitting Stack.toSeq unreversed produces a schema-valid but semantically
  *      wrong response — exactly the kind of bug a speconly (schema-only) hive test will NOT catch.
  *
  * execution-apis fixture cross-check (trace-block-storage-encoding.io): the pre-execution stack at the KECCAK256 step
  * reads ["0x40","0x0"], where 0x0 is the most recently pushed value — i.e. bottom-first.
  */
class StructLogTracerSpec extends AnyFreeSpec with Matchers:

  private val senderAddr = Address(0xcafebabeL)
  private val recipientAddr = Address(0xdeadbeefL)
  private val senderAcc = Account(nonce = 1, balance = 1000000)

  private val blockHeader = BlockFixtures.ValidBlock.header.copy(
    difficulty = Difficulty(1000000),
    number = BlockNumber(1),
    gasLimit = GasAmount(10000000),
    gasUsed = GasAmount.Zero,
    unixTimestamp = Timestamp(0)
  )

  private val evmConfig = EvmConfig.forBlock(
    0,
    BlockchainConfigForEvm(
      frontierBlockNumber = Long.MaxValue,
      homesteadBlockNumber = 0,
      eip150BlockNumber = Long.MaxValue,
      eip160BlockNumber = Long.MaxValue,
      eip161BlockNumber = Long.MaxValue,
      byzantiumBlockNumber = Long.MaxValue,
      constantinopleBlockNumber = Long.MaxValue,
      istanbulBlockNumber = Long.MaxValue,
      maxCodeSize = Some(24576),
      accountStartNonce = 0,
      atlantisBlockNumber = Long.MaxValue,
      aghartaBlockNumber = Long.MaxValue,
      petersburgBlockNumber = Long.MaxValue,
      phoenixBlockNumber = Long.MaxValue,
      magnetoBlockNumber = Long.MaxValue,
      berlinBlockNumber = Long.MaxValue,
      mystiqueBlockNumber = Long.MaxValue,
      spiralBlockNumber = Long.MaxValue,
      olympiaBlockNumber = Long.MaxValue,
      chainId = ChainId(0x3d)
    )
  )

  // PUSH1 0x02, PUSH1 0x03, ADD, STOP — deliberately two distinct, order-revealing operands.
  private val code = ByteString(0x60, 0x02, 0x60, 0x03, 0x01, 0x00)

  // PUSH1 0x02, PUSH1 0x00, MSTORE, PUSH1 0x00, STOP — writes the word 0x02 to memory offset 0,
  // then a trailing PUSH1/STOP so a post-MSTORE step exists whose prevState.memory is non-empty.
  private val memoryWriteCode = ByteString(0x60, 0x02, 0x60, 0x00, 0x52, 0x60, 0x00, 0x00)

  private def runTopLevelCall(tracer: StructLogTracer, contractCode: ByteString = code): Unit =
    val world = MockWorldState()
      .saveAccount(senderAddr, senderAcc)
      .saveAccount(recipientAddr, Account(nonce = 0))
      .saveCode(recipientAddr, contractCode)

    val vm = VM[MockWorldState, MockStorage](Some(tracer))
    val ctx = ProgramContext[MockWorldState, MockStorage](
      callerAddr = senderAddr,
      originAddr = senderAddr,
      recipientAddr = Some(recipientAddr),
      gasPrice = 1,
      startGas = 1000000,
      inputData = ByteString.empty,
      value = 0,
      endowment = 0,
      doTransfer = false,
      blockHeader = blockHeader,
      callDepth = 0,
      world = world,
      initialAddressesToDelete = Set(),
      evmConfig = evmConfig,
      originalWorld = world,
      warmAddresses = Set.empty,
      warmStorage = Set.empty
    )

    tracer.onTxStart(senderAddr, Some(recipientAddr), gas = 1000000, value = 0, input = ByteString.empty)
    val result = vm.call(ctx, senderAddr)
    // gasUsed is irrelevant to the stack-ordering assertions these tests make (onTxEnd's gas/failed/
    // returnValue wiring has its own dedicated tests below that call onTxEnd directly), so a fixed
    // placeholder avoids depending on ProgramResult's exact gas accounting here.
    tracer.onTxEnd(gasUsed = 0, output = result.returnData, error = result.error.map(_.toString))

  "StructLogTracer" - {

    "records the pre-execution stack bottom-first (oldest push first), not Stack.toSeq's top-first order" taggedAs (
      UnitTest,
      VMTest
    ) in {
      val tracer = new StructLogTracer(enableMemory = false, enableStorage = false)
      runTopLevelCall(tracer)

      val addStep = tracer.getSteps.find(_.op == "ADD").getOrElse(fail("expected an ADD step in the trace"))

      // 0x2 was pushed first (bottom), 0x3 second (top) — bottom-first means 0x2 appears
      // before 0x3, mirroring execution-apis's trace-block-storage-encoding.io fixture where
      // the most-recently-pushed operand is always LAST in the JSON array.
      addStep.stack shouldBe Seq(BigInt(2), BigInt(3))
    }

    "emits the same bottom-first order as 0x-prefixed minimal-width hex in getResult's structLogs" taggedAs (
      UnitTest,
      VMTest
    ) in {
      val tracer = new StructLogTracer(enableMemory = false, enableStorage = false)
      runTopLevelCall(tracer)

      val structLogs = (tracer.getResult \ "structLogs").asInstanceOf[JArray].arr
      val addLog = structLogs.find(log => (log \ "op") == JString("ADD")).getOrElse(fail("no ADD structLog entry"))

      (addLog \ "stack") shouldBe JArray(List(JString("0x2"), JString("0x3")))
      (addLog \ "depth") shouldBe JInt(1) // go-ethereum's 1-based depth for the top-level frame
    }

    "getResult's top-level gas/failed/returnValue come from onTxEnd, not from the removed setResult" taggedAs (
      UnitTest,
      VMTest
    ) in {
      val tracer = new StructLogTracer()
      tracer.onTxStart(senderAddr, Some(recipientAddr), gas = 21000, value = 0, input = ByteString.empty)
      tracer.onTxEnd(gasUsed = 21000, output = ByteString.empty, error = None)

      val result = tracer.getResult
      (result \ "gas") shouldBe JInt(21000)
      (result \ "failed") shouldBe JBool(false)
      (result \ "returnValue") shouldBe JString("0x") // empty ByteString must encode as "0x", not ""
      (result \ "structLogs") shouldBe JArray(Nil)
    }

    "marks failed=true when the traced tx errored" taggedAs (UnitTest, VMTest) in {
      val tracer = new StructLogTracer()
      tracer.onTxStart(senderAddr, Some(recipientAddr), gas = 21000, value = 0, input = ByteString.empty)
      tracer.onTxEnd(gasUsed = 21000, output = ByteString.empty, error = Some("out of gas"))

      (tracer.getResult \ "failed") shouldBe JBool(true)
    }

    "encodes a non-empty returnValue with a 0x prefix" taggedAs (UnitTest, VMTest) in {
      val tracer = new StructLogTracer()
      tracer.onTxStart(senderAddr, Some(recipientAddr), gas = 21000, value = 0, input = ByteString.empty)
      tracer.onTxEnd(gasUsed = 21000, output = ByteString(0xde.toByte, 0xad.toByte), error = None)

      (tracer.getResult \ "returnValue") shouldBe JString("0xdead")
    }

    "getResult is a JObject, never the JNothing stub it returned before this fix" taggedAs (UnitTest, VMTest) in {
      val tracer = new StructLogTracer()
      tracer.getResult shouldBe a[JObject]
      tracer.getResult should not be JNothing
    }

    "omits memory entirely when enableMemory is false (the default) — go-ethereum's zero-value Config; " +
      "execution-apis's trace-block-with-transactions.io sends no config at all and expects zero memory keys" taggedAs (
        UnitTest,
        VMTest
      ) in {
        val tracer = new StructLogTracer(enableMemory = false, enableStorage = false)
        runTopLevelCall(tracer, memoryWriteCode)

        tracer.getSteps should not be empty
        tracer.getSteps.foreach(_.memory shouldBe None)

        val structLogs = (tracer.getResult \ "structLogs").asInstanceOf[JArray].arr
        structLogs should not be empty
        structLogs.foreach(log => (log \ "memory") shouldBe JNothing)
      }

    "emits 0x-prefixed, 64-hex-digit memory words when enableMemory is true, per opcode-tracer.yaml's " +
      "bytes32 pattern ^0x[0-9a-f]{64}$ — the un-prefixed encoding previously emitted failed this exact check" taggedAs (
        UnitTest,
        VMTest
      ) in {
        val tracer = new StructLogTracer(enableMemory = true, enableStorage = false)
        runTopLevelCall(tracer, memoryWriteCode)

        val steps = tracer.getSteps
        val afterMstore = steps
          .sliding(2)
          .collectFirst { case Seq(a, b) if a.op == "MSTORE" => b }
          .getOrElse(fail("expected a step immediately after MSTORE"))

        // MSTORE(offset=0, value=2) writes value 2 as a right-aligned, zero-padded 32-byte word.
        val expectedWord = "0x" + ("0" * 63) + "2"
        afterMstore.memory shouldBe Some(Seq(expectedWord))
        (afterMstore.memory.get.head should fullyMatch).regex("^0x[0-9a-f]{64}$")

        val structLogs = (tracer.getResult \ "structLogs").asInstanceOf[JArray].arr
        val afterMstoreJson = structLogs
          .find(log => (log \ "pc") == JInt(afterMstore.pc))
          .getOrElse(fail("expected a structLog entry matching the post-MSTORE step"))
        (afterMstoreJson \ "memory") shouldBe JArray(List(JString(expectedWord)))
      }
  }
