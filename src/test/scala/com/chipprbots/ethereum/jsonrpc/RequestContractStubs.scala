package com.chipprbots.ethereum.jsonrpc

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.jsonrpc.EthSimulateService.StateOverride
import com.chipprbots.ethereum.ledger.BlockExecution

/** The four request contracts of an Amsterdam block (EIP-7002 and EIP-7251 queues, EIP-8282 builder deposit and exit
  * queues), each a contract that does nothing, as state overrides.
  *
  * A simulated Amsterdam block runs the EIP-7685 request system calls, and go-ethereum fails one whose contract has no
  * code (`failed to process <queue>: empty system contract: no code at <address>`, which fukuii answers since #1727 PR
  * 5). A real chain has the contracts; a test world built on an empty state does not, so an Amsterdam block in such a
  * test puts these in. STOP returns no data, so the block has no request, and its only effect on the block access list
  * is that each of the four addresses is read at index `n + 1`.
  */
object RequestContractStubs:

  val Addresses: Seq[Address] = Seq(
    BlockExecution.WithdrawalQueueAddress,
    BlockExecution.ConsolidationQueueAddress,
    BlockExecution.BuilderDepositQueueAddress,
    BlockExecution.BuilderExitQueueAddress
  )

  /** STOP. */
  val Stop: ByteString = ByteString(Array[Byte](0x00))

  val overrides: Map[Address, StateOverride] = Addresses.map(_ -> StateOverride(code = Some(Stop))).toMap
