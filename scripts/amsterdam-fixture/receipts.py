from keccak import keccak256
from rlp import enc

def bloom(logs):
    b = bytearray(256)
    def add(x):
        h = keccak256(x)
        for i in (0,2,4):
            bit = ((h[i]<<8)|h[i+1]) & 0x7ff
            b[256-1-(bit//8)] |= 1<<(bit%8)
    for (addr, topics, data) in logs:
        add(addr)
        for t in topics: add(t)
    return bytes(b)

def receipt(txtype, status, cgu, logs):
    lg = [[a, list(t), d] for (a,t,d) in logs]
    payload = enc([status, cgu, bloom(logs), lg])
    return (bytes([txtype]) + payload) if txtype else payload

def single_root(value):
    node = enc([b'\x20\x80', value])
    return keccak256(node) if len(node)>=32 else node

def contract_addr(sender: bytes, nonce: int):
    return keccak256(enc([sender, nonce]))[12:]
