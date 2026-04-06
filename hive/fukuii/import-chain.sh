#!/bin/bash
# Import chain.rlp blocks into Fukuii via test_importRawBlock RPC
# Uses only bash builtins + curl + od (no python or xxd needed)
set -e

CHAIN_FILE="$1"
RPC_URL="${2:-http://localhost:8545}"

if [ ! -f "$CHAIN_FILE" ]; then
    echo "No chain file found at $CHAIN_FILE"
    exit 0
fi

FILE_SIZE=$(stat -c%s "$CHAIN_FILE" 2>/dev/null || stat -f%z "$CHAIN_FILE" 2>/dev/null || echo 0)
echo "Chain file: $CHAIN_FILE ($FILE_SIZE bytes)"

# Convert entire file to hex using od
HEX=$(od -An -tx1 "$CHAIN_FILE" | tr -d ' \n')
TOTAL_LEN=${#HEX}

POS=0
BLOCK_NUM=0
IMPORTED=0

while [ $POS -lt $TOTAL_LEN ]; do
    # Read first byte
    FB_HEX="${HEX:$POS:2}"
    FB=$((16#$FB_HEX))

    if [ $FB -le 247 ]; then
        # Short list: 0xc0-0xf7
        LIST_LEN=$(( (FB - 192) * 2 ))
        ITEM_LEN=$(( 2 + LIST_LEN ))
    elif [ $FB -le 255 ]; then
        # Long list: 0xf8-0xff
        LEN_BYTES=$(( FB - 247 ))
        LEN_HEX="${HEX:$((POS+2)):$((LEN_BYTES*2))}"
        DATA_LEN=$((16#$LEN_HEX))
        ITEM_LEN=$(( 2 + LEN_BYTES*2 + DATA_LEN*2 ))
    else
        echo "Unexpected byte at position $POS: $FB_HEX"
        break
    fi

    if [ $ITEM_LEN -le 0 ] || [ $((POS + ITEM_LEN)) -gt $TOTAL_LEN ]; then
        break
    fi

    BLOCK_HEX="0x${HEX:$POS:$ITEM_LEN}"
    POS=$((POS + ITEM_LEN))
    BLOCK_NUM=$((BLOCK_NUM + 1))

    RESULT=$(curl -sf "$RPC_URL" -X POST -H "Content-Type: application/json" \
        -d "{\"jsonrpc\":\"2.0\",\"method\":\"test_importRawBlock\",\"params\":[\"$BLOCK_HEX\"],\"id\":$BLOCK_NUM}" 2>&1 || echo '{"error":"curl failed"}')

    if echo "$RESULT" | grep -q '"result"'; then
        IMPORTED=$((IMPORTED + 1))
    else
        echo "Block $BLOCK_NUM: $RESULT"
    fi
done

echo "Imported $IMPORTED/$BLOCK_NUM blocks"
