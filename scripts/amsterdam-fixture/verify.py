#!/usr/bin/env python3
"""Reproduce the Amsterdam figures in specs/009-amsterdam-fork-support/ from the fixture.

Every number in that spec, plan, research and contracts was measured with this harness. This
script re-derives them so a reader can check them instead of trusting them.

Order matters. The machinery (keccak, RLP, single-entry MPT root, receipt and bloom
construction) is validated against KNOWN PRE-AMSTERDAM data FIRST. Only then is it applied to
Amsterdam blocks. A harness that has not been validated on data whose answer is already known
cannot establish anything about data whose answer is not.

Usage:  python3 verify.py [path-to-fixture-dir]
        (default: ./devp2p-td — see README.md for how to obtain it)
"""
import json
import os
import sys

import rlp as R
from rlp import enc
from keccak import keccak256
from receipts import bloom, single_root, contract_addr

FAILURES = []


def check(label, got, want):
    ok = got == want
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}")
    if not ok:
        print(f"         got  {got}")
        print(f"         want {want}")
        FAILURES.append(label)
    return ok


def h(b):
    return "0x" + bytes(b).hex()


def main(d):
    chain = os.path.join(d, "chain.rlp")
    if not os.path.exists(chain):
        print(f"fixture not found at {d} — see README.md", file=sys.stderr)
        return 2

    blocks = R.decode_stream(open(chain, "rb").read())
    hdr = lambda n: blocks[n - 1][0]
    gas_used = lambda n: int.from_bytes(hdr(n)[10], "big")

    # ---------------------------------------------------------------- stage 1
    print("Stage 1 — validate the machinery on data whose answer is already known")

    check("keccak256(b'') matches the published vector",
          h(keccak256(b"")),
          "0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470")

    check("chain.rlp decodes to 600 blocks", len(blocks), 600)

    headblock = os.path.join(d, "headblock.json")
    if os.path.exists(headblock):
        hb = json.load(open(headblock))
        check("block 600 header re-encodes to its canonical hash",
              h(keccak256(enc(hdr(600)))), hb["hash"])

    check("block 35 (last pre-Amsterdam) carries 21 RLP items", len(hdr(35)), 21)
    check("block 36 (first Amsterdam) carries 23 RLP items", len(hdr(36)), 23)

    # ---------------------------------------------------------------- stage 2
    print("\nStage 2 — the header-decoder defect (spec User Story 2, slice A)")

    check("block 36 canonical hash",
          h(keccak256(enc(hdr(36)))),
          "0x6372c88fef519c6e4bebbe02d8447c084fc8cd759fae25c5b5cc3c6e2be99bfe")

    # This is what fukuii computes today: `case n if n >= 21` decodes a 23-item header as
    # HefPostPrague, dropping items 21 and 22, and re-encodes 21 items. Decoding SUCCEEDS and
    # returns the wrong answer — which is why the fix is to reject, not to tolerate.
    check("block 36 hash when items 21-22 are dropped (the live defect)",
          h(keccak256(enc(hdr(36)[:21]))),
          "0x94844dfd59c36d3edcebc229d2d89d659c471fa71057a8368a45760029c2e070")

    # ---------------------------------------------------------------- stage 3
    print("\nStage 3 — V1: the header reports a MAXIMUM, receipts report a SUM")

    # contracts/gas-accounting.md V1. tx-calltree at block 41.
    calltree = bytes.fromhex("9dcd17433742f4c0ca53122ab541d0ba67fc27d0")
    callme = bytes.fromhex("9dcd17433742f4c0ca53122ab541d0ba67fc27d1")
    SYS = bytes.fromhex("fffffffffffffffffffffffffffffffffffffffe")
    TRANSFER = keccak256(b"Transfer(address,address,uint256)")
    SLOT = keccak256(b"\x00" * 4)  # the delegatecall always passes four zero bytes
    T_EMIT = (0x656D6974).to_bytes(32, "big")
    T_TREE = (0x74726565).to_bytes(32, "big")
    T_CHILD = (0x6368696C64).to_bytes(32, "big")

    logs = [
        # EIP-7708: the 1-wei CALL emits a transfer log from the system address.
        # Without this entry the receipts root does not reconstruct — which is the
        # measurement that put EIP-7708 into scope as FR-019.
        (SYS, [TRANSFER, b"\x00" * 12 + calltree, b"\x00" * 12 + callme], (1).to_bytes(32, "big")),
        (calltree, [T_EMIT, SLOT], (2).to_bytes(32, "big")),
        (contract_addr(calltree, 2), [T_CHILD], b""),
        (calltree, [T_TREE], b""),
    ]
    lg = [[a, list(t), data] for (a, t, data) in logs]

    CUMULATIVE = 326947
    STATE_GAS = 183600  # STATE_BYTES_PER_NEW_ACCOUNT(120) x CPSB(1530)

    check("block 41 receipts root reconstructs with cumulativeGasUsed = 326,947",
          h(single_root(b"\x02" + enc([1, CUMULATIVE, bloom(logs), lg]))),
          h(hdr(41)[5]))
    check("block 41 header gasUsed = 183,600", gas_used(41), STATE_GAS)
    check("183,600 = STATE_BYTES_PER_NEW_ACCOUNT(120) x CPSB(1530)", 120 * 1530, STATE_GAS)
    check("header and receipt disagree, and the header is the max()",
          max(CUMULATIVE - STATE_GAS, STATE_GAS), gas_used(41))

    print(f"         execution gas = {CUMULATIVE} - {STATE_GAS} = {CUMULATIVE - STATE_GAS}")
    print(f"         header        = max({CUMULATIVE - STATE_GAS}, {STATE_GAS}) = {gas_used(41)}")

    # ---------------------------------------------------------------- stage 4
    print("\nStage 4 — V3: blocks whose total is a whole multiple of the state charge")

    STORAGE_SET = 64 * 1530  # STATE_BYTES_PER_STORAGE_SET(64) x CPSB(1530) = 97,920
    check("GAS_STORAGE_SET = 97,920", STORAGE_SET, 97920)
    check("block 36 gasUsed = 8 x 97,920", gas_used(36), 8 * STORAGE_SET)
    check("block 37 gasUsed = 5 x 97,920", gas_used(37), 5 * STORAGE_SET)

    # ---------------------------------------------------------------- stage 5
    print("\nStage 5 — R-1: the fixture cannot exercise the state-gas reservoir")

    TX_MAX_GAS_LIMIT = 1 << 24

    # gasLimit sits at a different index per transaction shape. Getting this wrong is not a
    # harmless slip: reading index 3 on a type-2 transaction returns maxFeePerGas, which looks
    # like a plausible gas limit and silently inverts this stage's conclusion.
    #   legacy       (list, 9):  [nonce, gasPrice, gasLimit, ...]                  -> 2
    #   0x01 2930   (list, 11):  [chainId, nonce, gasPrice, gasLimit, ...]         -> 3
    #   0x02 1559   (list, 12):  [chainId, nonce, maxPrio, maxFee, gasLimit, ...]  -> 4
    #   0x03 4844   (list, 14):  same prefix                                       -> 4
    #   0x04 7702   (list, 13):  same prefix                                       -> 4
    GAS_LIMIT_INDEX = {9: 2, 11: 3, 12: 4, 14: 4, 13: 4}

    biggest, where, ntx = 0, None, 0
    for n in range(1, 601):
        for tx in blocks[n - 1][1]:
            ntx += 1
            if isinstance(tx, (bytes, bytearray)):
                ttype, body = tx[0], R.decode(bytes(tx)[1:])
            else:
                ttype, body = 0, tx
            idx = GAS_LIMIT_INDEX.get(len(body))
            if idx is None:
                print(f"  unrecognised tx shape: type {ttype}, {len(body)} fields, block {n}")
                FAILURES.append("unrecognised transaction shape")
                continue
            g = int.from_bytes(body[idx], "big")
            if g > biggest:
                biggest, where = g, (n, ttype)

    print(f"  transactions examined:                      {ntx}")
    print(f"  largest tx gas limit anywhere in the chain: {biggest:,} (block {where[0]}, type {where[1]})")
    print(f"  TX_MAX_GAS_LIMIT (2^24):                    {TX_MAX_GAS_LIMIT:,}")
    check("every transaction sits below TX_MAX_GAS_LIMIT", biggest < TX_MAX_GAS_LIMIT, True)
    if biggest < TX_MAX_GAS_LIMIT:
        print("  => state_gas_reservoir is 0 in EVERY transaction in this chain.")
        print("     Reservoir seeding, cross-frame passing, LIFO refill ordering and the")
        print("     successful-child merge are NOT exercised here. Fixture-green is")
        print("     necessary, not sufficient. See research.md R-1.")

    print()
    if FAILURES:
        print(f"{len(FAILURES)} CHECK(S) FAILED: " + "; ".join(FAILURES))
        return 1
    print("All checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "devp2p-td"))
