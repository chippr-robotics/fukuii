package com.chipprbots.ethereum.vm

import scala.collection.mutable

import org.apache.pekko.util.ByteString

import org.json4s.JsonAST._
import org.json4s.JsonDSL._

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.UInt256
import com.chipprbots.ethereum.utils.Hex

/** Native prestateTracer matching go-ethereum's eth/tracers/native/prestate.go.
  *
  * Besu reference: StateTraceGenerator generates state diffs; this tracer also supports
  * go-ethereum's pre-state snapshot mode.
  *
  * Default mode — returns pre-transaction state for all touched accounts:
  * {{{
  * { "0xaddr": { "balance": "0x...", "nonce": 1, "code": "0x...", "storage": {"0xkey": "0xval"} } }
  * }}}
  *
  * diffMode — returns pre and post state (post only includes changed fields):
  * {{{
  * { "pre": { "0xaddr": { ... } }, "post": { "0xaddr": { ... } } }
  * }}}
  *
  * @param preWorld  world state snapshot before transaction execution
  * @param diffMode  when true, return {pre, post} diff instead of just prestate
  */
class PrestateTracer[W <: WorldStateProxy[W, S], S <: Storage[S]](
    val preWorld: W,
    diffMode: Boolean = false
) extends ExecutionTracer {

  private val touchedAddresses   = mutable.LinkedHashSet[Address]()
  private val touchedStorageKeys = mutable.HashMap[Address, mutable.LinkedHashSet[UInt256]]()
  private var postWorld: Option[W] = None

  def setPostWorld(world: W): Unit = {
    postWorld = Some(world)
  }

  override def onTxStart(from: Address, to: Option[Address], gas: BigInt, value: BigInt, input: ByteString): Unit = {
    touchedAddresses += from
    to.foreach(addr => touchedAddresses += addr)
  }

  override def onCallEnter(
      opCode: String,
      from: Address,
      to: Address,
      gas: BigInt,
      value: BigInt,
      input: ByteString
  ): Unit = {
    touchedAddresses += from
    touchedAddresses += to
  }

  override def onStep[W2 <: WorldStateProxy[W2, S2], S2 <: Storage[S2]](
      opCode: OpCode,
      prevState: ProgramState[W2, S2],
      nextState: ProgramState[W2, S2]
  ): Unit = {
    val opName = opCode.toString
    if (opName == "SLOAD" || opName == "SSTORE") {
      val stack = prevState.stack
      if (stack.size >= 1) {
        val addr = prevState.env.ownerAddr
        val key  = stack.toSeq.head
        touchedAddresses += addr
        touchedStorageKeys.getOrElseUpdate(addr, mutable.LinkedHashSet.empty) += key
      }
    }
    opName match {
      case "BALANCE" | "EXTCODESIZE" | "EXTCODECOPY" | "EXTCODEHASH" =>
        val stack = prevState.stack
        if (stack.size >= 1) {
          val addrUint = stack.toSeq.head
          touchedAddresses += Address(addrUint)
        }
      case "SELFDESTRUCT" =>
        val stack = prevState.stack
        if (stack.size >= 1) {
          val beneficiary = stack.toSeq.head
          touchedAddresses += Address(beneficiary)
          touchedAddresses += prevState.env.ownerAddr
        }
      case _ => ()
    }
  }

  override def getResult: JValue = {
    if (diffMode) {
      postWorld match {
        case Some(pw) => encodeDiff(pw)
        case None     => encodePrestate()
      }
    } else {
      encodePrestate()
    }
  }

  private def encodePrestate(): JValue = {
    val fields = touchedAddresses.toList.flatMap { addr =>
      preWorld.getAccount(addr).map { account =>
        val addrHex = "0x" + Hex.toHexString(addr.bytes.toArray)
        addrHex -> encodeAccountState(addr, account, preWorld)
      }
    }
    JObject(fields.map { case (k, v) => JField(k, v) })
  }

  private def encodeDiff(pw: W): JValue = {
    val preFields  = mutable.ListBuffer[JField]()
    val postFields = mutable.ListBuffer[JField]()

    touchedAddresses.foreach { addr =>
      val addrHex     = "0x" + Hex.toHexString(addr.bytes.toArray)
      val preAccount  = preWorld.getAccount(addr)
      val postAccount = pw.getAccount(addr)

      preAccount.foreach { acc =>
        preFields += JField(addrHex, encodeAccountState(addr, acc, preWorld))
      }

      postAccount.foreach { postAcc =>
        val preAcc = preAccount.getOrElse(com.chipprbots.ethereum.domain.Account.empty(0))
        val diff   = encodeAccountDiff(addr, preAcc, postAcc, pw)
        if (diff != JNothing) {
          postFields += JField(addrHex, diff)
        }
      }

      if (preAccount.isEmpty && postAccount.isDefined) {
        postFields += JField(addrHex, encodeAccountState(addr, postAccount.get, pw))
      }
    }

    ("pre" -> JObject(preFields.toList)) ~ ("post" -> JObject(postFields.toList))
  }

  private def encodeAccountState(addr: Address, account: com.chipprbots.ethereum.domain.Account, world: W): JValue = {
    var obj: JObject = ("balance" -> JString("0x" + account.balance.toBigInt.toString(16))) ~
      ("nonce" -> JInt(account.nonce.bigInteger))

    val code = world.getCode(addr)
    if (code.nonEmpty) {
      obj = obj ~ ("code" -> JString("0x" + Hex.toHexString(code.toArray)))
    }

    val storageKeys = touchedStorageKeys.getOrElse(addr, Set.empty)
    if (storageKeys.nonEmpty) {
      val storage = world.getStorage(addr)
      val storageFields = storageKeys.toList.flatMap { key =>
        val value = storage.load(key.toBigInt)
        if (value != BigInt(0)) {
          val keyHex = "0x" + key.toBigInt.toString(16).reverse.padTo(64, '0').reverse
          val valHex = "0x" + value.toString(16).reverse.padTo(64, '0').reverse
          Some(JField(keyHex, JString(valHex)))
        } else None
      }
      if (storageFields.nonEmpty) {
        obj = obj ~ ("storage" -> JObject(storageFields))
      }
    }

    obj
  }

  private def encodeAccountDiff(
      addr: Address,
      preAcc: com.chipprbots.ethereum.domain.Account,
      postAcc: com.chipprbots.ethereum.domain.Account,
      pw: W
  ): JValue = {
    var fields = List.empty[JField]

    if (preAcc.balance != postAcc.balance) {
      fields :+= JField("balance", JString("0x" + postAcc.balance.toBigInt.toString(16)))
    }
    if (preAcc.nonce != postAcc.nonce) {
      fields :+= JField("nonce", JInt(postAcc.nonce.bigInteger))
    }

    val preCode  = preWorld.getCode(addr)
    val postCode = pw.getCode(addr)
    if (preCode != postCode && postCode.nonEmpty) {
      fields :+= JField("code", JString("0x" + Hex.toHexString(postCode.toArray)))
    }

    val storageKeys = touchedStorageKeys.getOrElse(addr, Set.empty)
    if (storageKeys.nonEmpty) {
      val preStorage  = preWorld.getStorage(addr)
      val postStorage = pw.getStorage(addr)
      val storageFields = storageKeys.toList.flatMap { key =>
        val preVal  = preStorage.load(key.toBigInt)
        val postVal = postStorage.load(key.toBigInt)
        if (preVal != postVal) {
          val keyHex = "0x" + key.toBigInt.toString(16).reverse.padTo(64, '0').reverse
          val valHex = "0x" + postVal.toString(16).reverse.padTo(64, '0').reverse
          Some(JField(keyHex, JString(valHex)))
        } else None
      }
      if (storageFields.nonEmpty) {
        fields :+= JField("storage", JObject(storageFields))
      }
    }

    if (fields.isEmpty) JNothing else JObject(fields)
  }
}
