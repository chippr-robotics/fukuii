#!/usr/bin/env python3
"""Import chain.rlp blocks into Fukuii via test_importRawBlock RPC."""
import sys
import json
import urllib.request

def read_rlp_length(data, pos):
    """Read one RLP item length, return (total_bytes, next_pos)."""
    if pos >= len(data):
        return 0, pos
    b = data[pos]
    if b < 0x80:
        return 1, pos
    elif b <= 0xb7:
        length = b - 0x80
        return 1 + length, pos
    elif b <= 0xbf:
        len_bytes = b - 0xb7
        length = int.from_bytes(data[pos+1:pos+1+len_bytes], 'big')
        return 1 + len_bytes + length, pos
    elif b <= 0xf7:
        length = b - 0xc0
        return 1 + length, pos
    else:
        len_bytes = b - 0xf7
        length = int.from_bytes(data[pos+1:pos+1+len_bytes], 'big')
        return 1 + len_bytes + length, pos

def extract_blocks(data):
    """Extract individual RLP-encoded blocks from concatenated chain.rlp."""
    pos = 0
    blocks = []
    while pos < len(data):
        total_len, _ = read_rlp_length(data, pos)
        if total_len == 0:
            break
        block_data = data[pos:pos+total_len]
        blocks.append(block_data)
        pos += total_len
    return blocks

def import_block(rpc_url, block_hex):
    """Send a block via test_importRawBlock RPC."""
    payload = json.dumps({
        "jsonrpc": "2.0",
        "method": "test_importRawBlock",
        "params": [block_hex],
        "id": 1
    }).encode()
    req = urllib.request.Request(rpc_url, data=payload,
                                headers={"Content-Type": "application/json"})
    try:
        resp = urllib.request.urlopen(req, timeout=30)
        result = json.loads(resp.read())
        return result
    except Exception as e:
        return {"error": str(e)}

def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <chain.rlp> <rpc_url>")
        sys.exit(1)

    chain_file = sys.argv[1]
    rpc_url = sys.argv[2]

    with open(chain_file, 'rb') as f:
        data = f.read()

    print(f"Chain file size: {len(data)} bytes")
    blocks = extract_blocks(data)
    print(f"Found {len(blocks)} blocks to import")

    imported = 0
    for i, block in enumerate(blocks):
        hex_str = "0x" + block.hex()
        result = import_block(rpc_url, hex_str)
        if "error" in result and result["error"]:
            print(f"Block {i}: import error: {result.get('error', {})}")
        elif result.get("result"):
            imported += 1
        else:
            err = result.get("error", "unknown")
            print(f"Block {i}: failed: {err}")

    print(f"Imported {imported}/{len(blocks)} blocks")

if __name__ == "__main__":
    main()
