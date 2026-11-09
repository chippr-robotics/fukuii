# Proposer/import parity (consensus footgun)

Used by: forge, beacon, eye. Applies to every path that builds a block rather than imports one: PoW mining and test
mode (`BlockGeneratorSkeleton` -> `BlockPreparator.prepareBlock`), the Engine API payload builder
(`BlockExecution.executeForProposer`), and eth_simulateV1 (`EthSimulateService`).

A built block is valid only if import computes the same header and state for it. Import is the reference; every
builder must reach the same result by calling the same code, not a copy of it.

## Rule 1: the template header carries every field import recomputes from the parent

Set them before any transaction runs, with the function the validator uses, behind the validator's own gate:
difficulty, gas limit, base fee (`BaseFeeCalculator.calcBaseFee`, gated as `BlockHeaderValidatorSkeleton.validateBaseFee`
gates it), excess blob gas. Transactions read these fields (BASEFEE, the effective gas price, the miner's tip), so a
field filled in after execution gives a different block.

## Rule 2: the builder runs import's pre-transaction steps

`BlockExecution.applyBlockPreamble` is everything import does before the first transaction: the DAO fork block's drain,
EIP-4788 and EIP-2935 (system calls from Amsterdam). A builder that skips it builds on a state import never sees.

## Rule 3: post-execution payments read the sealed header, not the template

A reward or credit that reads a header field the transactions decide (`gasUsed`, for one) is paid against the header
as it will be sealed: `prepareBlock` pays `payBlockReward` on `header.copy(gasUsed = executed)`, the header block
validation holds equal to the sealed one.

Reproduced divergence (B-167): the PoW builder set no base fee and skipped `applyBlockPreamble`. A PoW block mined on
an ETH config after London failed import's header rules (`HeaderExtraFieldsError(HefEmpty)`), and one mined at ETH's
DAO fork block lacked the drain import applies, so import refused its state root. Each was found only by importing
the mined block.

## Checklist for a new header field, pre-transaction step or block-level payment

1. List the builders: `grep -rn "prepareBlock(\|defaultPrepareHeader\|executeForProposer\|applyBlockPreamble" src/main`
   and EthSimulateService's header assembly. Each one must produce the field, run the step, or pay the payment.
2. Is the builder's gate the validator's or import's gate? Call the shared predicate; do not restate it.
3. Does a payment read a field execution fills in? Pay it on the sealed header.
4. Test by building through the real builder and importing through `ConsensusAdapter.evaluateBranchBlock`, as
   `PowBlockImportParitySpec` does. A hand-built header proves nothing about the builder.
