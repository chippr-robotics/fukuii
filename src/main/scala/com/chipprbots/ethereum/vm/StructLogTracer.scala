package com.chipprbots.ethereum.vm

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST.*
import org.json4s.JsonDSL.*

import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.utils.Hex

/** A single step in EVM execution, matching go-ethereum's structLog format. */
case class StructLog(
    pc: Int,
    op: String,
    gas: BigInt,
    gasCost: BigInt,
    depth: Int,
    stack: Seq[BigInt],
    memory: Option[Seq[String]],
    storage: Option[Map[String, String]],
    error: Option[String]
)

/** Collects opcode-by-opcode execution trace in go-ethereum structLog format.
  *
  * Besu reference: evm/src/main/java/org/hyperledger/besu/evm/tracing/StreamingOperationTracer.java
  *
  * Besu streams structLog to a PrintStream; Fukuii collects to a buffer for in-memory access. Output format is
  * equivalent: per-opcode pc, op, gas, gasCost, depth, stack, memory, storage.
  *
  * @param enableMemory
  *   include memory snapshot per step (expensive)
  * @param enableStorage
  *   include storage diff per step (expensive)
  * @param limit
  *   maximum number of steps to capture (0 = unlimited)
  */
class StructLogTracer(
    enableMemory: Boolean = false,
    enableStorage: Boolean = false,
    limit: Int = 0
) extends ExecutionTracer:
  private val steps = scala.collection.mutable.ArrayBuffer[StructLog]()
  private var _gas: BigInt = 0
  private var _failed: Boolean = false
  private var _returnValue: ByteString = ByteString.empty

  // A step is placed when its opcode starts (onStepStart) and completed when it ends (onStep), so a CALL or CREATE is
  // listed before the steps of the frame it opens, as go-ethereum lists it (B-026). Each open step: its index in
  // `steps` (-1 past the limit) and the gas the frames it opened handed back.
  private val open = scala.collection.mutable.Stack[(Int, Array[BigInt])]()
  // The gas each open sub-frame started with (onCallEnter), to learn what it hands back at onCallExit.
  private val frameStartGas = scala.collection.mutable.Stack[BigInt]()

  override def onStepStart[W <: WorldStateProxy[W, S], S <: Storage[S]](
      opCode: OpCode,
      state: ProgramState[W, S]
  ): Unit =
    if limit > 0 && steps.size >= limit then open.push((-1, Array(BigInt(0))))
    else
      open.push((steps.size, Array(BigInt(0))))
      steps += StructLog(
        pc = state.pc,
        op = opCode.toString,
        gas = state.gas,
        gasCost = 0,
        depth = state.env.callDepth + 1, // go-ethereum uses 1-based depth
        // go-ethereum's structLog stack is bottom-first (oldest push first). Stack.toSeq is
        // top-first (see Stack.scala: toSeq = underlying.reverse), so it must be reversed here
        // to avoid a schema-valid-but-wrong-order response.
        stack = state.stack.toSeq.reverse.map(_.toBigInt),
        memory = memorySnapshot(state),
        storage = None,
        error = None
      )

  override def onCallEnter(
      opCode: String,
      from: com.chipprbots.ethereum.domain.Address,
      to: com.chipprbots.ethereum.domain.Address,
      gas: BigInt,
      value: BigInt,
      input: ByteString
  ): Unit = frameStartGas.push(gas)

  override def onCallExit(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit =
    if frameStartGas.nonEmpty then
      val handedBack = frameStartGas.pop() - gasUsed
      if open.nonEmpty then open.top._2(0) += handedBack

  override def onStep[W <: WorldStateProxy[W, S], S <: Storage[S]](
      opCode: OpCode,
      prevState: ProgramState[W, S],
      nextState: ProgramState[W, S]
  ): Unit =
    if open.nonEmpty then
      val (index, handedBack) = open.pop()
      if index >= 0 then
        steps(index) = steps(index).copy(
          gasCost = gasCost(opCode, prevState, nextState, handedBack(0)),
          storage = storageSnapshot(opCode, prevState, nextState),
          error = nextState.error.map(ProgramErrorText.vmErrorMessage)
        )

  /** The op's cost as go-ethereum's interpreter passes it to the tracer: its constant and dynamic gas, charged before
    * it runs (B-026).
    *   - Out of gas when charged: the cost `execute` computed, which it keeps on the halted state (`opcodeGasCost`);
    *     the gas left, which the state shows, is not the cost.
    *   - CREATE and CREATE2: the cost `execute` computed (`opcodeGasCost`). The gas handed to the new frame is taken
    *     inside the op, as go-ethereum's opCreate takes it, and is not part of the cost.
    *   - The CALL family: the gas spent plus what the callee handed back, which is the cost including the gas forwarded
    *     (go-ethereum's dynamic gas for a call includes it), the stipend excluded.
    *   - Anything else: the gas spent.
    */
  private def gasCost[W <: WorldStateProxy[W, S], S <: Storage[S]](
      opCode: OpCode,
      prev: ProgramState[W, S],
      next: ProgramState[W, S],
      handedBack: BigInt
  ): BigInt =
    if next.error.contains(OutOfGas) && next.opcodeGasCost != prev.opcodeGasCost then next.opcodeGasCost
    else
      opCode match
        case _: CreateOp if next.error.isEmpty => next.opcodeGasCost
        case _: CallOp                         => prev.gas - next.gas + handedBack
        case _                                 => prev.gas - next.gas

  private def memorySnapshot[W <: WorldStateProxy[W, S], S <: Storage[S]](
      state: ProgramState[W, S]
  ): Option[Seq[String]] =
    if enableMemory then
      val mem = state.memory
      if mem.size > 0 then
        val words = (0 until mem.size by 32).map { offset =>
          val word = mem.load(UInt256(offset), UInt256(32))._1
          // Schema (execution-apis src/schemas/opcode-tracer.yaml, StructLog.memory.items): each
          // chunk is a 0x-prefixed bytes32 (^0x[0-9a-f]{64}$). The un-prefixed hex below was
          // previously emitted bare and failed that pattern — see StructLogTracerSpec.
          "0x" + word.toArray.map("%02x".format(_)).mkString
        }
        Some(words.toSeq)
      else Some(Seq.empty)
    else None

  private def storageSnapshot[W <: WorldStateProxy[W, S], S <: Storage[S]](
      opCode: OpCode,
      prevState: ProgramState[W, S],
      nextState: ProgramState[W, S]
  ): Option[Map[String, String]] =
    if enableStorage then
      opCode match
        case SLOAD if prevState.stack.size >= 1 =>
          val slot = prevState.stack.toSeq.head.toBigInt
          val value = nextState.stack.toSeq.head.toBigInt
          val k = "0x" + slot.toString(16).reverse.padTo(64, '0').reverse
          val v = "0x" + value.toString(16).reverse.padTo(64, '0').reverse
          Some(Map(k -> v))
        case SSTORE if prevState.stack.size >= 2 =>
          val slot = prevState.stack.toSeq(0).toBigInt
          val value = prevState.stack.toSeq(1).toBigInt
          val k = "0x" + slot.toString(16).reverse.padTo(64, '0').reverse
          val v = "0x" + value.toString(16).reverse.padTo(64, '0').reverse
          Some(Map(k -> v))
        case _ => None
    else None

  /** Populates the tx-level result fields. Fired once after the top-level transaction returns — see
    * StxLedger.simulateTransactionWithTracer, which calls this after all onStep calls have completed with exactly the
    * values go-ethereum's ExecutionResult carries: post-refund gas used, the return/revert data, and the error (if
    * any).
    */
  override def onTxEnd(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit =
    _gas = gasUsed
    _returnValue = output
    _failed = error.isDefined

  def getSteps: Seq[StructLog] = steps.toSeq
  def gas: BigInt = _gas
  def failed: Boolean = _failed
  def returnValue: ByteString = _returnValue

  /** Builds the go-ethereum structLog response: {gas, failed, returnValue, structLogs}.
    *
    * core-geth reference: eth/tracers/logger/logger.go StructLogger — ExecutionResult()/StructLogs().
    */
  override def getResult: JValue =
    ("gas" -> JInt(_gas)) ~
      ("failed" -> JBool(_failed)) ~
      ("returnValue" -> encodeHexBytes(_returnValue)) ~
      ("structLogs" -> JArray(steps.toList.map(encodeStep)))

  private def encodeStep(log: StructLog): JValue =
    var obj: JObject = ("pc" -> JInt(log.pc)) ~
      ("op" -> JString(log.op)) ~
      ("gas" -> JInt(log.gas)) ~
      ("gasCost" -> JInt(log.gasCost)) ~
      ("depth" -> JInt(log.depth)) ~
      ("stack" -> JArray(log.stack.map(encodeHex).toList))

    log.memory.foreach(mem => obj = obj ~ ("memory" -> JArray(mem.map(JString(_)).toList)))
    log.storage.foreach(st =>
      obj = obj ~ ("storage" -> JObject(st.toList.map { case (k, v) => JField(k, JString(v)) }))
    )
    log.error.foreach(e => obj = obj ~ ("error" -> JString(e)))

    obj

  private def encodeHex(value: BigInt): JString =
    JString("0x" + value.toString(16))

  private def encodeHexBytes(bs: ByteString): JString =
    if bs.isEmpty then JString("0x")
    else JString("0x" + Hex.toHexString(bs.toArray))
