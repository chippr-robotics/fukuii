package com.chipprbots.ethereum.vm

import scala.collection.immutable.SortedMap
import scala.collection.immutable.SortedSet
import scala.collection.mutable

import org.json4s.JsonAST.JNothing
import org.json4s.JsonAST.JValue

import com.chipprbots.ethereum.domain.AccessListItem
import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.StorageKey

/** The access-list tracer of eth_createAccessList: go-ethereum `eth/tracers/logger/access_list_tracer.go`
  * (`AccessListTracer.OnOpcode`), which collects every address and storage slot a message call touches.
  *
  *   - SLOAD / SSTORE: the slot on the stack top, under the executing account (`scope.Address()`; under DELEGATECALL
  *     the caller's storage). Recorded for every account, the excluded ones included — which is why a call's `to`
  *     appears in the result when it reads its own storage.
  *   - BALANCE / EXTCODESIZE / EXTCODECOPY / EXTCODEHASH / SELFDESTRUCT: the address on the stack top.
  *   - CALL / CALLCODE / DELEGATECALL / STATICCALL: the callee, second on the stack.
  *   - Addresses (not slots) in `excluded` are never added: geth excludes the sender, the recipient (or the created
  *     contract) and the precompiles active at the block, all of which are warm anyway.
  *
  * geth's hook fires before each opcode executes, and again from the interpreter's error path when an opcode fails
  * before it was logged, so a failing SLOAD still records its slot. fukuii's [[ExecutionTracer.onStep]] fires after
  * every opcode, failed ones included, with the pre-execution state; reading the operands from `prevState` is therefore
  * the same observation. Observation only: nothing here changes how the call executes.
  *
  * @param seed
  *   the access list the tracer starts from (geth `NewAccessListTracer(acl, ...)`): its addresses unless excluded, all
  *   of its slots
  */
final class AccessListTracer(seed: Seq[AccessListItem], excluded: Set[Address]) extends ExecutionTracer:

  // address -> slots; an address with an empty set is present with no slots
  private val touched: mutable.Map[Address, mutable.Set[BigInt]] = mutable.Map.empty

  seed.foreach { item =>
    if !excluded.contains(item.address) then addAddress(item.address)
    item.storageKeys.foreach(k => addSlot(item.address, k.value))
  }

  private def addAddress(address: Address): Unit =
    touched.getOrElseUpdate(address, mutable.Set.empty)

  private def addSlot(address: Address, slot: BigInt): Unit =
    touched.getOrElseUpdate(address, mutable.Set.empty) += slot

  private def addUnlessExcluded(address: Address): Unit =
    if !excluded.contains(address) then addAddress(address)

  override def onStep[W <: WorldStateProxy[W, S], S <: Storage[S]](
      opCode: OpCode,
      prevState: ProgramState[W, S],
      nextState: ProgramState[W, S]
  ): Unit =
    val stack = prevState.stack.toSeq // top of the stack first
    opCode match
      case SLOAD | SSTORE if stack.nonEmpty =>
        addSlot(prevState.ownAddress, stack.head.toBigInt)
      case EXTCODECOPY | EXTCODEHASH | EXTCODESIZE | BALANCE | SELFDESTRUCT if stack.nonEmpty =>
        addUnlessExcluded(Address(stack.head))
      case DELEGATECALL | CALL | STATICCALL | CALLCODE if stack.size >= 5 =>
        addUnlessExcluded(Address(stack(1)))
      case _ => ()

  /** What was touched, as one set per address; what `tracer.Equal(prevTracer)` compares in geth. */
  def touchedSet: Map[Address, Set[BigInt]] = touched.view.mapValues(_.toSet).toMap

  /** The collected access list, ordered as go-ethereum's `accessList.accessList()` orders it: tuples by address,
    * storage keys ascending.
    */
  def accessList: List[AccessListItem] =
    val byAddress = SortedMap.from(touched.map { case (a, s) => BigInt(1, a.toArray) -> (a, s) })
    byAddress.values.toList.map { case (address, slots) =>
      AccessListItem(address, SortedSet.from(slots).toList.map(StorageKey(_)))
    }

  override def getResult: JValue = JNothing
