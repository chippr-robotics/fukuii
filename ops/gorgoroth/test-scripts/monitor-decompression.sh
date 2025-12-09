#!/bin/bash
# Monitor for message decompression issues in Fukuii nodes
# Usage: ./monitor-decompression.sh [container_name]

CONTAINER_NAME="${1:-fukuii-node6}"

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Statistics
DECOMPRESS_COUNT=0
SNAPPY_COUNT=0
RLPX_COUNT=0
HANDSHAKE_COUNT=0
ERROR_COUNT=0

# Cleanup and display statistics on exit
cleanup() {
  echo
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "Monitoring Statistics:"
  echo "  Decompression messages: $DECOMPRESS_COUNT"
  echo "  Snappy messages: $SNAPPY_COUNT"
  echo "  RLPx messages: $RLPX_COUNT"
  echo "  Handshake messages: $HANDSHAKE_COUNT"
  echo "  Error messages: $ERROR_COUNT"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  exit 0
}

# Set up trap to call cleanup on Ctrl+C or script termination
trap cleanup SIGINT SIGTERM

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Monitoring $CONTAINER_NAME for decompression issues"
echo "Press Ctrl+C to stop and view statistics"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

# Check if container exists
if ! docker ps -a --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
  echo -e "${RED}Error: Container '$CONTAINER_NAME' not found${NC}"
  echo "Available containers:"
  docker ps --format '{{.Names}}' | grep fukuii
  exit 1
fi

# Start monitoring
docker logs -f "$CONTAINER_NAME" 2>&1 | while IFS= read -r line; do
  TIMESTAMP=$(echo "$line" | grep -oE '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}' || echo "")
  
  # Highlight decompression-related messages
  if echo "$line" | grep -qi "decompress"; then
    DECOMPRESS_COUNT=$((DECOMPRESS_COUNT + 1))
    
    if echo "$line" | grep -qi "error\|fail\|invalid"; then
      echo -e "${RED}[ERROR-DECOMPRESS]${NC} $line"
      ERROR_COUNT=$((ERROR_COUNT + 1))
    else
      echo -e "${CYAN}[DECOMPRESS]${NC} $line"
    fi
    
  elif echo "$line" | grep -qi "snappy"; then
    SNAPPY_COUNT=$((SNAPPY_COUNT + 1))
    
    if echo "$line" | grep -qi "error\|fail"; then
      echo -e "${RED}[ERROR-SNAPPY]${NC} $line"
      ERROR_COUNT=$((ERROR_COUNT + 1))
    else
      echo -e "${BLUE}[SNAPPY]${NC} $line"
    fi
    
  elif echo "$line" | grep -qi "rlpx"; then
    RLPX_COUNT=$((RLPX_COUNT + 1))
    
    if echo "$line" | grep -qi "error\|fail"; then
      echo -e "${RED}[ERROR-RLPx]${NC} $line"
      ERROR_COUNT=$((ERROR_COUNT + 1))
    else
      echo -e "${MAGENTA}[RLPx]${NC} $line"
    fi
    
  elif echo "$line" | grep -qi "handshake"; then
    HANDSHAKE_COUNT=$((HANDSHAKE_COUNT + 1))
    
    if echo "$line" | grep -qi "error\|fail"; then
      echo -e "${RED}[ERROR-HANDSHAKE]${NC} $line"
      ERROR_COUNT=$((ERROR_COUNT + 1))
    elif echo "$line" | grep -qi "complete"; then
      echo -e "${GREEN}[HANDSHAKE-OK]${NC} $line"
    else
      echo -e "${YELLOW}[HANDSHAKE]${NC} $line"
    fi
    
  elif echo "$line" | grep -qi "peer.*disconnect\|disconnect.*peer"; then
    echo -e "${YELLOW}[PEER-DISCONNECT]${NC} $line"
    
  elif echo "$line" | grep -qi "error.*message\|message.*error"; then
    echo -e "${RED}[MESSAGE-ERROR]${NC} $line"
    ERROR_COUNT=$((ERROR_COUNT + 1))
  fi
done
