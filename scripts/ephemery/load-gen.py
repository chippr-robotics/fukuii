#!/usr/bin/env python3
"""Transaction load generator for the Ephemery testnet (spec 020 two-tree import differential).

Purpose
    Give the import differential real execution load: ETH transfers that create accounts, contract
    deploys (CREATE and CREATE2), storage writes / overwrites / zeroings (refund path), LOG1 events,
    access-list and EIP-1559 transactions, and a few REVERTing calls. NO blob transactions. Python 3
    standard library only.

Node configuration needed (checked against src/main/resources/conf/base/network.conf)
    fukuii.network.rpc.http.apis = "eth,web3,net,personal"     # base default already is
                                   "eth,web3,net,personal,fukuii,debug,qa,admin"
    fukuii.network.rpc.http.enabled = true, interface = "localhost" (default), port 8556 on Ephemery.
    Passphrase length must be >= fukuii.keystore.minimal-passphrase-length (7).

How signing works
    * Legacy (type 0) transactions are signed BY FUKUII: the script calls personal_importRawKey once
      (an "account already exists" error is tolerated), personal_unlockAccount (duration 0 = until
      lock/restart), then eth_sendTransaction (default, uses the unlocked wallet) or, with
      --send-method personal, personal_sendTransaction (re-decrypts the keystore on every call).
    * fukuii's eth_sendTransaction / personal_sendTransaction build ONLY LegacyTransaction (the
      request decoder has no type / accessList / maxFeePerGas fields). So type-1 (access list) and
      type-2 (EIP-1559) transactions are signed LOCALLY by a small pure-Python secp256k1 + keccak
      signer in this file and submitted with eth_sendRawTransaction. Same key, same nonce counter.
    * The private key is read from a file (--key-file) and never printed or logged.

Contract (hand-assembled, see STORE_RUNTIME_ASM / FACTORY_RUNTIME_ASM; `--print-bytecode` shows it)
    Store runtime dispatches on calldatasize:
      64 bytes  word0=slot, word1=value : counter slot (2^256-1) += 1 ; SSTORE(slot, value) ;
                                          LOG1(topic=slot, data=counter)
      36 bytes  selector 0x3a5f1c01 ++ slot : SSTORE(slot, 0)  (refund path); other selector reverts
       4 bytes  anything                    : REVERT (failing tx)
      other                                 : STOP (plain ETH receive)
    Callers must not use slot 2^256-1. Factory runtime: CREATE2(0, child_initcode, salt=word0),
    returns the new address; the child initcode is appended to the factory's code.

--dry-run never touches the network: it prints the tx plan using --chain-id (default 39438000),
--dry-base-fee, nonce 0 and the sender derived from --key-file (or a placeholder).
"""
import argparse
import hashlib
import hmac
import json
import math
import os
import random
import signal
import sys
import time
import urllib.error
import urllib.request

# ---------------------------------------------------------------------------------------------
# keccak-256 (Ethereum padding 0x01, not SHA3's 0x06). Constants are generated, not typed in.
# ---------------------------------------------------------------------------------------------
_M64 = (1 << 64) - 1


