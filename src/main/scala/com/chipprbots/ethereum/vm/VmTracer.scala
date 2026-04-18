package com.chipprbots.ethereum.vm

import scala.collection.mutable

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST._
import org.json4s.JsonDSL._

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.utils.Hex

/** Parity-format vmTrace tracer for trace_replayBlockTransactions / trace_replayTransaction.
  *
  * Besu reference: ethereum/api/.../results/tracing/vm/VmTraceGenerator.java
  *
  * Produces the vmTrace field matching OpenEthereum / Besu format:
  * {{{
  * {
  *   "code": "0x...",
  *   "ops": [
  *     {
  *       "cost": 3,
  *       "ex": {
  *         "mem": {"data": "0x...", "off": 32},
  *         "push": ["0x1"],
  *         "store": {"key": "0x0", "val": "0x1"},
  *         "used": 21000
  *       },
  *       "pc": 0,
  *       "sub": null
  *     }
  *   ]
  * }
  * }}}
  *
  * Frame stack mirrors the call depth. onCallEnter pushes a new frame; onCallExit encodes it and attaches it as the
  * `sub` field on the triggering CALL/CREATE op in the parent frame.
  *
  * Divergence from Besu: VmTraceGenerator post-processes TraceFrames; this tracer streams during execution.
  * Semantically equivalent for standard use cases.
  */
class VmTracer extends ExecutionTracer {

  private case class VmFrame(
      var code: ByteString = ByteString.empty,
      ops: mutable.ArrayBuffer[VmOp] = mutable.ArrayBuffer.empty
  )

  private case class VmOp(
      pc: Int,
      cost: BigInt,
      exUsed: BigInt,
      exPush: Seq[BigInt],
      exMem: Option[(BigInt, ByteString)], // (offset, data written)
      exStore: Option[(BigInt, BigInt)], // (key, value written)
      var sub: Option[JValue] = None
  )

  private val frameStack = mutable.Stack[VmFrame]()
  private var rootFrame: Option[VmFrame] = None

  override def onTxStart(from: Address, to: Option[Address], gas: BigInt, value: BigInt, input: ByteString): Unit = {
    val frame = VmFrame()
    frameStack.push(frame)
    rootFrame = Some(frame)
  }

  override def onStep[W <: WorldStateProxy[W, S], S <: Storage[S]](
      opCode: OpCode,
      prevState: ProgramState[W, S],
      nextState: ProgramState[W, S]
  ): Unit = {
    if (frameStack.isEmpty) return
    val frame = frameStack.top

    if (frame.code.isEmpty) {
      frame.code = prevState.env.program.code
    }

    val cost = prevState.gas - nextState.gas
    val exUsed = nextState.gas

    val exPush: Seq[BigInt] =
      if (opCode.alpha > 0)
        nextState.stack.toSeq.take(opCode.alpha).map(_.toBigInt)
      else
        Seq.empty

    val exMem: Option[(BigInt, ByteString)] = opCode match {
      case MSTORE if prevState.stack.size >= 2 =>
        val offset = prevState.stack.toSeq.head
        val data = nextState.memory.load(offset, UInt256(32))._1
        Some((offset.toBigInt, data))
      case MSTORE8 if prevState.stack.size >= 2 =>
        val offset = prevState.stack.toSeq.head
        val data = nextState.memory.load(offset, UInt256(1))._1
        Some((offset.toBigInt, data))
      case _ =>
        None
    }

    val exStore: Option[(BigInt, BigInt)] = opCode match {
      case SSTORE if prevState.stack.size >= 2 =>
        val key = prevState.stack.toSeq(0).toBigInt
        val v = prevState.stack.toSeq(1).toBigInt
        Some((key, v))
      case _ =>
        None
    }

    frame.ops += VmOp(
      pc = prevState.pc,
      cost = cost,
      exUsed = exUsed,
      exPush = exPush,
      exMem = exMem,
      exStore = exStore
    )
  }

  override def onCallEnter(
      opCode: String,
      from: Address,
      to: Address,
      gas: BigInt,
      value: BigInt,
      input: ByteString
  ): Unit = {
    val frame = VmFrame()
    frameStack.push(frame)
  }

  override def onCallExit(gasUsed: BigInt, output: ByteString, error: Option[String]): Unit = {
    if (frameStack.size <= 1) return
    val frame = frameStack.pop()
    val encoded = encodeFrame(frame)
    if (frameStack.nonEmpty && frameStack.top.ops.nonEmpty) {
      frameStack.top.ops.last.sub = Some(encoded)
    }
  }

  override def getResult: JValue = rootFrame match {
    case Some(frame) => encodeFrame(frame)
    case None        => JNull
  }

  private def encodeFrame(frame: VmFrame): JValue =
    ("code" -> encodeHexBytes(frame.code)) ~
      ("ops" -> JArray(frame.ops.toList.map(encodeOp)))

  private def encodeOp(op: VmOp): JValue = {
    val ex: JValue =
      ("mem" -> op.exMem
        .map { case (off, data) =>
          ("data" -> encodeHexBytes(data)) ~ ("off" -> JInt(off.bigInteger))
        }
        .getOrElse(JNull: JValue)) ~
        ("push" -> JArray(op.exPush.map(v => JString("0x" + v.toString(16))).toList)) ~
        ("store" -> op.exStore
          .map { case (k, v) =>
            ("key" -> JString("0x" + k.toString(16))) ~ ("val" -> JString("0x" + v.toString(16)))
          }
          .getOrElse(JNull: JValue)) ~
        ("used" -> JInt(op.exUsed.bigInteger))

    ("cost" -> JInt(op.cost.bigInteger)) ~
      ("ex" -> ex) ~
      ("pc" -> JInt(op.pc)) ~
      ("sub" -> op.sub.getOrElse(JNull: JValue))
  }

  private def encodeHexBytes(bs: ByteString): JString =
    if (bs.isEmpty) JString("0x")
    else JString("0x" + Hex.toHexString(bs.toArray))
}
