package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
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
  *
  * What differs from a real block is what go-ethereum's simulator does differently, and block execution is not given
  * any of it (the delegates' parameters default to a real block's):
  *   - the calls run with the block's `precompileRelocations`, the precompiles its state overrides moved or deleted,
  *     because the simulator sets the overridden precompile set on the one EVM that runs the transactions and both
  *     system-call phases (simulate.go:403-409);
  *   - a request contract with no code fails the block, which a real block never reaches (it is invalid earlier, in
  *     `BlockExecution.requireRequestPredeploysPresent`).
  */
object SimulatedBlockSystemCalls:

  /** The EIP-4788 / EIP-2935 preamble, as [[BlockExecution.applyAmsterdamPreambleSystemCalls]] runs it for a real
    * Amsterdam block, on the block's precompiles as its state overrides left them.
    */
  def amsterdamPreamble(
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      accessRecorder: Option[BlockAccessRecorder],
      precompileRelocations: Map[Address, Address]
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    BlockExecution.applyAmsterdamPreambleSystemCalls(header, world, accessRecorder, precompileRelocations)

  /** The post-execution EIP-7685 request phase of a real block: the request system calls
    * ([[BlockExecution.requestSystemCalls]]) and the EIP-6110 deposit requests parsed from `receipts`
    * ([[BlockExecution.depositRequests]]). Returns the world after the calls and the requests in canonical order
    * (deposits first), ready for [[BlockExecution.computeRequestsHash]]; Left if a system call failed or a deposit log
    * is malformed, which would make a real block invalid.
    *
    * A request contract with no code is a Left too, with go-ethereum's text, for example `failed to process withdrawal
    * queue: empty system contract: no code at 0x00000961Ef480Eb55e80D19ad83579A64c007002` (state_processor.go:469-472
    * under `PostExecution`'s wrapper, :211-226). The caller answers it -32000, as go-ethereum's plain error is. The
    * contracts are checked in go-ethereum's order, each on the world the one before it left: withdrawals,
    * consolidations, then the builder deposit and exit queues.
    */
  def requests(
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      receipts: Seq[Receipt],
      accessRecorder: Option[BlockAccessRecorder],
      precompileRelocations: Map[Address, Address]
  )(implicit blockchainConfig: BlockchainConfig): Either[String, (InMemoryWorldStateProxy, Seq[ByteString])] =
    for
      (afterCalls, systemRequests) <- BlockExecution.requestSystemCalls(
        header,
        world,
        accessRecorder,
        precompileRelocations,
        BlockExecution.EmptyRequestContract.Fail
      )
      deposits <- BlockExecution.depositRequests(receipts)
    yield (afterCalls, deposits.toSeq ++ systemRequests)
