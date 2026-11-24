package com.chipprbots.ethereum.ledger

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.Address
import com.chipprbots.ethereum.domain.Block
import com.chipprbots.ethereum.domain.BlockBody
import com.chipprbots.ethereum.domain.BlockHeader
import com.chipprbots.ethereum.domain.Receipt
import com.chipprbots.ethereum.utils.BlockchainConfig
import com.chipprbots.ethereum.vm.BlockAccessRecorder

/** eth_simulateV1's entry points into a block's system calls, so a simulated block runs the ones a real block runs:
  * import's preamble (the DAO fork block's drain, EIP-4788, EIP-2935) before its calls, and from Prague the EIP-7685
  * request phase after them. On Amsterdam they are also what the block access list records (execution-apis
  * eth_simulateV1 notes, "Block access list": "Pre-execution system calls (EIP-4788, EIP-2935) record at block access
  * index `0`. [...] Post-execution system calls (EIP-7002, EIP-7251, EIP-8282) and the `withdrawals` from
  * `blockOverrides` record at index `n + 1` [...]. This matches a real block.").
  *
  * Both delegate to the code block execution itself calls; nothing here is a second implementation
  * (`.claude/agent-protocols/proposer-import-parity.md`, Rule 2).
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

  /** The block's preamble, as [[BlockExecution.applyBlockPreamble]] runs it for a real block: the DAO fork block's
    * drain; then the EIP-4788 / EIP-2935 direct storage writes before Amsterdam (a system call where a state override
    * put other code at the address, as import runs such code, B-517), or from Amsterdam the system calls, which record
    * at block access index 0 of `accessList` and run on the block's precompiles as its state overrides left them. Only
    * the header is read, so the block is given an empty body.
    *
    * The caller runs it AFTER the block's state overrides: go-ethereum's `processBlock` applies `StateOverrides` and
    * then calls `ProcessBeaconBlockRoot` / `ProcessParentBlockHash` on the EVM it built (internal/ethapi/simulate.go),
    * and execution-apis says the same for Amsterdam ("State overrides [...] are applied to the pre-state before the
    * pre-execution system calls run").
    */
  def preamble(
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      accessList: Option[BlockAccessListBuilder],
      precompileRelocations: Map[Address, Address]
  )(implicit blockchainConfig: BlockchainConfig): InMemoryWorldStateProxy =
    BlockExecution.applyBlockPreamble(
      Block(header, BlockBody.empty),
      header.number.value,
      world,
      accessList,
      precompileRelocations
    )

  /** The post-execution EIP-7685 request phase of a real block: the request system calls
    * ([[BlockExecution.requestSystemCalls]]) and the EIP-6110 deposit requests parsed from `receipts`
    * ([[BlockExecution.depositRequests]]). Returns the world after the calls and the requests in canonical order
    * (deposits first), ready for [[BlockExecution.computeRequestsHash]]; Left if a system call failed or a deposit log
    * is malformed, which would make a real block invalid.
    *
    * A request contract with no code is a Left too, with go-ethereum's text, for example `failed to process withdrawal
    * queue: empty system contract: no code at 0x00000961Ef480Eb55e80D19ad83579A64c007002` (state_processor.go:469-472
    * under `PostExecution`'s wrapper, :211-226). The caller answers it -32000, as go-ethereum's plain error is.
    *
    * go-ethereum's order (`PostExecution`, state_processor.go:207-226): the deposit logs are parsed FIRST, so a
    * malformed deposit event is the error even where a queue would fail too; then the contracts, each on the world the
    * one before it left: withdrawals, consolidations, then the builder deposit and exit queues. Block execution parses
    * the deposits after the calls; for a valid block the two orders give the same requests, since parsing reads only
    * the receipts.
    */
  def requests(
      header: BlockHeader,
      world: InMemoryWorldStateProxy,
      receipts: Seq[Receipt],
      accessRecorder: Option[BlockAccessRecorder],
      precompileRelocations: Map[Address, Address]
  )(implicit blockchainConfig: BlockchainConfig): Either[String, (InMemoryWorldStateProxy, Seq[ByteString])] =
    for
      deposits <- BlockExecution.depositRequests(receipts)
      (afterCalls, systemRequests) <- BlockExecution.requestSystemCalls(
        header,
        world,
        accessRecorder,
        precompileRelocations,
        BlockExecution.EmptyRequestContract.Fail
      )
    yield (afterCalls, deposits.toSeq ++ systemRequests)
