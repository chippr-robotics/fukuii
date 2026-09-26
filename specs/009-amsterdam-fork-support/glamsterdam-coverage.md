# Glamsterdam (EIP-7773, Amsterdam EL) — coverage audit and impact analysis

beacon, 2026-09-26. Analysis only, no repository file was edited. Branch audited:
`claude/glamsterdam-release-tasks-1plkfl` @ `3b0609c` (origin/staging 0.8.5 + 5 commits of #1413).

```
VERIFY: ran sbt …                         — result: DID NOT RUN (instructed not to; build running in background)
VERIFY: ran BAL RLP re-encoder vs Platåberget headers (4 live blocks) — result: PASS, 4/4 hashes reproduced
VERIFY: ran EEST v21.0.0 tarball full listing (49,272 paths)          — result: done, counts below
```

---

## 0. Verdict

fukuii 0.8.5 + #1413 **cannot follow Platåberget today and will stop following Sepolia at 1791294816**, and
in one case (below) **cannot follow Sepolia today**. The blocking set is larger than #1409 records:

| # | Blocker | Kind | Evidence |
|---|---|---|---|
| B1 | No `engine_newPayloadV5` / `engine_forkchoiceUpdatedV4` / `engine_getPayloadV6`, not advertised in `exchangeCapabilities` | Engine API | `EngineApiController.scala:38-62`, `EngineApiService.scala:1687-1711` |
| B2 | **EIP-6110 deposits read only from the mainnet deposit contract; Sepolia’s is `0x7f02c3e3…295d`** — every Sepolia block with a deposit since Prague (2025-03) yields the wrong `requestsHash` | Consensus, **pre-existing, Sepolia today** | `BlockExecution.scala:584,601`; `sepolia-chain.conf` has no `deposit-contract-address`; geth `params.SepoliaChainConfig.DepositContractAddress` |
| B3 | EIP-8037 per-dimension block-capacity check missing: tx admitted against `tx.gas + receipt-sum ≤ gasLimit` | Consensus | `StdSignedTransactionValidator.scala:385-391` called with `acumGas` from `BlockPreparator.scala:684` |
| B4 | EIP-7976 (floor 64 gas/byte) and EIP-7981 (access-list data surcharge) missing | Consensus | `BlockPreparator.scala:989-993`, `EvmConfig.scala:330-359` |
| B5 | EIP-8246 missing: same-tx SELFDESTRUCT still burns and deletes | Consensus | `OpCode.scala:1668`, `BlockPreparator.scala:330-335` |
| B6 | SLOTNUM (0x4b) and DUPN/SWAPN/EXCHANGE (0xe6–0xe8) missing | Consensus | `EvmConfig.scala:61-72` (“Adds no opcodes”) |
| B7 | Type-4 transaction whose first frame fails loses its delegations on Amsterdam (EIP-2780 says they stay) | Consensus | `BlockPreparator.scala:448` vs EIP-2780 §“pre-execution phase” l.91, EELS `process_top_level` |
| B8 | EIP-7928 BAL: not computed, not validated, not stored; builder emits Prague-shaped header | Consensus (validation + production) | no BAL type anywhere in `src/main`; `EngineApiService.scala:1330` |

B1 blocks following behind any Gloas CL. B2–B7 make fukuii compute a wrong post-state/receipt/gas on the
first block that contains an affected transaction, i.e. it forks off. B8 means fukuii is not a validating node
for Amsterdam and cannot propose. Platåberget exercises the EIP-8037 reservoir on nearly every block (below),
which the devp2p fixture never did (R-1), so slice B's reservoir code is live-untested.

---

## 1. Sources read and pinned

| Source | Pin | Notes |
|---|---|---|
| EIP-7773 + all EL EIPs | ethereum/EIPs `master` `0b8184b1` | SFI EL list = 2780, 7708, 7778, 7843, 7928, 7954, 7976, 7981, 7997, 8024, 8037, 8038, 8246, 8282 (+CL 7688, 7732, 8045, 8061; networking 7975, 8070, 8136, 8159, 8189). Sepolia row = epoch 353024 / 1791294816. |
| EELS Amsterdam | ethereum/execution-specs `forks/amsterdam` (= default HEAD) `84e7d2c2` 2026-09-25 | `src/ethereum/forks/amsterdam/` |
| EELS devnet-8 (Platåberget) | `devnets/glamsterdam/8` `5747e76e` 2026-09-16 | diffed against HEAD, §1.1 |
| execution-apis | `main` `5bcdc34a`; `src/engine/amsterdam.md` last touched `465d1b9` 2026-09-13 | |
| go-ethereum | `master` `920c0777` 2026-09-24 | `eth/catalyst/api.go`, `beacon/engine/types.go`, `core/types/bal`, `core/block_validator.go`, `core/state/statedb.go`, `params/config.go` |
| Lighthouse | `unstable` `9ee3fd72` | `execution_layer/src/engine_api/{http,json_structures}.rs`, `lib.rs` |
| Live Platåberget | `https://rpc.plataberget.ethpandaops.io` (upstream `reth/v2.5.2-621f663`) | `eth_config`, blocks, `eth_getBlockAccessList` |
| Live Sepolia | `https://ethereum-sepolia-rpc.publicnode.com` | `eth_getCode` |

### 1.1 devnet-8 vs HEAD EELS — what changed after Platåberget’s spec was cut

All 14 EL EIPs are in both. Differences in `forks/amsterdam/` (full diff 777 lines):

| Change | Consensus effect | Recommendation |
|---|---|---|
| `block_access_lists.py`: writes netted against **state at the start of the block-access index** (`_index_start_account/_storage`, `remove_*_change`) instead of cumulative-after-previous-system-call (commit `a9792ab` 2026-09-17 “eip7928 extended coverage”) | Only when two writers share an index (index 0: 4788+2935; index n+1: withdrawals + 7002/7251/8282×2) **and** touch the same account/slot. None of the canonical predeploys overlap, so identical on real blocks; EEST `block_access_lists_cross_index` (10 files) encodes HEAD. EIP-7928 text agrees with HEAD (“compare each write against the storage value as of immediately before the current block_access_index”). | Implement HEAD semantics. The per-index diff design in §3.6 gives it for free. |
| `state_tracker.py`: `storage_clears` so reads after a storage wipe return 0 (`faf6637` 2026-09-23) | EELS-internal correctness fix (creation over storage-only account; EIP-161 delete of empty account with storage). A real client already reads 0. | No fukuii action beyond EEST. |
| `transactions.py`: `tx.gas > TX_MAX_TOTAL_GAS_LIMIT (2^32−1)` → invalid (`c335bc4` 2026-09-21) | Unreachable while block gas limit < 2^32−1 (the EIP-8037 capacity check already bounds `tx.gas ≤ gasLimit`). fukuii already has it (`StdSignedTransactionValidator.scala:364`). | none |
| EIP-7981 surcharge moved into a named term | Numerically identical (devnet-8: surcharge added to `access_list_cost` and to floor tokens; HEAD: `access_list_data_cost` added to both). | none |
| `runtime.py`: jumpdest analysis no longer special-cases DUPN/SWAPN/EXCHANGE immediates (`ad202d7`) | Identical jumpdest sets: devnet-8 only ever skipped a byte outside `0x5b..0x7f` (not JUMPDEST, not PUSHn). EIP-8024: “JUMPDEST analysis is unchanged.” | Do **not** change fukuii’s jumpdest analysis. |
| `OPCODE_SELFBALANCE = FAST_STEP`, `CALL_VALUE = ACCOUNT_WRITE + CALL_STIPEND` | refactor, same values (5, 11,300) | none |

`tests@v21.0.0` (`faf6637`) is an ancestor of HEAD; everything after it is test/benchmark infra. So v21 fixtures
encode HEAD semantics.

---

## 2. Per-EIP coverage

Status legend: **done** = rule in code with unit coverage (not EEST-verified); **partial**; **missing**.

### EIP-2780 Resource-based intrinsic gas — **partial**
- Evidence: `EvmConfig.transactionBaseCost` `EvmConfig.scala:373-390`; `AmsterdamGas.scala:76-84`;
  pre-execution phase `ProgramContext.scala:62-138`; authorization charges `BlockPreparator.scala:842-872`.
- Reference (EELS `transactions.calculate_intrinsic_cost`, `interpreter.create_evm`, `eoa_delegation.set_delegation`):
  `TX_BASE 12000`; recipient `COLD_ACCOUNT_ACCESS 3000` (non-self call) or `CREATE_ACCESS 12000` (creation) + initcode 2/word;
  `TX_VALUE_COST 6000` iff `value>0` and not self-transfer and not creation; `EXECUTION_PER_AUTH_BASE_COST 7816` per tuple.
  Runtime (top frame): per valid authorization `NEW_ACCOUNT` state gas if authority does not exist, `ACCOUNT_WRITE 9000` once per authority
  not already in `accounts_with_paid_writes` (sender; recipient if value-bearing), `AUTH_BASE 23×1530` once when a net-new indicator is set;
  then `commit_state_gas`; then value-transfer-to-dead-recipient `NEW_ACCOUNT`, creation `NEW_ACCOUNT` if pre-state account is EMPTY,
  delegation-target access (warm 100 / cold 3000).
- **Divergence 1 (Critical, B7)**: `rollbackWorld = if amsterdamActive then checkpointWorldState` (`BlockPreparator.scala:448`) discards
  delegations and authority nonce bumps on **any** frame error. EIP-2780 l.91: “if the transaction goes out-of-gas after entering the first EVM
  frame, the delegations applied during the pre-execution phase stay in place”; EELS: `process_call` snapshots after `create_evm`.
  Only a pre-execution failure (`preExecutionOutOfGas`, AddressCollision) rolls back to the checkpoint.
- **Divergence 2 (Warning)**: `amsterdamAuthorizationCharges` validates every tuple against the tx-start world (`applyAuthorization(auth, world)`),
  EELS validates sequentially (second tuple for the same authority with nonce n+1 is valid after the first applies). Also uses
  `isAccountDead` where EELS uses `account_exists` (existence, not emptiness). Also cannot model an OOG part-way through the list
  (EELS charges each tuple as it is applied; later authorities are never loaded, which matters for BAL).
- Edge cases for tests: self-transfer 12,000; creation with value (no TX_VALUE_COST); authority == sender / == recipient; duplicate
  authorities; set→clear→set; OOG in auth processing (all delegations reverted, recipient not accessed); OOG after frame entry
  (delegations kept, state gas for them not refilled). EEST `eip2780_reduce_intrinsic_tx_gas` (59 files), `authorization_charges/*`.

### EIP-8037 State creation gas — **partial**
- Evidence: `AmsterdamGas.scala:34-104`; `ProgramState`/`VM` reservoir; `BlockResult.scala` (`gasUsed = max`); `BlockPreparator.scala:520-534`.
- Reference: `COST_PER_STATE_BYTE 1530`; bytes NEW_ACCOUNT 120, STORAGE_SET 64, AUTH_BASE 23; `TX_MAX_GAS_LIMIT 2^24`,
  `TX_MAX_TOTAL_GAS_LIMIT 2^32−1`; `allocate_evm_gas`; LIFO refill (`credit_state_gas_refund`), `repay_state_gas_spill` on child merge
  (`132d114`, 2026-09-04); `settle_transaction_gas` (execution dimension = `max(before_refund − state, floor)`, state clamped ≥0);
  header `gasUsed = max(block_gas_used, block_state_gas_used)`.
- **Missing (Critical, B3)**: `gas.check_block_gas_capacity`: `min(TX_MAX_GAS_LIMIT, tx.gas) > gasLimit − block_exec_used` → invalid;
  `tx.gas > gasLimit − block_state_used` → invalid. fukuii checks `tx.gas + acumGas(receipt sum) ≤ gasLimit`, which both rejects valid
  blocks (receipt sum can approach 2×gasLimit on Amsterdam) and accepts invalid ones (pre-refund execution can exceed the post-refund sum).
  The builder repeats the wrong rule (`EngineApiService.scala:1213`).
- **Note**: system calls. EELS: `execution_gas_grant = 30,000,000`, `state_gas_reservoir = 16 × 97,920 = 1,566,720`.
  fukuii: `startGas = 31,566,720`, reservoir 0 (`BlockExecution.scala:543`). Same total; different GAS-opcode view and execution ceiling.
  Unobservable for canonical predeploys; match EELS exactly anyway.
- **Live evidence the reservoir is exercised**: Platåberget block 275,654 (`0x434c6`), tx `0x33e82ff8…2680`: type-2 creation, `gas =
  130,000,000 > 2^24`, 65,552-byte initcode deploying 65,536 bytes. Header `gasUsed = 100,453,680 = 65,536×1530 + 120×1530` (state
  dimension); receipt `101,563,324` (execution 1,109,644). Blocks `0x434a1…0x434c5` all carry the same 100,453,680. R-1’s “reservoir is
  never used” holds for the devp2p fixture only; on Platåberget it is used on nearly every block.
- EEST `eip8037_state_creation_gas_cost_increase` (268 files).

### EIP-8038 State-access repricing — **done**
- `EvmConfig.scala:553-585`: COLD_ACCOUNT_ACCESS 3000, access list 2900/2000, `R_sclear 11616`, `CALL_VALUE 11300`, `CREATE_ACCESS 12000`,
  `G_newaccount 0`, `G_codedeposit 0`; STORAGE_WRITE 10000 in `AmsterdamGas`. Matches EELS `GasCosts`. EEST 88 files.

### EIP-7778 Block gas accounting without refunds — **done**
- `BlockPreparator.scala:527-534`; matches `settle_transaction_gas`. EEST 9 files (the fixture never earns a refund, R-3).

### EIP-7708 ETH transfer logs — **done**
- Tx/CALL `VM.scala:112-121`, CREATE `VM.scala:272`, SELFDESTRUCT `OpCode.scala:1689-1692`; emitter `0xff…fe`, topic `0xddf252ad…`, 32-byte
  big-endian amount, skipped for zero value and self-transfer (EELS `emit_transfer_log`). After EIP-8246 lands, re-check
  selfdestruct-to-self (no log, no burn). EEST 38 files.

### EIP-7843 SLOTNUM — **partial** (header only)
- Header field 22 carried (`BlockHeader.scala:221`), but no `slotNumber`/`blockAccessListHash` accessor, no opcode, no Engine plumbing.
- Reference: `SLOTNUM = 0x4B`, gas `BASE = 2`, pushes header `slot_number` (uint64). Payload attributes carry it; payload/header carry it;
  EL does not validate it against anything (EELS `validate_header` has no slot rule). EEST 10 files.

### EIP-7928 Block-level access lists — **missing** (header hash only). See §3.

### EIP-7954 Contract size 64 KiB / initcode 128 KiB — **done**
- `EvmConfig.scala:411-420`, `AmsterdamGas.scala:98-101`. Live witness: the 65,536-byte deployments above. EEST 22 files.

### EIP-7976 Calldata floor 64/64 — **missing** (B4)
- Reference (EELS `calculate_intrinsic_cost`): `floor = TX_BASE + recipient_execution_gas + len(data)×4×16 + access_list_data_cost`
  (i.e. **64 gas per byte, zero or not**, on the EIP-2780 decomposed base). Validity: `tx.gas ≥ max(intrinsic, floor)` and both ≤ 2^24.
- fukuii: `tokens = nonzero×4 + zero; base + tokens×10` (`BlockPreparator.scala:989-993`).
- **Related pre-existing gap (Warning)**: no `tx.gas ≥ floor` validity check exists (grep of `StdSignedTransactionValidator`, `SignedTransaction.coversIntrinsicGas`); only the 2^24 cap uses the floor. Pre-Amsterdam this is EIP-7623 (Prague); at 64/byte it becomes much easier to hit.
- EEST 21 files.

### EIP-7981 Access-list data cost — **missing** (B4)
- Reference: `ACCESS_LIST_ADDRESS_FLOOR_TOKENS 80`, `…STORAGE_KEY_FLOOR_TOKENS 128`, ×`TX_DATA_TOKEN_FLOOR 16` = **1,280 per address,
  2,048 per key**, added to **both** intrinsic execution gas and floor; per-entry charges stay 2900/2000 (EIP-8038). EEST 19 files.

### EIP-7997 Deterministic factory — **no client code** (correct)
- EIP: “Client software MUST NOT check for the existence of the contract at the fork boundary.” Sepolia has code at
  `0x4e59b448…956C` (verified live).

### EIP-8024 DUPN/SWAPN/EXCHANGE — **missing** (B6)
- Reference (EELS `vm/instructions/stack.py`, `vm/stack.py`): `DUPN 0xE6`, `SWAPN 0xE7`, `EXCHANGE 0xE8`, gas 3 each, immediate
  `x = code[pc+1]` (0 past end of code), `pc += 2`.
  DUPN/SWAPN: `x ∈ [91,127]` → exceptional halt; `n = (x+145) % 256` (17..235); DUPN needs `n ≤ len(stack)`, pushes `stack[-n]`;
  SWAPN needs `n+1 ≤ len(stack)`, swaps top with `stack[-(n+1)]`.
  EXCHANGE: `x ∈ [82,127]` → halt; `k = x ^ 143; q,r = divmod(k,16); (n,m) = (q+1,r+1) if q<r else (r+1, 29−q)`; swap `stack[-(n+1)]`,
  `stack[-(m+1)]`, needs `max(n,m)+1 ≤ len(stack)`. Jumpdest analysis unchanged. EIP-8024 lists 12 assembly and 10 execution vectors.
  EEST 66 files.

### EIP-8037/8038/2780 interaction constants cross-checked
`REFUND_STORAGE_CLEAR = (10000+2100)×4800/5000 = 11,616`; `EXECUTION_PER_AUTH_BASE_COST = 101×16+3000+3000+2×100 = 7,816`. Both match fukuii.

### EIP-8246 Remove SELFDESTRUCT burn — **missing** (B5)
- Reference: same-tx selfdestruct-to-self keeps balance; at tx finalization each account in `accounts_to_delete` gets nonce 0, code cleared,
  storage cleared, **balance kept** (EELS `clear_account_preserving_balance`; geth `finaliseAmsterdam`), then EIP-161 deletes it if empty.
- fukuii: `removeAllEther` on self-beneficiary (`OpCode.scala:1668`) and `world.deleteAccount` (`BlockPreparator.scala:330-335`).
- EEST 7 files; EIP lists 17 cases.

### EIP-8282 Builder execution requests — **done in execution**, eth_config missing
- `BlockExecution.scala:609-647` (addresses `0x0000bFF4…8282`, `0x000064D6…8282`, types 0x03/0x04, order after 0x01/0x02),
  `requireRequestPredeploysPresent` covers them. Sepolia has code at both (verified live). `tasks.md` T043–T047 are unticked although the code
  and `AmsterdamBuilderRequestsSpec` exist — bookkeeping only.
- Missing: `eth_config` `BUILDER_DEPOSIT_CONTRACT_ADDRESS` / `BUILDER_EXIT_CONTRACT_ADDRESS` (`EthInfoService.scala:280-292`; geth
  `ActiveSystemContracts`; Platåberget’s live `eth_config` lists both). EEST 22 files.

---

## 3. EIP-7928 in depth

### 3.1 Structure and RLP (verified against live Platåberget)
```
BlockAccessList = [AccountChanges, ...]
AccountChanges  = [address(20 bytes),
                   [[slot:uint256, [[index:uint32, value:uint256], ...]], ...],   # storage_changes
                   [slot:uint256, ...],                                          # storage_reads
                   [[index:uint32, post_balance:uint256], ...],
                   [[index:uint32, new_nonce:uint64], ...],
                   [[index:uint32, new_code:bytes], ...]]
header.block_access_list_hash = keccak256(rlp(BAL)); empty = keccak256(0xc0) = 0x1dcc4de8…9347
```
All integers are **minimal big-endian RLP** (0 → `0x80`), including slot keys and storage values (EELS types them `U256`; geth `*uint256.Int`).
Independent check: I re-encoded the JSON from `eth_getBlockAccessList` for blocks `0x434c6`, `0x434b3`, `0x434bf`, `0x434ad` and
reproduced each header’s `blockAccessListHash` exactly (e.g. `0x3c1e78a5…5129`, 65,994 bytes, 10 accounts; `0x69e8e8f2…237c`, 21
accounts). These are ready-made codec vectors; they are committed as test resources with the codec (WI-2).

Observed in the live BALs, matching EELS: 7002/7251/8282 predeploys appear with 4 `storage_reads` (slots 0–3; the dequeue’s no-op writes
are reads); 2935 one change at index 0, 4788 two changes at index 0; a builder deposit at tx 1 writes queue slots 1,3–9 at index 1 and
the dequeue resets slots 1,3 at index n+1; coinbase has a balance change at every tx index; withdrawal recipients at n+1;
`SYSTEM_ADDRESS` absent; addresses touched without change carry empty lists.

### 3.2 What is recorded (EELS `state_tracker` + opcode ordering)
- **Account “read”** = every `get_account/get_account_optional` (`BlockState.account_reads`), surviving reverts. **Storage read** = every
  `get_storage` (SLOAD, SSTORE’s current-value read). Transient storage, `get_storage_original`, code-by-hash lookups are not recorded.
- **Gas before access** (EIP-7928 §“Gas Validation Before State Access”; EELS): the target is recorded only once the state-independent cost
  is affordable:
  BALANCE/EXTCODESIZE/EXTCODEHASH `access`; EXTCODECOPY `access+memory+copy`; CALL/CALLCODE `access+memory+CALL_VALUE(if value)`;
  DELEGATECALL/STATICCALL `access+memory`; delegated code address only after `+delegation access` is affordable;
  SLOAD `access`; SSTORE `max(access, CALL_STIPEND+1)`; SELFDESTRUCT `5000 + cold`.
  CREATE/CREATE2 destination: only after the preflight (sender balance ≥ endowment, nonce ≠ 2^64−1, depth) — not gas.
- **Top level**: sender (always); recipient unless authorization processing halts first; creation target always; delegated code address
  after its access charge; every authority whose signature recovers (chain id and nonce-bound checks first); coinbase for every
  transaction (fee credit even when 0); not the EIP-2930 list, not pre-warmed precompiles/coinbase unless accessed.
- **System**: index 0 = EIP-4788 then EIP-2935 as **EVM calls** (target read even with no code: `bal_4788_absent_contract`); index n+1 =
  all withdrawals in one state (recipient read even for amount 0), then 7002, 7251, 8282 deposit, 8282 exit. `SYSTEM_ADDRESS` excluded.
- **Changes** per index, net against the index-start value (HEAD): balance, nonce, code (post bytes; delegation indicator or empty on clear),
  storage (value ≠ index-start value; equal ⇒ read). Selfdestructed-in-tx: writes become reads; EIP-8246 balance kept.
- **Ordering**: accounts by address; slots numerically (uint256 order = lexicographic over 32-byte keys); changes by index; reads exclude
  written slots. Unique addresses/slots/indices; `SlotChanges` non-empty; indices ≤ n+1.
- **Size limit**: `addresses + unique slots (reads ∪ writes) ≤ gasLimit // 2000` (EELS `validate_block_access_list_gas_limit`).
  200M gas → 100,000 items.

### 3.3 Validation
- Import (devp2p/ChainImporter): execute, build BAL, check size, compare `keccak(rlp)` with header (EELS `execute_block`; geth `ValidateState`).
- `engine_newPayloadV5`: `blockAccessList` missing → `-32602`; undecodable (incl. empty byte string) → `INVALID`, `latestValidHash null`;
  header hash = `keccak(raw bytes)`; block hash check; execute; local hash ≠ header → `INVALID`. geth decodes before the block-hash check
  so a broken encoding is reported as such (`beacon/engine/types.go:271-289`). Strict decoding needed (fukuii’s generic BigInt decoder
  tolerates leading zeros).
- Genesis with Amsterdam active: `blockAccessListHash = 0x1dcc4de8…`, `slotNumber = genesis.slotNumber or 0` (geth `genesis.go:556-561`;
  EEST `FixtureHeader`). This removes the `GenesisDataLoader.scala:189-202` “blocked on slice C” gap without any BAL machinery.
- Storage: BALs retained ≥ 3533 epochs; served via `getPayloadBodiesByHash/RangeV2` (null pre-Amsterdam/pruned) and eth/71.

### 3.4 Why a passive recorder does not fit fukuii
EELS and geth record every state lookup. fukuii computes full dynamic gas **before** the gas check (`OpCode.execute` → `calcGas` →
`varGas` reads `isAccountDead`, delegation code, `accountExists`; `OpCode.scala:274-297`, SELFDESTRUCT `varGas` `OpCode.scala:1705-1731`).
A lookup-level recorder would therefore include targets of opcodes that OOG on the pre-state cost — exactly what the
`bal_*_and_oog_before_target_access`, `bal_sload_and_oog`, `bal_sstore_and_oog` fixtures reject. The world state is also an immutable
proxy, so reads leave no trace. Recording must be explicit and spec-shaped.

### 3.5 Proposed design (fits existing code)
1. **`domain/BlockAccessList.scala`** (new, ETH-only): case classes above; canonical encoder; strict decoder (`Either[String, BAL]`:
   address = 20 bytes, index ≤ u32, nonce ≤ u64, uint ≤ 32 bytes, no leading zeros, strictly sorted/unique); `hash`; `itemCount`;
   `EmptyHash`. Codec tests = the 4 live vectors + negatives.
2. **Access channel through the VM**: `BalAccess(accounts: Set[Address], slots: Set[(Address, UInt256)])` on `ProgramState` and
   `ProgramResult`, merged into the parent on **success and failure** (unlike `accessedAddresses`). Populated only when
   `config.amsterdamEnabled`.
3. **Recording in `OpCode.execute`** (Amsterdam only): new `balTargets(state)` and `balPreStateGas(state)` on the 13 accessing opcodes.
   If `baseGas + preStateGas ≤ state.gas`, record targets before the existing full-gas check, so an OOG on the state-dependent remainder
   still records. CALL-family: record `to`; record the delegated address only if `preStateGas + delegationAccess ≤ gas`.
   CREATE/CREATE2: record in `CreateOp.exec` after the preflight at `OpCode.scala:1152-1160`. SLOAD/SSTORE record the slot.
4. **Top level** (`BlockPreparator.executeTransaction` / `ProgramContext`): sender, coinbase, creation target, recipient (with the
   authorization-OOG exception), delegated target, recovered authorities (respecting an OOG part-way through the list).
5. **Changes by per-index diff**: for index i keep `startWorld` (before the unit) and `endWorld` (after persist); for every touched
   address compare balance/nonce/codeHash (code bytes from `endWorld.getCode`); for every touched slot compare `getStorage(addr).load(slot)`.
   Every write in EELS is preceded by a recorded read of the same account/slot, so touched ⊇ written. Index 0 = 4788+2935; index i = tx;
   index n+1 = withdrawals + four request calls. One diff per index yields HEAD’s netting.
6. **System calls on Amsterdam**: run 4788/2935 through the VM (SYSTEM_ADDRESS, 30M + 1,566,720 reservoir), as EELS does, instead of the
   direct write in `BlockExecution.scala:246-331`; keep the direct write pre-Amsterdam (Chesterton’s fence: it fixed hive graphql state roots
   when the contract is absent). The direct write only checks code presence, not code identity, so it diverges from EELS when a test puts
   other code there (`bal_pre_execution_call_failure_keeps_read_drops_write`, `bal_2935_selfdestruct_to_history_storage`). Record withdrawal
   recipients even when `amount == 0` (`BlockExecution.scala:434` skips them for state, correctly).
7. **Assembly** in `BlockExecution.executeBlock`: build, size-check, then (follower) compare with `header.blockAccessListHash` in
   `executeAndValidateBlockFull` next to `validateRequestsHash`, and in the `executeBlockNoValidationWithRequests` path used by
   ChainImporter; (proposer) return bytes in `BlockResult` for `sealProposerBlock`.
8. **Persistence**: new key-value storage `blockHash → BAL bytes` (via `vault` for the column family), written after a block is validated.

### 3.6 Riskiest points
1. Exact pre-state gas per opcode in fukuii’s gas model (dynamic gas already includes state-dependent terms) — one mistake is a
   spurious/missing BAL entry and a rejected valid block. Mitigate with the 38 `block_access_lists_opcodes` fixtures before anything else.
2. Shared signatures: `ProgramState`, `ProgramResult`, `OpCode.execute`, `VM`, `BlockPreparator`, `BlockExecution` (forge sign-off).
3. Top-level ordering (authorizations → recipient → delegation) and partial authorization OOG.
4. Replacing the 4788/2935 direct write on Amsterdam.
5. Performance at 200M gas: per-index diffs re-read touched state from the start world (MPT). Measure on a Platåberget block; if slow,
   capture the index-start value on first touch.
6. Fork-choice interplay: a BAL mismatch must mark the payload INVALID through the existing invalid-ancestor bookkeeping.

---

## 4. Engine API

### 4.1 What a Gloas CL calls on Platåberget (Lighthouse `unstable`, read from source)
- `engine_exchangeCapabilities` at startup; **methods are chosen from the advertised set** and Gloas returns `RequiredMethodUnsupported`
  when `engine_forkchoiceUpdatedV4` / `engine_getPayloadV6` are not advertised (`lib.rs`). fukuii advertises neither
  (`EngineApiService.scala:1687-1711`).
- `engine_newPayloadV5` every block: `[ExecutionPayloadV4, expectedBlobVersionedHashes, parentBeaconBlockRoot, executionRequests]`.
- `engine_forkchoiceUpdatedV4` every head change: `[ForkchoiceStateV1, PayloadAttributesV4|null, custodyColumns|null]` (**three** params;
  LH sends `slotNumber` and `targetGasLimit` as required u64).
- `engine_getPayloadV6` when proposing.
- `engine_getBlobsV2/V3/V4` only if advertised (optional).
- `engine_getPayloadBodiesByHashV2` (Gloas payload reconstruction) only if advertised; LH does not use ByRange.
- `engine_getClientVersionV1`.
Prysm/Teku/Nimbus/Lodestar/Grandine not read; the execution-apis spec is the contract.

### 4.2 Shapes and errors (execution-apis `amsterdam.md`)
- `ExecutionPayloadV4` = V3 + `blockAccessList: DATA` + `slotNumber: QUANTITY(64)`.
- `PayloadAttributesV4` = V3 + `slotNumber` + `targetGasLimit` (“MUST use” when building; geth: `CalcGasLimit(parent, target)`, optional pointer).
- `newPayloadV5`: `-38005` if timestamp not Amsterdam; `-32602` if `blockAccessList` missing (geth also for missing `slotNumber`); undecodable
  BAL → `INVALID`, `latestValidHash null`; otherwise as V4.
- `forkchoiceUpdatedV4`: attributes not matching V4 → `-38003`; timestamp not Amsterdam → `-38005`; `custodyColumns` non-null and not 16-byte
  DATA → `-32602`; custody update must not affect fork choice.
- `getPayloadV6`: returns `{executionPayload: V4, blockValue, blobsBundle: BlobsBundleV2, shouldOverrideBuilder, executionRequests}`;
  `-38005` if the built payload is not Amsterdam.
- `getPayloadBodiesByHashV2/ByRangeV2`: `ExecutionPayloadBodyV2 = {transactions, withdrawals, blockAccessList: DATA|null}`.
- `getBlobsV4`: `[hashes, indices_bitarray(16 bytes)]` → cells/proofs, partial allowed, `-38004` too large.
- Old methods: `newPayloadV4`, `getPayloadV5`, `forkchoiceUpdatedV3` → `-38005` at Amsterdam timestamps.

### 4.3 fukuii status and extension points
| Item | Status | Where |
|---|---|---|
| newPayloadV4 `-38005` at Amsterdam | missing (commented TODO) | `EngineApiController.scala:143-145` |
| getPayloadV5 refuses Amsterdam | done | `EngineApiController.getPayloadForkError` `:765` |
| forkchoiceUpdatedV3 `-38005` at Amsterdam | done | `payloadAttributesVersionError` `:711` (`inV3Window`) |
| newPayloadV5 / FCUv4 / getPayloadV6 / bodies V2 / getBlobsV3/V4 | missing | dispatch `:38-62` |
| Payload decode (`blockAccessList`, `slotNumber`) | missing | `decodeExecutionPayload` `:572`, `ExecutionPayload` `EngineApiDomain.scala:11-40` |
| Attributes decode (`slotNumber`, `targetGasLimit`) | missing | `decodePayloadAttributes` `:631`, `PayloadAttributes` `EngineApiDomain.scala:42-50` |
| `payloadToBlock` Amsterdam header | missing (Prague branch) | `EngineApiService.scala:1765-1831` |
| Payload encode (`blockAccessList`, `slotNumber`) | missing | `blockToExecutionPayload` `:852` |
| Builder Amsterdam header, slot, target gas limit, per-dimension fit | missing | `sealProposerBlock` `EngineApiService.scala:1261-1460` (`:1330`), `proposerGasLimit` `:1107`, `buildLeniently` `:1213` |
| exchangeCapabilities | missing new entries | `EngineApiService.scala:1687-1711` |
| getBlobsV1 `-38005` post-Osaka | returns nulls (minor) | `EngineApiController.scala:474` |

---

## 5. #1413-review gaps, re-verified

| Gap | Status on this branch | Evidence |
|---|---|---|
| EIP-6110 deposits only from mainnet contract | **still present, and broken on Sepolia today** | `BlockExecution.scala:584,601`; `sepolia-chain.conf` lacks `deposit-contract-address`; only `eth_config` reads it (`EthInfoService.scala:287`) |
| eth_config lacks BUILDER_* | **still present** | `EthInfoService.scala:280-292` |
| txpool pre-filter uses Osaka rules after Amsterdam | **still present** (picks latest configured of Osaka…Shanghai, never Amsterdam, and “latest configured” rather than “active now”) | `SignedTransaction.scala:575-600` (`:588`) |
| trace/debug replays use Osaka rules | **partly**: EvmConfig is timestamp-aware (`BlockPreparator.scala:226,254`), but replays run bare `runVM` (no fee/refund payment, no Amsterdam authorization charges, no floor) and `binarySearchGasEstimation` floors at 21,000 | `StxLedger.scala:61-89,149-173` |
| peer fork-id validation uses best block number | **still present** in eth/68, 69, 70+ handshakes and ENR/DNS filters: timestamp forks compared against a height, so they never pass; stale (non-upgraded) peers are accepted | `EthNodeStatus68ExchangeState.scala:126`, `…69…:109`, `…70…:111`, `ForkIdTag.scala:53-57`, `DnsDiscovery.scala:327-333`, `ForkIdValidator.scala:49-65` |
| discovery ENR fork id with head timestamp 0 | **still present**: ENR advertises the pre-Shanghai id (on Platåberget, the genesis id with `next = 1787212224`), which geth’s discovery filter rejects as stale | `ForkIdTag.scala:41` |
| payload builder emits Prague-shaped header at Amsterdam | **still present** | `EngineApiService.scala:1330` |
| EngineApiService blob-schedule doc comment stale | **still present** (says BPO1 8/12, BPO2 12/18; code and geth are 10/15 and 14/21) | `EngineApiService.scala:1872-1885` vs `:1901-1914` |

From #1409 comments: BAL computation/validation missing (confirmed §3), SLOTNUM missing (confirmed), 7976/7981/8024/8246 missing (confirmed),
no Amsterdam Engine API (confirmed), EIP-8070 messages only (confirmed; `getBlobsV4` absent), `RemoteStatus` keeps `supportsSnap: Boolean`
(`NetworkPeerManagerActor.scala:1204`), eth/71 serving needs stored BALs (no BAL store exists).

Tasks.md “case _” fallbacks flagged in slice A are fixed (`EthInfoService.scala:435`, `EngineApiController.scala:823,879`,
`EthSimulateService.scala:375`); `EthSimulateService.scala:501` still builds `HefPostPrague` for simulated blocks (RPC only).

---

## 6. Test vectors

| Release (execution-specs) | Asset | Size / date | Amsterdam content |
|---|---|---|---|
| **`tests@v21.0.0`** (`faf6637`) | `fixtures.tar.gz` | 998,114,541 B, 2026-09-23 | `blockchain_tests/for_amsterdam/**` 3,277 JSON + `for_bpo2toamsterdamattime15k` 58; `blockchain_tests_engine/for_amsterdam` 3,280 + 59; `blockchain_tests_engine_x` 3,280 + 59; `state_tests/for_amsterdam` 2,854; `transaction_tests/for_amsterdam` 22; `blockchain_tests_sync/for_amsterdam` 4 |
| `tests-glamsterdam-devnet@v8.1.4` (`7341820`) | `fixtures_glamsterdam-devnet.tar.gz` | 946,283,655 B, 2026-09-03 | same layout; devnet-8 semantics |
| `tests-bal@v7.3.2` | `fixtures_bal.tar.gz` | 2026-06-15 | superseded |

Per-EIP Amsterdam fixture files in v21 `blockchain_tests/for_amsterdam/amsterdam/`: 2780: 59, 7708: 38, 7778: 9, 7843: 10, 7928: 202
(incl. 33 invalid-BAL, 38 opcode-ordering, 10 cross-index, 18 EIP-7702), 7954: 22, 7976: 21, 7981: 19, 7997: 21, 8024: 66, 8037: 268,
8038: 88, 8246: 7, 8282: 22. Every other `for_amsterdam/<fork>/` directory re-fills older tests under Amsterdam rules, so each block
also checks gas, state root and BAL hash. Blocks carry `rlp` (23-field header) plus a debug `blockAccessList` JSON; engine fixtures carry
`params` for `newPayloadV5`/`FCUv4`.

**fukuii runner**: `src/it/scala/com/chipprbots/ethereum/ethtest/` (IntegrationTest config) consumes ethereum/tests-style BlockchainTest
JSON (`EthereumTestsAdapter`, `TestConverter`), but maps networks only up to `"osaka"` (`TestConverter.scala:219-450`) and builds
headers only up to `HefPostPrague` (`TestConverter.scala:65-72`); no `for_<fork>` layout, no engine format, not wired into
`testComprehensive` with fetched EEST (`build.sbt:548-557`). The fastest route to the engine fixtures is hive `eels/consume-engine`
and `consume-rlp` against the same release; `hive/fukuii/fukuii.sh:51,154` already maps `HIVE_AMSTERDAM_TIMESTAMP`.

**Live golden vectors**: 4 Platåberget BALs with reproduced hashes (codec); Platåberget block 275,654 (reservoir + 64 KiB deployment gas);
Sepolia/Platåberget fork ids from `ForkIdSepoliaSpec`/`ForkIdPlatabergetSpec`. A replay harness that pulls Platåberget blocks and BALs over
RPC and imports them offline would validate ~226k real Amsterdam blocks (fork ≈ slot 49,152; head 275,654).

---

## 7. ETC isolation per change

| Change | Touches ETC path? | How ETC stays byte-identical |
|---|---|---|
| Deposit contract per chain | No (Prague-gated) | only reached when `isPragueTimestamp`; ETC configs have no Prague |
| EIP-8037 capacity check | **Shared validator** | branch on `isAmsterdamTimestamp(header)`; old `tx.gas + acumGas` check untouched; new counters already threaded (`BlockPreparator.scala:642-643`) |
| EIP-7976/7981 | **Shared** `calcFloorDataGas` (ETC Olympia uses EIP-7623) | new Amsterdam-only floor function; existing one and `EIP7623FloorDataGasSpec` unchanged; access-list surcharge inside `if amsterdamEnabled` |
| EIP-8246 | **Shared** SELFDESTRUCT and `deleteAccounts` | gate on `config.amsterdamEnabled` / `isAmsterdamTimestamp`; ETC Olympia (EIP-6780) path unchanged |
| Type-4 rollback / sequential auth | Shared 7702 code (ETC Olympia has Type-4) | edits inside existing `amsterdamActive` branches only |
| SLOTNUM / DUPN / SWAPN / EXCHANGE | New `AmsterdamOpCodes` table | reachable only via the timestamp cascade; assert no ETC table contains 0x4b/0xe6–0xe8 (OpCodeContractSpec) |
| BAL access channel | **Shared signatures** `ProgramState`, `ProgramResult`, `OpCode.execute`, `VM` | empty default, recording only when `amsterdamEnabled`; forge sign-off before the signature edit; ETC suites (`EthashBlockHeaderValidatorSpec`, `PoWBlockHeaderValidatorSpec`, Olympia suites) unchanged |
| 4788/2935 as VM calls | 2935 has an ETC Olympia branch (`BlockExecution.scala:306-331`) | Amsterdam-only branch; ETC branch untouched |
| BAL store, Engine API, eth_config, builder | ETH-only | Engine API unused on ETC; eth_config ETC path is `blockNumberedConfig` |
| Fork-id head timestamp | **Shared** handshake and discovery | `gatherTimestampForks` is empty for ETC configs, so adding a timestamp argument cannot change ETC ids; keep ETC/Mordor ForkId specs byte-identical |
| txpool pre-filter | ETH branch only (`networkType == ETH`) | ETC uses the two-argument cascade |

---

## 8. Implementation plan (GitHub sub-issues, dependency order)

Required to follow Platåberget/Sepolia first; each item is bucket C except where noted.

**WI-1 — Sepolia EIP-6110 deposit contract (S). Ship as a 0.8.x hotfix now.**
Files: `sepolia-chain.conf` (+`deposit-contract-address = "0x7f02c3e3c98b133055b8b348b2ac625669ed295d"`), `BlockExecution.collectDepositRequests`
(use `blockchainConfig.depositContractAddress.getOrElse(mainnet)`). Acceptance: a unit test with a real Sepolia deposit block (receipts from
RPC) reproduces its `requestsHash`; mainnet/hive behaviour unchanged; ChainConfigMatrixSpec pins the address. Deps: none.

**WI-2 — BAL codec and Amsterdam genesis (S).** `domain/BlockAccessList.scala`; `BlockHeader.blockAccessListHash/slotNumber` accessors;
`GenesisDataLoader` Amsterdam branch (`0x1dcc4de8…`, slot 0/genesis). Acceptance: 4 live vectors hash-equal; strict-decode negatives;
EEST for_amsterdam genesis hashes match. Deps: none.

**WI-3 — EEST Amsterdam harness (M).** Extend `ethtest` (`"Amsterdam"`, `"BPO2ToAmsterdamAtTime15k"`, 23-field headers, `for_<fork>` dirs,
`expectException`); CI job fetching `tests@v21.0.0/fixtures.tar.gz` (filter `for_amsterdam`, `for_bpo2toamsterdamattime15k`); hive
`eels/consume-rlp` + `consume-engine` runs. Acceptance: runner reports per-EIP pass counts (baseline recorded, no prediction). Deps: WI-2.
Non-consensus (bucket A/B).

**WI-4 — EIP-8037 block-capacity check (S).** Validator per-dimension rule; builder filter. Acceptance: EEST 8037 capacity cases; unit
vectors both directions (valid block with receipt sum > gasLimit − tx.gas; invalid block with pre-refund execution overflow). Deps: none.

**WI-5 — EIP-7976 + EIP-7981, plus `tx.gas ≥ floor` validity (S–M).** Amsterdam intrinsic surcharge and floor; validity; txpool filter uses the
same function. Acceptance: EEST 7976 (21) and 7981 (19); Platåberget tx `0x33e82ff8…` gives execution dimension 4,219,328. The
pre-Amsterdam EIP-7623 validity check is a separate, separately-reviewed commit (it changes live Prague/Osaka behaviour). Deps: none.

**WI-6 — EIP-8246 (S–M).** Acceptance: EEST 8246 (7) and `for_amsterdam/cancun/eip6780*` re-fills; the 17 EIP cases. Deps: none.

**WI-7 — EIP-2780/7702 fixes (S).** Keep delegations on in-frame failure; sequential validation and per-tuple OOG for charges; `account_exists`.
Acceptance: EEST `authorization_charges/*`, `for_amsterdam/prague/eip7702_set_code_tx/*`. Deps: none.

**WI-8 — SLOTNUM + EIP-8024 opcodes (M).** New `AmsterdamOpCodes`. Acceptance: EEST 7843 (10), 8024 (66), the EIP-8024 vectors; opcode-table
contract test for ETC tables. Deps: WI-2 (slot accessor).

**WI-9 — Engine API Amsterdam surface (M).** newPayloadV5, FCUv4 (3 params, custodyColumns validated and ignored), getPayloadV6,
newPayloadV4 `-38005`, capabilities, `payloadToBlock` Amsterdam, attributes and payload codecs, payload id includes slot and target.
Acceptance: controller specs per error code (`-32602/-38003/-38005/INVALID`); hive engine suites; EEST `blockchain_tests_engine/for_amsterdam`
via consume-engine. Deps: WI-2. **With WI-1, WI-4…WI-9 fukuii can follow Platåberget/Sepolia behind a Gloas CL, trusting the CL-supplied BAL.**

**WI-10 — BAL collection, validation, persistence (XL).** §3.5 steps 2–8, including 4788/2935 as VM calls on Amsterdam. Acceptance: EEST 7928
(202, incl. 33 invalid) and the whole for_amsterdam corpus; Platåberget replay; performance measured on a 200M-gas block.
Deps: WI-2, WI-3, WI-4…WI-8 (BAL content depends on correct execution). forge sign-off on `ProgramState`/`ProgramResult`/`OpCode.execute`.

**WI-11 — Amsterdam payload builder (S–M).** HefPostAmsterdam header, slot, `targetGasLimit`, BAL from WI-10. Acceptance: getPayloadV6
round-trips through newPayloadV5; hive engine builder tests. Deps: WI-9, WI-10, WI-4.

**WI-12 — getPayloadBodiesByHash/RangeV2 + eth/71 BAL serving (M).** Deps: WI-10.

**WI-13 — Fork id with head timestamp (S–M).** Handshakes and ENR/DNS. Acceptance: go-ethereum `forkid_test` Sepolia rows with head
timestamps; stale-peer rejection; ETC ForkId specs unchanged. Deps: none. (herald review.)

**WI-14 — RPC surfaces (S).** eth_config BUILDER_*; txpool filter by active fork; estimateGas lower bound; EthSimulate Amsterdam header;
doc comment. Acceptance: eth_config equals Platåberget’s live output; rpc-compat/graphql oracles unchanged. Deps: none. Bucket A/B.

**WI-15 — Release gates.** hive devp2p (T037), inertness oracles (T038), Platåberget soak behind a devnet-8 CL, Sepolia shadow until
1791294816.

**Deferrable (reason peering/consensus still works):**
- `getBlobsV3/V4`, EIP-8070 sparse blobpool, custody adoption — CL only calls advertised methods and falls back to CL p2p.
- snap/2, eth/70–72 default-on, negotiated snap version in `RemoteStatus` — geth master speaks eth/69–72 and snap/1–2, so eth/69+snap/1 peers.
- trace/debug replay fidelity — RPC only.
- `getBlobsV1` `-38005` post-Osaka — CLs no longer call it.

Timeline note: WI-10 is the only XL item and cannot be made smaller without cutting validation. Shipping 0.9.0 for Sepolia with
WI-1…WI-9 but without WI-10 means fukuii follows by trusting the CL-supplied BAL: it would accept a block whose BAL is wrong, and it cannot
propose. That is a consensus-policy decision for the user, not an engineering default.

---

## 9. Spec ambiguities and reference disagreements (stated, not resolved silently)
1. BAL netting: devnet-8 EELS vs HEAD/EIP text (§1.1). HEAD and the EIP agree; equal on real blocks.
2. Slot ordering: EIP says “lexicographic by storage key”; EELS/geth sort numerically. Identical over 32-byte keys; **not** over minimal
   RLP bytes. Use numeric.
3. System-call gas: EIP-8282 says 30,000,000; EELS gives 30M execution + 1,566,720 state reservoir; fukuii 31,566,720 execution.
4. EIP-7981’s table lists 2400/1900 (EIP-2930) but EIP-8038 reprices to 2900/2000; EELS uses 2900/2000 plus the surcharge.
5. `targetGasLimit`: spec “MUST use” and a structure mismatch is `-38003`; geth treats it as optional and does not reject a missing one.
6. `custodyColumns`: spec makes adoption mandatory “when acting as a sampler”; fukuii is not one — validate length, ignore.
7. EIP-7928 Engine text says newPayloadV5 “validates the computed access list matches”; execution-apis adds the undecodable → INVALID rule.