def _keccak_consts():
    rcs, r = [], 1
    for _ in range(24):
        rc = 0
        for j in range(7):
            r = ((r << 1) ^ ((r >> 7) * 0x71)) % 256
            if r & 2:
                rc ^= 1 << ((1 << j) - 1)
        rcs.append(rc)
    rot = [[0] * 5 for _ in range(5)]
    x, y = 1, 0
    for t in range(24):
        rot[x][y] = ((t + 1) * (t + 2) // 2) % 64
        x, y = y, (2 * x + 3 * y) % 5
    return rcs, rot


_RC, _ROT = _keccak_consts()


def _rol(v, n):
    n %= 64
    return ((v << n) | (v >> (64 - n))) & _M64 if n else v


def _keccak_f(a):
    for rnd in range(24):
        c = [a[x] ^ a[x + 5] ^ a[x + 10] ^ a[x + 15] ^ a[x + 20] for x in range(5)]
        d = [c[(x - 1) % 5] ^ _rol(c[(x + 1) % 5], 1) for x in range(5)]
        a = [a[i] ^ d[i % 5] for i in range(25)]
        b = [0] * 25
        for x in range(5):
            for y in range(5):
                b[y + 5 * ((2 * x + 3 * y) % 5)] = _rol(a[x + 5 * y], _ROT[x][y])
        a = [b[x + 5 * y] ^ ((~b[(x + 1) % 5 + 5 * y]) & b[(x + 2) % 5 + 5 * y]) for y in range(5) for x in range(5)]
        a[0] ^= _RC[rnd]
    return a


def keccak256(data):
    rate = 136
    msg = bytearray(data)
    msg.append(0x01)
    while len(msg) % rate:
        msg.append(0)
    msg[-1] |= 0x80
    st = [0] * 25
    for off in range(0, len(msg), rate):
        for i in range(rate // 8):
            st[i] ^= int.from_bytes(msg[off + 8 * i: off + 8 * i + 8], "little")
        st = _keccak_f(st)
    return b"".join(st[i].to_bytes(8, "little") for i in range(4))


# ---------------------------------------------------------------------------------------------
# secp256k1 (affine, RFC 6979 deterministic nonce, low-s)
# ---------------------------------------------------------------------------------------------
_P = 2 ** 256 - 2 ** 32 - 977
_N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
_G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
      0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)


def _pt_add(p, q):
    if p is None:
        return q
    if q is None:
        return p
    if p[0] == q[0]:
        if (p[1] + q[1]) % _P == 0:
            return None
        lam = 3 * p[0] * p[0] * pow(2 * p[1], -1, _P) % _P
    else:
        lam = (q[1] - p[1]) * pow(q[0] - p[0], -1, _P) % _P
    x = (lam * lam - p[0] - q[0]) % _P
    return (x, (lam * (p[0] - x) - p[1]) % _P)


def _pt_mul(k, p):
    r = None
    while k:
        if k & 1:
            r = _pt_add(r, p)
        p = _pt_add(p, p)
        k >>= 1
    return r


def address_of_key(priv):
    x, y = _pt_mul(priv, _G)
    return keccak256(x.to_bytes(32, "big") + y.to_bytes(32, "big"))[12:]


def _rfc6979(priv, h1):
    xb = priv.to_bytes(32, "big")
    hb = (int.from_bytes(h1, "big") % _N).to_bytes(32, "big")
    v, k = b"\x01" * 32, b"\x00" * 32
    k = hmac.new(k, v + b"\x00" + xb + hb, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    k = hmac.new(k, v + b"\x01" + xb + hb, hashlib.sha256).digest()
    v = hmac.new(k, v, hashlib.sha256).digest()
    while True:
        v = hmac.new(k, v, hashlib.sha256).digest()
        cand = int.from_bytes(v, "big")
        if 1 <= cand < _N:
            return cand
        k = hmac.new(k, v + b"\x00", hashlib.sha256).digest()
        v = hmac.new(k, v, hashlib.sha256).digest()


def ecdsa_sign(priv, h):
    """Return (r, s, recid) for 32-byte hash h, low-s normalised."""
    k = _rfc6979(priv, h)
    rx, ry = _pt_mul(k, _G)
    r = rx % _N
    s = pow(k, -1, _N) * (int.from_bytes(h, "big") + r * priv) % _N
    recid = (ry & 1) | (2 if rx >= _N else 0)
    if s > _N // 2:
        s = _N - s
        recid ^= 1
    return r, s, recid


def ecdsa_recover(h, r, s, recid):
    x = r + (_N if recid & 2 else 0)
    y2 = (pow(x, 3, _P) + 7) % _P
    y = pow(y2, (_P + 1) // 4, _P)
    if y & 1 != recid & 1:
        y = _P - y
    rinv = pow(r, -1, _N)
    e = int.from_bytes(h, "big")
    q = _pt_add(_pt_mul(s * rinv % _N, (x, y)), _pt_mul((-e * rinv) % _N, _G))
    return keccak256(q[0].to_bytes(32, "big") + q[1].to_bytes(32, "big"))[12:]


# ---------------------------------------------------------------------------------------------
# RLP (encode only) and transaction encoding
# ---------------------------------------------------------------------------------------------
def _be(n):
    return n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""


def rlp(x):
    if isinstance(x, int):
        x = _be(x)
    if isinstance(x, (bytes, bytearray)):
        x = bytes(x)
        if len(x) == 1 and x[0] < 0x80:
            return x
        return _rlp_len(len(x), 0x80) + x
    body = b"".join(rlp(i) for i in x)
    return _rlp_len(len(body), 0xC0) + body


def _rlp_len(n, base):
    if n < 56:
        return bytes([base + n])
    b = _be(n)
    return bytes([base + 55 + len(b)]) + b


def _al(access_list):
    return [[a, [s.to_bytes(32, "big") for s in slots]] for a, slots in access_list]


def sign_typed(priv, chain_id, tx):
    """Sign an EIP-2930 (type 1) or EIP-1559 (type 2) tx dict; return (raw bytes, tx hash, sighash)."""
    to = tx["to"] or b""
    if tx["type"] == 1:
        fields = [chain_id, tx["nonce"], tx["gas_price"], tx["gas"], to, tx["value"], tx["data"], _al(tx["access_list"])]
    elif tx["type"] == 2:
        fields = [chain_id, tx["nonce"], tx["max_priority"], tx["max_fee"], tx["gas"], to, tx["value"], tx["data"],
                  _al(tx["access_list"])]
    else:
        raise ValueError("only type 1 and 2 are signed locally")
    prefix = bytes([tx["type"]])
    sighash = keccak256(prefix + rlp(fields))
    r, s, recid = ecdsa_sign(priv, sighash)
    raw = prefix + rlp(fields + [recid & 1, r, s])
    return raw, keccak256(raw), sighash


def legacy_signing_hash_and_raw(priv, chain_id, nonce, gas_price, gas, to, value, data):
    """Legacy EIP-155 signer; used only by the selftest to prove the signer against the EIP-155 vector."""
    base = [nonce, gas_price, gas, to, value, data]
    sighash = keccak256(rlp(base + [chain_id, 0, 0]))
    r, s, recid = ecdsa_sign(priv, sighash)
    return sighash, rlp(base + [chain_id * 2 + 35 + (recid & 1), r, s])


def create_address(sender, nonce):
    return keccak256(rlp([sender, nonce]))[12:]


def create2_address(factory, salt32, init_code):
    return keccak256(b"\xff" + factory + salt32 + keccak256(init_code))[12:]


# ---------------------------------------------------------------------------------------------
# EVM bytecode: tiny assembler (labels), independent disassembler table, tiny interpreter (selftest)
# ---------------------------------------------------------------------------------------------
_OPS = {  # assembler: mnemonic -> byte (only what the contracts use)
    "STOP": 0x00, "ADD": 0x01, "EQ": 0x14, "ISZERO": 0x15, "NOT": 0x19, "SHR": 0x1C, "CALLDATALOAD": 0x35,
    "CALLDATASIZE": 0x36, "CODECOPY": 0x39, "MSTORE": 0x52, "SLOAD": 0x54, "SSTORE": 0x55, "JUMPI": 0x57,
    "JUMPDEST": 0x5B, "DUP1": 0x80, "SWAP1": 0x90, "SWAP2": 0x91, "LOG1": 0xA1, "RETURN": 0xF3,
    "CREATE2": 0xF5, "REVERT": 0xFD,
}
# Disassembler table, written separately from _OPS on purpose: the selftest checks the two agree.
_DIS = {
    0x00: "STOP", 0x01: "ADD", 0x14: "EQ", 0x15: "ISZERO", 0x19: "NOT", 0x1C: "SHR", 0x35: "CALLDATALOAD",
    0x36: "CALLDATASIZE", 0x39: "CODECOPY", 0x52: "MSTORE", 0x54: "SLOAD", 0x55: "SSTORE", 0x57: "JUMPI",
    0x5B: "JUMPDEST", 0x80: "DUP1", 0x90: "SWAP1", 0x91: "SWAP2", 0xA1: "LOG1", 0xF3: "RETURN",
    0xF5: "CREATE2", 0xFD: "REVERT",
}


def assemble(src):
    """Lines: `label:` | `MNEMONIC` | `PUSH<n> 0xHEX|int|@label` (label refs are PUSH1). `#` starts a comment."""
    items, labels, pc = [], {}, 0
    for line in src.splitlines():
        line = line.split("#")[0].strip()
        if not line:
            continue
        if line.endswith(":"):
            labels[line[:-1]] = pc
            continue
        parts = line.split()
        m = parts[0]
        if m.startswith("PUSH"):
            n = int(m[4:])
            items.append(("push", n, parts[1]))
            pc += 1 + n
        else:
            items.append(("op", _OPS[m]))
            pc += 1
    out = bytearray()
    for it in items:
        if it[0] == "op":
            out.append(it[1])
        else:
            _, n, arg = it
            v = labels[arg[1:]] if arg.startswith("@") else int(arg, 0)
            out.append(0x5F + n)
            out += v.to_bytes(n, "big")
    return bytes(out)


def disassemble(code):
    lines, pc = [], 0
    while pc < len(code):
        op = code[pc]
        if 0x60 <= op <= 0x7F:
            n = op - 0x5F
            lines.append("%04x  PUSH%d 0x%s" % (pc, n, code[pc + 1: pc + 1 + n].hex()))
            pc += 1 + n
        else:
            lines.append("%04x  %s" % (pc, _DIS[op]))
            pc += 1
    return lines


CLEAR_SELECTOR = 0x3A5F1C01
REVERT_SELECTOR = 0xDEADBEEF
COUNTER_SLOT = (1 << 256) - 1

STORE_RUNTIME_ASM = """
CALLDATASIZE
PUSH1 0x40
EQ
PUSH1 @set
JUMPI              # size 64 -> set path
CALLDATASIZE
PUSH1 0x24
EQ
PUSH1 @clear
JUMPI              # size 36 -> clear path
CALLDATASIZE
PUSH1 0x04
EQ
PUSH1 @fail
JUMPI              # size 4 -> revert
STOP               # anything else: accept ETH
set:
JUMPDEST
PUSH1 0
NOT                # [C]   C = 2^256-1 counter slot
DUP1               # [C,C]
SLOAD              # [C,n]
PUSH1 1
ADD                # [C,n+1]
DUP1               # [C,n1,n1]
SWAP2              # [n1,n1,C]
SSTORE             # key=C val=n1 -> [n1]
PUSH1 0x20
CALLDATALOAD       # [n1,value]
PUSH1 0
CALLDATALOAD       # [n1,value,slot]
SSTORE             # key=slot val=value -> [n1]
PUSH1 0
MSTORE             # mem[0:32]=n1 -> []
PUSH1 0
CALLDATALOAD       # [slot]  = LOG1 topic
PUSH1 0x20         # [slot,32]
PUSH1 0            # [slot,32,0]  LOG1(offset=0,size=32,topic=slot)
LOG1
STOP
clear:
JUMPDEST
PUSH1 0
CALLDATALOAD
PUSH1 0xe0
SHR                # first 4 bytes of calldata
PUSH4 0x3a5f1c01
EQ
ISZERO
PUSH1 @fail
JUMPI              # wrong selector -> revert
PUSH1 0            # value 0
PUSH1 4
CALLDATALOAD       # slot
SSTORE             # SSTORE(slot, 0)
STOP
fail:
JUMPDEST
PUSH1 0
DUP1
REVERT
"""


def initcode_for(runtime):
    """Return init code that copies `runtime` (appended after it) to memory and RETURNs it."""
    pre = assemble("PUSH1 %d\nDUP1\nPUSH1 0x0b\nPUSH1 0\nCODECOPY\nPUSH1 0\nRETURN" % len(runtime))
    assert len(pre) == 0x0B
    return pre + runtime


FACTORY_RUNTIME_ASM = """
PUSH1 {L}          # child init-code length
DUP1               # [L,L]
PUSH1 {OFF}        # [L,L,off]   off = start of the child init code inside this contract's code
PUSH1 0            # [L,L,off,0]
CODECOPY           # mem[0:L] = child init code -> [L]
PUSH1 0
CALLDATALOAD       # [L,salt]
SWAP1              # [salt,L]
PUSH1 0            # [salt,L,0]
PUSH1 0            # [salt,L,0,0]
CREATE2            # value=0 offset=0 size=L salt -> [addr]
PUSH1 0
MSTORE             # mem[0:32]=addr
PUSH1 0x20
PUSH1 0
RETURN
"""


def build_contracts():
    store_rt = assemble(STORE_RUNTIME_ASM)
    store_init = initcode_for(store_rt)
    # factory runtime = code + appended child init code; the code length is fixed (23 bytes + data)
    head_len = len(assemble(FACTORY_RUNTIME_ASM.format(L=len(store_init), OFF=0)))
    fac_rt = assemble(FACTORY_RUNTIME_ASM.format(L=len(store_init), OFF=head_len)) + store_init
    fac_init = initcode_for(fac_rt)
    return {"store_runtime": store_rt, "store_init": store_init, "factory_runtime": fac_rt, "factory_init": fac_init}


class _Halt(Exception):
    pass


def evm_run(code, calldata=b"", storage=None, selfcode=None):
    """Minimal interpreter for the opcodes above. Returns dict(status, ret, storage, logs, creates)."""
    storage = {} if storage is None else storage
    stack, mem, logs, creates, pc = [], bytearray(), [], [], 0
    M = 1 << 256
    valid_dests = set()
    i = 0
    while i < len(code):
        if code[i] == 0x5B:
            valid_dests.add(i)
        i += 1 + (code[i] - 0x5F if 0x60 <= code[i] <= 0x7F else 0)

    def memw(off, data):
        if len(mem) < off + len(data):
            mem.extend(b"\x00" * (off + len(data) - len(mem)))
        mem[off: off + len(data)] = data

    def memr(off, n):
        if len(mem) < off + n:
            mem.extend(b"\x00" * (off + n - len(mem)))
        return bytes(mem[off: off + n])

    status, ret = "stop", b""
    while pc < len(code):
        op = code[pc]
        pc += 1
        if 0x60 <= op <= 0x7F:
            n = op - 0x5F
            stack.append(int.from_bytes(code[pc: pc + n], "big"))
            pc += n
        elif op == 0x00:
            break
        elif op == 0x01:
            a, b = stack.pop(), stack.pop()
            stack.append((a + b) % M)
        elif op == 0x14:
            stack.append(int(stack.pop() == stack.pop()))
        elif op == 0x15:
            stack.append(int(stack.pop() == 0))
        elif op == 0x19:
            stack.append(M - 1 - stack.pop())
        elif op == 0x1C:
            sh, v = stack.pop(), stack.pop()
            stack.append(v >> sh if sh < 256 else 0)
        elif op == 0x35:
            off = stack.pop()
            stack.append(int.from_bytes((calldata[off: off + 32]).ljust(32, b"\x00"), "big"))
        elif op == 0x36:
            stack.append(len(calldata))
        elif op == 0x39:
            dst, off, n = stack.pop(), stack.pop(), stack.pop()
            src = selfcode if selfcode is not None else code
            memw(dst, src[off: off + n].ljust(n, b"\x00"))
        elif op == 0x52:
            off, v = stack.pop(), stack.pop()
            memw(off, v.to_bytes(32, "big"))
        elif op == 0x54:
            stack.append(storage.get(stack.pop(), 0))
        elif op == 0x55:
            k, v = stack.pop(), stack.pop()
            if v:
                storage[k] = v
            else:
                storage.pop(k, None)
        elif op == 0x57:
            dest, cond = stack.pop(), stack.pop()
            if cond:
                if dest not in valid_dests:
                    raise _Halt("bad jump")
                pc = dest
        elif op == 0x5B:
            pass
        elif op == 0x80:
            stack.append(stack[-1])
        elif op == 0x90:
            stack[-1], stack[-2] = stack[-2], stack[-1]
        elif op == 0x91:
            stack[-1], stack[-3] = stack[-3], stack[-1]
        elif op == 0xA1:
            off, n, topic = stack.pop(), stack.pop(), stack.pop()
            logs.append((topic, memr(off, n)))
        elif op == 0xF5:
            val, off, n, salt = stack.pop(), stack.pop(), stack.pop(), stack.pop()
            creates.append((val, memr(off, n), salt))
            stack.append(0xC0FFEE)
        elif op == 0xF3:
            off, n = stack.pop(), stack.pop()
            ret, status = memr(off, n), "return"
            break
        elif op == 0xFD:
            off, n = stack.pop(), stack.pop()
            ret, status = memr(off, n), "revert"
            break
        else:
            raise _Halt("unsupported opcode 0x%02x" % op)
    return {"status": status, "ret": ret, "storage": storage, "logs": logs, "creates": creates, "stack": stack}


# ---------------------------------------------------------------------------------------------
# Calldata, fees
# ---------------------------------------------------------------------------------------------
def cd_set(slot, value):
    return slot.to_bytes(32, "big") + value.to_bytes(32, "big")


def cd_clear(slot):
    return CLEAR_SELECTOR.to_bytes(4, "big") + slot.to_bytes(32, "big")


def cd_revert():
    return REVERT_SELECTOR.to_bytes(4, "big")


def cd_create2(salt):
    return salt.to_bytes(32, "big")


GWEI = 10 ** 9


def fees(base_fee, tip):
    """maxFeePerGas = 2 x baseFee + tip. Legacy gasPrice uses the same cap (fukuii signs legacy only)."""
    return {"max_priority": tip, "max_fee": 2 * base_fee + tip, "gas_price": 2 * base_fee + tip}


# ---------------------------------------------------------------------------------------------
# Workload plan
# ---------------------------------------------------------------------------------------------
# Gas limits are explicit (no estimateGas) and generous; blocks are charged gas USED, not the limit.
# EST_GAS is a rough pre-Amsterdam figure only, used for the plan summary and the selftest window.
EST_GAS = {"transfer": 21000, "deploy": 85000, "create2": 110000, "call_new": 62000, "call_over": 35000,
           "call_zero": 18000, "revert": 24000}
INTRINSIC_AL_ADDR, INTRINSIC_AL_KEY = 2400, 1900


class Contract:
    def __init__(self, addr, kind):
        self.addr, self.kind, self.slots = addr, kind, []


class State:
    def __init__(self, sender, nonce, seed):
        self.sender, self.nonce, self.seed = sender, nonce, seed
        self.stores, self.factories, self.known_eoas = [], [], []
        self.eoa_ctr, self.salt_ctr = 0, 0

    def fresh_eoa(self):
        self.eoa_ctr += 1
        return keccak256(b"loadgen-eoa" + self.seed.to_bytes(8, "big") + self.eoa_ctr.to_bytes(8, "big"))[12:]


def _count(rng, rate):
    n = int(math.floor(rate))
    return n + (1 if rng.random() < rate - n else 0)


def _parse_mix(s):
    d = {}
    for part in s.split(","):
        k, v = part.split(":")
        d[k.strip()] = float(v)
    if set(d) != {"legacy", "type1", "type2"} or sum(d.values()) <= 0:
        raise ValueError("--type-mix needs legacy,type1,type2 weights")
    return d


def _pick_type(rng, mix):
    r, acc = rng.random() * sum(mix.values()), 0.0
    for name, w in mix.items():
        acc += w
        if r < acc:
            return {"legacy": 0, "type1": 1, "type2": 2}[name]
    return 0


def plan_tick(rng, st, cfg, contracts):
    """Return a list of tx dicts (nonces assigned from st.nonce, contract pool updated)."""
    mix = _parse_mix(cfg.type_mix)
    plan = []

    def add(kind, to, value, data, gas, est, ttype=None, al=None):
        t = _pick_type(rng, mix) if ttype is None else ttype
        al = list(al or []) if t else []  # legacy txs carry no access list
        if t == 1 and not al:
            al = [(to or st.sender, [])]
        if t == 2 and not al and rng.random() < 0.3:
            al = [(to or st.sender, [rng.getrandbits(32) + 1])]
        gas += sum(INTRINSIC_AL_ADDR + INTRINSIC_AL_KEY * len(k) for _, k in al) if t else 0
        tx = {"kind": kind, "type": t, "nonce": st.nonce, "to": to, "value": value, "data": data, "gas": gas,
              "access_list": al, "est_gas": est}
        st.nonce += 1
        plan.append(tx)
        return tx

    def deploy(kind, init):
        n = st.nonce
        tx = add(kind, None, 0, init, cfg.deploy_gas, EST_GAS["deploy"])
        addr = create_address(st.sender, n)
        c = Contract(addr, "store" if kind == "deploy_store" else "factory")
        (st.stores if c.kind == "store" else st.factories).append(c)
        return tx

    # initial setup: the first tick guarantees callable contracts
    if not st.stores:
        for _ in range(cfg.initial_deploys):
            deploy("deploy_store", contracts["store_init"])
    if not st.factories:
        deploy("deploy_factory", contracts["factory_init"])

    for _ in range(_count(rng, cfg.transfers_new)):
        to = st.fresh_eoa()
        st.known_eoas.append(to)
        if len(st.known_eoas) > 4096:
            st.known_eoas.pop(0)
        add("transfer_new", to, rng.randint(1, 10 ** 6), b"", 21000, EST_GAS["transfer"])
    for _ in range(_count(rng, cfg.transfers_existing)):
        to = rng.choice(st.known_eoas) if st.known_eoas else st.fresh_eoa()
        add("transfer_existing", to, rng.randint(1, 10 ** 6), b"", 21000, EST_GAS["transfer"])
    for _ in range(_count(rng, cfg.deploys)):
        deploy("deploy_store", contracts["store_init"])
    for _ in range(_count(rng, cfg.create2)):
        fac = rng.choice(st.factories)
        st.salt_ctr += 1
        salt = (st.seed << 32) + st.salt_ctr
        child = Contract(create2_address(fac.addr, salt.to_bytes(32, "big"), contracts["store_init"]), "store")
        st.stores.append(child)
        add("create2", fac.addr, 0, cd_create2(salt), cfg.deploy_gas, EST_GAS["create2"])
    for _ in range(_count(rng, cfg.calls)):
        c = rng.choice(st.stores)
        r = rng.random()
        if c.slots and r < cfg.zero_frac:
            slot = c.slots.pop(rng.randrange(len(c.slots)))
            add("call_zero", c.addr, 0, cd_clear(slot), cfg.call_gas, EST_GAS["call_zero"], al=[(c.addr, [slot])])
        elif c.slots and r < cfg.zero_frac + cfg.overwrite_frac:
            slot = rng.choice(c.slots)
            add("call_overwrite", c.addr, 0, cd_set(slot, rng.getrandbits(64) + 1), cfg.call_gas,
                EST_GAS["call_over"], al=[(c.addr, [slot])])
        else:
            slot = rng.getrandbits(48) + 1
            if len(c.slots) < 512:
                c.slots.append(slot)
            add("call_new", c.addr, 0, cd_set(slot, rng.getrandbits(64) + 1), cfg.call_gas, EST_GAS["call_new"],
                al=[(c.addr, [slot])])
    for _ in range(_count(rng, cfg.reverts)):
        c = rng.choice(st.stores)
        add("revert", c.addr, 0, cd_revert(), cfg.call_gas, EST_GAS["revert"])
    return plan


# ---------------------------------------------------------------------------------------------
# JSON-RPC client
# ---------------------------------------------------------------------------------------------
class RpcError(Exception):
    def __init__(self, method, err):
        super().__init__("%s: %s" % (method, err))
        self.method, self.err = method, err

    def text(self):
        return json.dumps(self.err).lower()


class Rpc:
    def __init__(self, url, timeout=30):
        self.url, self.timeout, self.id = url, timeout, 0

    def call(self, method, params=None):
        self.id += 1
        body = json.dumps({"jsonrpc": "2.0", "id": self.id, "method": method, "params": params or []}).encode()
        req = urllib.request.Request(self.url, data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            out = json.loads(resp.read().decode())
        if "error" in out and out["error"]:
            raise RpcError(method, out["error"])
        return out.get("result")


def hexq(n):
    return hex(n)


def hx(b):
    return "0x" + bytes(b).hex()


# ---------------------------------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------------------------------
def load_key(path):
    with open(path) as f:
        s = f.read().strip().lower()
    if s.startswith("0x"):
        s = s[2:]
    k = int(s, 16)  # error text never includes the file content
    if not 1 <= k < _N or len(s) != 64:
        raise SystemExit("key file must hold one 32-byte secp256k1 private key in hex")
    return k, s


def send_tx(rpc, tx, st, priv, chain_id, fee, passphrase, method):
    """Send one planned tx. Returns tx hash hex. Legacy via fukuii signing, typed via local signing."""
    f = fee
    if tx["type"] == 0:
        obj = {"from": hx(st.sender), "value": hexq(tx["value"]), "gas": hexq(tx["gas"]),
               "gasPrice": hexq(f["gas_price"]), "nonce": hexq(tx["nonce"]), "data": hx(tx["data"])}
        if tx["to"]:
            obj["to"] = hx(tx["to"])
        if method == "personal":
            return rpc.call("personal_sendTransaction", [obj, passphrase])
        return rpc.call("eth_sendTransaction", [obj])
    t = dict(tx, gas_price=f["gas_price"], max_priority=f["max_priority"], max_fee=f["max_fee"])
    raw, local_hash, _ = sign_typed(priv, chain_id, t)
    h = rpc.call("eth_sendRawTransaction", [hx(raw)])
    if h and h.lower() != hx(local_hash):
        print("WARN: node hash %s != locally computed %s" % (h, hx(local_hash)), file=sys.stderr)
    return h


def read_fee(rpc, tip):
    blk = rpc.call("eth_getBlockByNumber", ["latest", False])
    if blk.get("baseFeePerGas") is None:
        gp = int(rpc.call("eth_gasPrice"), 16)
        return {"max_priority": 0, "max_fee": gp, "gas_price": gp}, blk
    return fees(int(blk["baseFeePerGas"], 16), tip), blk


def block_report(rpc, n, sent_hashes, sent_since):
    blk = rpc.call("eth_getBlockByNumber", [hexq(n), False])
    hashes = blk["transactions"]
    rec_gas, failed = 0, 0
    try:
        recs = rpc.call("eth_getBlockReceipts", [hexq(n)])
    except RpcError:
        recs = None
    if recs is None:
        recs = [rpc.call("eth_getTransactionReceipt", [h]) for h in hashes]
    for r in recs:
        if r:
            rec_gas += int(r["gasUsed"], 16)
            failed += 1 if int(r.get("status", "0x1"), 16) == 0 else 0
    ours = sum(1 for h in hashes if h.lower() in sent_hashes)
    print("block=%d txs=%d ours=%d sent_since_last=%d failed=%d hdr_gasUsed=%d receipts_gas_sum=%d baseFee=%s" % (
        n, len(hashes), ours, sent_since, failed, int(blk["gasUsed"], 16), rec_gas,
        int(blk["baseFeePerGas"], 16) if blk.get("baseFeePerGas") else "n/a"), flush=True)


def run(args):
    contracts = build_contracts()
    seed = args.seed if args.seed is not None else random.SystemRandom().getrandbits(31)
    rng = random.Random(seed)
    priv = hexkey = None
    if args.key_file:
        priv, hexkey = load_key(args.key_file)
        sender = address_of_key(priv)
    elif args.dry_run:
        sender = bytes.fromhex("00" * 19 + "01")
    else:
        raise SystemExit("--key-file is required unless --dry-run")
    tip = int(args.tip_gwei * GWEI)

    if args.dry_run:
        chain_id = args.chain_id or 39438000
        st = State(sender, 0, seed)
        fee = fees(args.dry_base_fee, tip)
        total = 0
        for i in range(args.blocks or 3):
            plan = plan_tick(rng, st, args, contracts)
            est = sum(t["est_gas"] for t in plan)
            total += est
            print("tick=%d txs=%d est_gas~%d chain_id=%d maxFee=%d tip=%d" % (i, len(plan), est, chain_id, fee["max_fee"], tip))
            for t in plan:
                print("  nonce=%d type=%d %-17s to=%s gas=%d value=%d data_len=%d al=%d" % (
                    t["nonce"], t["type"], t["kind"], hx(t["to"]) if t["to"] else "CREATE", t["gas"], t["value"],
                    len(t["data"]), len(t["access_list"])))
        print("dry-run: sender=%s seed=%d total est_gas~%d (not sent)" % (hx(sender), seed, total))
        return 0

    rpc = Rpc(args.rpc)
    chain_id = args.chain_id or int(rpc.call("eth_chainId"), 16)
    try:
        got = rpc.call("personal_importRawKey", [hexkey, args.passphrase])
        if got and got.lower() != hx(sender):
            raise SystemExit("node derived address %s but local derivation gives %s" % (got, hx(sender)))
    except RpcError as e:
        if "already exists" not in e.text():
            raise
    if not rpc.call("personal_unlockAccount", [hx(sender), args.passphrase, 0]):
        raise SystemExit("personal_unlockAccount returned false")
    bal = int(rpc.call("eth_getBalance", [hx(sender), "latest"]), 16)
    print("chain_id=%d sender=%s balance=%.6f ETH seed=%d send_method=%s" % (chain_id, hx(sender), bal / 1e18, seed, args.send_method))
    if bal < int(args.min_balance_eth * 1e18):
        raise SystemExit("balance below --min-balance-eth")

    st = State(sender, int(rpc.call("eth_getTransactionCount", [hx(sender), "pending"]), 16), seed)
    sent_hashes, sent_since, backoff, consec_err = set(), 0, 0, 0
    out = open(args.out, "a")
    stop = {"flag": False}
    signal.signal(signal.SIGINT, lambda *_: stop.update(flag=True))
    t0 = time.time()
    start_head = last_head = int(rpc.call("eth_blockNumber"), 16)
    total_sent, first = 0, True
    print("start_head=%d out=%s" % (start_head, args.out), flush=True)

    def send_batch():
        nonlocal sent_since, backoff, consec_err, total_sent
        fee, blk = read_fee(rpc, tip)
        chain_pending = int(rpc.call("eth_getTransactionCount", [hx(sender), "pending"]), 16)
        chain_latest = int(rpc.call("eth_getTransactionCount", [hx(sender), "latest"]), 16)
        if chain_pending > st.nonce:
            st.nonce = chain_pending
        if st.nonce - chain_latest > args.max_inflight:
            print("skip: %d txs in flight (> --max-inflight)" % (st.nonce - chain_latest), flush=True)
            return
        plan = plan_tick(rng, st, args, contracts)
        base_nonce = plan[0]["nonce"] if plan else st.nonce
        for tx in plan:
            try:
                h = send_tx(rpc, tx, st, priv, chain_id, fee, args.passphrase, args.send_method)
            except (RpcError, urllib.error.URLError, OSError) as e:
                consec_err += 1
                backoff = min(2 ** consec_err, 30)
                print("send error (%d consecutive, backing off %ds): %s" % (consec_err, backoff, str(e)[:200]), file=sys.stderr, flush=True)
                if consec_err >= args.max_errors:
                    raise SystemExit("too many consecutive errors")
                # drop the rest of this plan; resync the nonce from the node's pending view
                st.nonce = int(rpc.call("eth_getTransactionCount", [hx(sender), "pending"]), 16)
                time.sleep(backoff)
                return
            consec_err = 0
            sent_hashes.add(h.lower())
            sent_since += 1
            total_sent += 1
            out.write(json.dumps({"ts": time.time(), "head": last_head, "hash": h, "kind": tx["kind"], "type": tx["type"],
                                  "nonce": tx["nonce"], "to": hx(tx["to"]) if tx["to"] else None, "gas": tx["gas"]}) + "\n")
            out.flush()

    send_batch()
    while not stop["flag"]:
        time.sleep(args.poll)
        if args.duration and time.time() - t0 >= args.duration:
            break
        try:
            head = int(rpc.call("eth_blockNumber"), 16)
            if head > last_head:
                for n in range(max(last_head + 1, head - 3), head + 1):
                    block_report(rpc, n, sent_hashes, sent_since)
                sent_since, last_head = 0, head
                if args.blocks and head - start_head >= args.blocks:
                    break
                send_batch()
        except (RpcError, urllib.error.URLError, OSError) as e:
            consec_err += 1
            backoff = min(2 ** consec_err, 30)
            print("rpc error (%d consecutive): %s" % (consec_err, str(e)[:200]), file=sys.stderr, flush=True)
            if consec_err >= args.max_errors:
                raise SystemExit("too many consecutive errors")
            time.sleep(backoff)
    out.close()
    print("done: sent=%d blocks_advanced=%d elapsed=%.0fs" % (total_sent, last_head - start_head, time.time() - t0))
    return 0


# ---------------------------------------------------------------------------------------------
# Selftest (offline)
# ---------------------------------------------------------------------------------------------
EXPECTED_STORE_RUNTIME = "366040146016573660241460355736600414604d57005b60001980546001018091556020356000355560005260003560206000a1005b60003560e01c633a5f1c011415604d57600060043555005b600080fd"
EXPECTED_STORE_RUNTIME_DISASM = """0000  CALLDATASIZE
0001  PUSH1 0x40
0003  EQ
0004  PUSH1 0x16
0006  JUMPI
0007  CALLDATASIZE
0008  PUSH1 0x24
000a  EQ
000b  PUSH1 0x35
000d  JUMPI
000e  CALLDATASIZE
000f  PUSH1 0x04
0011  EQ
0012  PUSH1 0x4d
0014  JUMPI
0015  STOP
0016  JUMPDEST
0017  PUSH1 0x00
0019  NOT
001a  DUP1
001b  SLOAD
001c  PUSH1 0x01
001e  ADD
001f  DUP1
0020  SWAP2
0021  SSTORE
0022  PUSH1 0x20
0024  CALLDATALOAD
0025  PUSH1 0x00
0027  CALLDATALOAD
0028  SSTORE
0029  PUSH1 0x00
002b  MSTORE
002c  PUSH1 0x00
002e  CALLDATALOAD
002f  PUSH1 0x20
0031  PUSH1 0x00
0033  LOG1
0034  STOP
0035  JUMPDEST
0036  PUSH1 0x00
0038  CALLDATALOAD
0039  PUSH1 0xe0
003b  SHR
003c  PUSH4 0x3a5f1c01
0041  EQ
0042  ISZERO
0043  PUSH1 0x4d
0045  JUMPI
0046  PUSH1 0x00
0048  PUSH1 0x04
004a  CALLDATALOAD
004b  SSTORE
004c  STOP
004d  JUMPDEST
004e  PUSH1 0x00
0050  DUP1
0051  REVERT"""
EXPECTED_STORE_INIT = "605280600b6000396000f3366040146016573660241460355736600414604d57005b60001980546001018091556020356000355560005260003560206000a1005b60003560e01c633a5f1c011415604d57600060043555005b600080fd"
EXPECTED_FACTORY_RUNTIME = "605d8060196000396000359060006000f560005260206000f3605280600b6000396000f3366040146016573660241460355736600414604d57005b60001980546001018091556020356000355560005260003560206000a1005b60003560e01c633a5f1c011415604d57600060043555005b600080fd"


def selftest():
    import py_compile
    import types
    fails = []

    def check(name, cond, detail=""):
        print("%s %s %s" % ("PASS" if cond else "FAIL", name, "" if cond else detail))
        if not cond:
            fails.append(name)

    # compile
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        py_compile.compile(os.path.abspath(__file__), cfile=os.path.join(td, "x.pyc"), doraise=True)
    check("py_compile", True)
    # keccak / rlp
    check("keccak256('')", keccak256(b"").hex() == "c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470")
    check("keccak256('abc')", keccak256(b"abc").hex() == "4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45")
    check("keccak 200-byte (multi-block)", len(keccak256(b"a" * 300)) == 32)
    check("rlp vectors", rlp(b"dog").hex() == "83646f67" and rlp([b"cat", b"dog"]).hex() == "c88363617483646f67"
          and rlp(0).hex() == "80" and rlp(1024).hex() == "820400" and rlp([]).hex() == "c0"
          and rlp(b"a" * 56).hex().startswith("b838"))
    # secp256k1
    check("G on curve", (_G[1] ** 2 - _G[0] ** 3 - 7) % _P == 0)
    check("address of key 1", address_of_key(1).hex() == "7e5f4552091a69125d5dfcb7b8c2659029395bdf")
    priv = int("46" * 32, 16)
    sh, raw = legacy_signing_hash_and_raw(priv, 1, 9, 20 * GWEI, 21000, bytes.fromhex("35" * 20), 10 ** 18, b"")
    check("EIP-155 signing hash", sh.hex() == "daf5a779ae972f972197303d7b574746c7ef83eadac0f2791ad23db92e4c8e53", sh.hex())
    check("EIP-155 signed tx", raw.hex() == "f86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276a067cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83", raw.hex())
    # typed tx: sign then recover the sender from the signature
    for ttype in (1, 2):
        tx = {"type": ttype, "nonce": 7, "to": bytes(range(20)), "value": 5, "data": b"\x01\x02", "gas": 50000,
              "gas_price": 3 * GWEI, "max_priority": 10 ** 7, "max_fee": 2 * GWEI, "access_list": [(bytes(range(20)), [1, 2])]}
        rawt, th, sighash = sign_typed(priv, 1, tx)
        # decode r,s,yParity from the tail of the RLP: reconstruct by re-signing deterministically
        r, s, recid = ecdsa_sign(priv, sighash)
        check("type %d sign/recover sender" % ttype, ecdsa_recover(sighash, r, s, recid) == address_of_key(priv)
              and rawt[0] == ttype and th == keccak256(rawt))
    # bytecode
    c = build_contracts()
    rt = c["store_runtime"]
    dis = disassemble(rt)
    check("disassembler table agrees with assembler table", all(_DIS[v] == k for k, v in _OPS.items()) and len(_DIS) == len(_OPS))
    check("store runtime hex", EXPECTED_STORE_RUNTIME is not None and rt.hex() == EXPECTED_STORE_RUNTIME, rt.hex())
    check("store runtime disassembly", EXPECTED_STORE_RUNTIME_DISASM is not None and "\n".join(dis) == EXPECTED_STORE_RUNTIME_DISASM)
    check("store init hex", EXPECTED_STORE_INIT is not None and c["store_init"].hex() == EXPECTED_STORE_INIT, c["store_init"].hex())
    check("factory runtime hex", EXPECTED_FACTORY_RUNTIME is not None and c["factory_runtime"].hex() == EXPECTED_FACTORY_RUNTIME, c["factory_runtime"].hex())
    # every PUSH1 @label target is a JUMPDEST
    check("jump targets are JUMPDEST", all(rt[int(l.split()[2], 16)] == 0x5B for l, nxt in zip(dis, dis[1:])
                                          if nxt.endswith("JUMPI") and "PUSH1" in l))
    # execute the init code, then the runtime
    r = evm_run(c["store_init"], selfcode=c["store_init"])
    check("store init returns runtime", r["status"] == "return" and r["ret"] == rt)
    st_ = {}
    r1 = evm_run(rt, cd_set(5, 111), st_)
    check("set: slot, counter, log", r1["storage"] == {5: 111, COUNTER_SLOT: 1} and r1["logs"] == [(5, (1).to_bytes(32, "big"))])
    r2 = evm_run(rt, cd_set(5, 222), st_)
    check("overwrite + counter increments", st_ == {5: 222, COUNTER_SLOT: 2} and r2["logs"][0][1] == (2).to_bytes(32, "big"))
    evm_run(rt, cd_clear(5), st_)
    check("clear selector zeroes slot", st_ == {COUNTER_SLOT: 2})
    check("clear with wrong selector reverts", evm_run(rt, bytes.fromhex("11223344") + (5).to_bytes(32, "big"), {})["status"] == "revert")
    check("4-byte call reverts", evm_run(rt, cd_revert(), {})["status"] == "revert")
    check("empty calldata is a no-op", evm_run(rt, b"", {})["status"] == "stop")
    fr = evm_run(c["factory_init"], selfcode=c["factory_init"])
    check("factory init returns factory runtime", fr["ret"] == c["factory_runtime"])
    f2 = evm_run(c["factory_runtime"], cd_create2(77), selfcode=c["factory_runtime"])
    check("factory CREATE2 uses store init code and salt", f2["creates"] == [(0, c["store_init"], 77)] and f2["status"] == "return")
    # calldata, fees, addresses
    check("calldata encoding", cd_set(1, 2).hex() == "00" * 31 + "01" + "00" * 31 + "02" and cd_clear(3).hex() == "3a5f1c01" + "00" * 31 + "03"
          and cd_revert().hex() == "deadbeef" and len(cd_create2(9)) == 32)
    fe = fees(7, 10 ** 7)
    check("fee math", fe["max_fee"] == 2 * 7 + 10 ** 7 and fe["max_priority"] == 10 ** 7 and int(0.01 * GWEI) == 10 ** 7)
    check("CREATE address known vector", create_address(bytes.fromhex("6ac7ea33f8831ea9dcc53393aaa88b25a785dbf0"), 0).hex() == "cd234a471b72ba2f1ccf0a70fcaba648a5eecd8d")
    # plan sanity
    cfg = argparser().parse_args([])
    rng = random.Random(1)
    sd = State(address_of_key(priv), 100, 1)
    total, nonces, kinds = 0, [], set()
    for _ in range(50):
        p = plan_tick(rng, sd, cfg, c)
        total += sum(t["est_gas"] for t in p)
        nonces += [t["nonce"] for t in p]
        kinds |= {t["kind"] for t in p}
    avg = total / 50
    check("plan nonces consecutive from 100", nonces == list(range(100, 100 + len(nonces))))
    check("plan mean est gas/tick in 0.5-2 Mgas (%.0f)" % avg, 500_000 <= avg <= 2_000_000)
    check("plan covers all kinds", {"transfer_new", "transfer_existing", "deploy_store", "deploy_factory", "create2", "call_new",
                                    "call_overwrite", "call_zero", "revert"} <= kinds, str(kinds))
    check("no blob txs (types only 0,1,2)", True)
    rng2, sd2 = random.Random(1), State(address_of_key(priv), 100, 1)
    p2 = [plan_tick(rng2, sd2, cfg, c) for _ in range(50)]
    rng3, sd3 = random.Random(1), State(address_of_key(priv), 100, 1)
    p3 = [plan_tick(rng3, sd3, cfg, c) for _ in range(50)]
    check("plan deterministic for a seed", p2 == p3)
    check("types seen are 0/1/2", {t["type"] for p in p2 for t in p} <= {0, 1, 2})
    print("selftest: %s" % ("FAILED (%s)" % ", ".join(fails) if fails else "all checks passed"))
    return 1 if fails else 0


def argparser():
    ap = argparse.ArgumentParser(description="Ephemery transaction load generator (spec 020). Does not run unless invoked.")
    ap.add_argument("--rpc", default="http://127.0.0.1:8556")
    ap.add_argument("--key-file", help="file holding the funded sender's private key (hex); never printed")
    ap.add_argument("--passphrase", default="loadgen-passphrase", help="keystore passphrase (>= 7 chars)")
    ap.add_argument("--chain-id", type=int, help="default: read eth_chainId (dry-run: 39438000)")
    ap.add_argument("--blocks", type=int, default=0, help="stop after the head advanced N blocks (dry-run: N ticks, default 3)")
    ap.add_argument("--duration", type=float, default=0, help="stop after N seconds")
    ap.add_argument("--dry-run", action="store_true", help="print the tx plan, no network access")
    ap.add_argument("--dry-base-fee", type=int, default=7, help="base fee in wei used by --dry-run")
    ap.add_argument("--selftest", action="store_true", help="offline checks, then exit")
    ap.add_argument("--print-bytecode", action="store_true")
    ap.add_argument("--seed", type=int)
    ap.add_argument("--poll", type=float, default=1.0, help="head poll interval, seconds")
    ap.add_argument("--out", default="load-gen-txs.jsonl", help="JSONL of sent tx hashes (appended)")
    ap.add_argument("--send-method", choices=["eth", "personal"], default="eth")
    ap.add_argument("--tip-gwei", type=float, default=0.01)
    ap.add_argument("--type-mix", default="legacy:0.5,type1:0.2,type2:0.3")
    # per-tick (per new head) rates; fractional part is a probability
    ap.add_argument("--transfers-new", type=float, default=6)
    ap.add_argument("--transfers-existing", type=float, default=4)
    ap.add_argument("--deploys", type=float, default=1.5)
    ap.add_argument("--create2", type=float, default=0.5)
    ap.add_argument("--calls", type=float, default=10)
    ap.add_argument("--reverts", type=float, default=0.5)
    ap.add_argument("--zero-frac", type=float, default=0.2, help="share of calls that zero a slot")
    ap.add_argument("--overwrite-frac", type=float, default=0.3, help="share of calls that overwrite a slot")
    ap.add_argument("--initial-deploys", type=int, default=2)
    ap.add_argument("--deploy-gas", type=int, default=500000)
    ap.add_argument("--call-gas", type=int, default=250000)
    ap.add_argument("--max-inflight", type=int, default=300)
    ap.add_argument("--max-errors", type=int, default=20)
    ap.add_argument("--min-balance-eth", type=float, default=0.05)
    return ap


def main():
    args = argparser().parse_args()
    if args.print_bytecode:
        c = build_contracts()
        for k in ("store_runtime", "store_init", "factory_runtime", "factory_init"):
            print("%s (%d bytes): %s" % (k, len(c[k]), c[k].hex()))
        print("\n".join(disassemble(c["store_runtime"])))
        print("--- factory runtime ---")
        print("\n".join(disassemble(c["factory_runtime"][:-len(c["store_init"])])))
        return 0
    if args.selftest:
        return selftest()
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
