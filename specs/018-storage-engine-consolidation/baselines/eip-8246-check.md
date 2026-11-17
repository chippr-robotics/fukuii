# C0 T008: EIP-8246 against data-model §3.6 rule 6

**Source:** https://eips.ethereum.org/EIPS/eip-8246, read 2026-11-07. Status at that date: Last Call (Standards Track: Core).
Created 2026-05-01. Requires EIP-161 and EIP-6780. The text below is quoted from the EIP. **Pending:** beacon's read
(T008 stays open until beacon signs it).

## The EIP's specification (quoted)

> The behavior of `SELFDESTRUCT` changes as follows:
> 1. When `SELFDESTRUCT` is executed in the same transaction as the contract was created, and if the beneficiary address is
>    the executing account address, the balance of this account remains unchanged.
> 2. During transaction finalization, all accounts marked for selfdestruction, instead of being deleted, are modified as
>    follows: nonce is reset to 0, balance is unchanged, code is cleared, all storage is cleared.
>
> Note: All other behavior of `SELFDESTRUCT` is unchanged.
> Note: If the resulting balance is 0, the account is *empty* and is deleted from the state by EIP-161.

From Backwards Compatibility:
> a contract with nonzero final balance remains in the state as a balance-only account with empty code, empty storage, and
> nonce 0.

## Check against data-model §3.6 rule 6

- **The shape matches.** Rule 6 lists "storage cleared while the account stays (Amsterdam's `clearSelfDestructedAccounts`)" as
  one of the three ways a storage trie is replaced. EIP-8246's finalization is that shape: storage and code cleared, nonce 0,
  balance kept. When the balance is 0, the account is removed by EIP-161, which is rule 6's first case (account removal).
- **The trigger matches.** Rule 6 records an owner reset when the owner's storage root in the parent state is non-empty. Under
  EIP-6780, which EIP-8246 requires, only a contract created in the same transaction can be marked for selfdestruction. Its
  storage therefore exists only inside that transaction. The per-transaction persist never writes it (rule 1: a node created and
  removed before the persist produces no entry), so no reset is needed for it. The parent-root trigger still covers the cases
  that do need one:
  - destroy-then-recreate within a block, across transactions;
  - account removal under EIP-161.

  EIP-7610 forbids creating at an address whose storage is non-empty, so a same-transaction creation never starts from
  pre-existing rows.
- **No change to the design.** Track C's storage design did not depend on the text (plan open item 5). The text confirms it.
  The EEST `amsterdam/eip8246_selfdestruct_no_burn` fixtures (658, per open item 5) remain the executable check: T009 records
  their pass count on the pinned jar, and C3c records it again on the ported harness.
