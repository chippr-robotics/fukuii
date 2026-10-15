package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.BlockAccessRecorder

/** eth_simulateV1's entry points into an Amsterdam block's system calls, so a simulated block runs the ones a real
  * block runs (execution-apis eth_simulateV1 notes, "Block access list": "Pre-execution system calls (EIP-4788,
  * EIP-2935) record at block access index `0`. [...] Post-execution system calls (EIP-7002, EIP-7251, EIP-8282) and the
  * `withdrawals` from `blockOverrides` record at index `n + 1` [...]. This matches a real block.").
  *
  * Both delegate to the code block execution itself calls; nothing here is a second implementation.
  * `EthSimulateService` calls them only for a block at an Amsterdam timestamp, which no ETC chain schedules.
  */
object SimulatedBlockSystemCalls:

  /** The EIP-4788 / EIP-2935 preamble, as [[BlockExecution.applyAmsterdamPreambleSystemCalls]] runs it for a real
    * Amsterdam block.
    */
  def amsterdamPreamble(
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      accessRecorder: Option[BlockAccessRecorder]
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    BlockExecution.applyAmsterdamPreambleSystemCalls(header, world, accessRecorder)

  /** The post-execution EIP-7685 request phase of a real block: the request system calls
    * ([[BlockExecution.requestSystemCalls]]) and the EIP-6110 deposit requests parsed from `receipts`
    * ([[BlockExecution.depositRequests]]). Returns the world after the calls and the requests in canonical order
    * (deposits first), ready for [[BlockExecution.computeRequestsHash]]; Left if a system call failed or a deposit log
    * is malformed, which would make a real block invalid.
    */
  def requests(
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      receipts: Seq[Receipt],
      accessRecorder: Option[BlockAccessRecorder]
  )(implicit blockchainConfig: BlockchainConfig): Either[String, (InMemoryWorldStateProxy, Seq[ByteString])] =
    for
      (afterCalls, systemRequests) <- BlockExecution.requestSystemCalls(header, world, accessRecorder)
      deposits <- BlockExecution.depositRequests(receipts)
    yield (afterCalls, deposits.toSeq ++ systemRequests)
