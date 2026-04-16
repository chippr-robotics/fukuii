# Network Management Runbook

## Overview

This runbook covers network management operations for Fukuii nodes, including peer management, blacklist operations, and network hygiene best practices.

**Target Audience**: Node operators, DevOps engineers  
**Prerequisites**: Running Fukuii node with JSON-RPC enabled  
**Difficulty**: Intermediate

## Table of Contents

- [Peer Management](#peer-management)
  - [Listing Connected Peers](#listing-connected-peers)
  - [Connecting to New Peers](#connecting-to-new-peers)
  - [Disconnecting Problematic Peers](#disconnecting-problematic-peers)
- [Blacklist Management](#blacklist-management)
  - [Viewing Blacklisted Peers](#viewing-blacklisted-peers)
  - [Adding Peers to Blacklist](#adding-peers-to-blacklist)
  - [Removing Peers from Blacklist](#removing-peers-from-blacklist)
- [Network Hygiene](#network-hygiene)
- [Troubleshooting](#troubleshooting)

## Peer Management

### Listing Connected Peers

To view all currently connected peers with detailed information:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_listPeers",
    "params": []
  }'
```

**Response Fields**:
- `id`: Unique peer identifier
- `remoteAddress`: IP address and port
- `nodeId`: Ethereum node ID (public key), null if handshake incomplete
- `incomingConnection`: `true` for incoming, `false` for outgoing
- `status`: Current connection status
  - `Handshaked`: Fully connected and ready
  - `Connecting`: Connection in progress
  - `Handshaking`: Performing protocol handshake
  - `Idle`: Not actively connected
  - `Disconnected`: Connection closed

**Use Cases**:
- Monitor peer connectivity
- Identify peer geographic distribution
- Diagnose connection issues
- Verify peer diversity

### Connecting to New Peers

To manually add a peer connection:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_connectToPeer",
    "params": ["enode://PUBLIC_KEY@IP:PORT"]
  }'
```

**Enode URI Format**:
```
enode://NODE_ID@IP_ADDRESS:PORT
```

**Example**:
```
enode://a979fb575495b8d6db44f750317d0f4622bf4c2aa3365d6af7c284339968eef29b69ad0dce72a4d8db5ebb4968de0e3bec910127f134779fbcb0cb6d3331163c@52.16.188.185:30303
```

**Use Cases**:
- Bootstrap new nodes
- Connect to known good peers
- Test connectivity to specific nodes
- Build private networks

**Notes**:
- Returns immediately; connection happens asynchronously
- Success means connection attempt initiated, not that connection succeeded
- Check `net_listPeers` after a few seconds to verify connection

### Disconnecting Problematic Peers

To disconnect a specific peer:

1. First, get the peer ID from `net_listPeers`
2. Then disconnect using the ID:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_disconnectPeer",
    "params": ["PEER_ID"]
  }'
```

**Use Cases**:
- Remove misbehaving peers
- Free up connection slots
- Test network resilience
- Implement peer rotation

**Notes**:
- Disconnection is immediate
- Peer may attempt to reconnect (consider blacklisting if needed)
- Returns `false` if peer ID not found

## Blacklist Management

### Viewing Blacklisted Peers

To see all currently blacklisted peers:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_listBlacklistedPeers",
    "params": []
  }'
```

**Response Fields**:
- `id`: Blacklisted address (IP or peer ID)
- `reason`: Description of why blacklisted
- `addedAt`: Timestamp when added (milliseconds since epoch)

**Use Cases**:
- Audit blacklist contents
- Review security measures
- Troubleshoot connection issues
- Identify blacklist expiries

### Adding Peers to Blacklist

#### Temporary Blacklist

To blacklist a peer for a specific duration (in seconds):

```bash
# Blacklist for 1 hour (3600 seconds)
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_addToBlacklist",
    "params": ["192.168.1.100", 3600, "Excessive failed handshakes"]
  }'
```

**Common Durations**:
- 1 hour: 3600 seconds
- 24 hours: 86400 seconds
- 1 week: 604800 seconds
- 30 days: 2592000 seconds

#### Permanent Blacklist

To permanently blacklist a peer:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_addToBlacklist",
    "params": ["192.168.1.100", null, "Known malicious actor"]
  }'
```

**Common Blacklist Reasons**:
- "Malicious behavior"
- "Protocol violations"
- "Excessive resource usage"
- "Known attack source"
- "Incompatible client"
- "Testing/debugging"

**Use Cases**:
- Block malicious peers
- Prevent resource exhaustion
- Enforce network policies
- Implement geographic restrictions

### Removing Peers from Blacklist

To remove a peer from the blacklist:

```bash
curl -X POST http://localhost:8546 \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "net_removeFromBlacklist",
    "params": ["192.168.1.100"]
  }'
```

**Use Cases**:
- Unban mistakenly blacklisted peers
- Update network policies
- Clear expired entries manually
- Test blacklist functionality

**Notes**:
- Removal is immediate
- Peer can connect again after removal
- Does not automatically trigger reconnection

## Network Hygiene

### Best Practices

1. **Regular Monitoring**
   ```bash
   # Check peer count regularly
   watch -n 30 'curl -s http://localhost:8546 -X POST -H "Content-Type: application/json" \
     -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"net_peerCount\",\"params\":[]}" | jq'
   ```

2. **Blacklist Review**
   - Review blacklist weekly
   - Remove entries for resolved issues
   - Keep blacklist size reasonable (< 100 entries typically)

3. **Peer Diversity**
   - Monitor geographic distribution
   - Avoid concentration on single IP ranges
   - Rotate peers periodically

4. **Connection Limits**
   - Set appropriate max peer counts
   - Balance incoming vs outgoing connections
   - Monitor connection churn rate

### Automated Scripts

#### Health Check Script

```bash
#!/bin/bash
# check-network-health.sh

RPC_URL="http://localhost:8546"

# Get peer count
PEER_COUNT=$(curl -s $RPC_URL -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"net_peerCount","params":[]}' | jq -r '.result')

# Convert hex to decimal
PEER_COUNT_DEC=$((16#${PEER_COUNT#0x}))

echo "Connected Peers: $PEER_COUNT_DEC"

if [ $PEER_COUNT_DEC -lt 5 ]; then
  echo "WARNING: Low peer count!"
  exit 1
fi

echo "Network health: OK"
```

#### Blacklist Cleanup Script

```bash
#!/bin/bash
# cleanup-blacklist.sh

RPC_URL="http://localhost:8546"

# Get blacklisted peers
BLACKLIST=$(curl -s $RPC_URL -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"net_listBlacklistedPeers","params":[]}' | jq -r '.result')

# Get current timestamp
NOW=$(date +%s)000  # Convert to milliseconds

# Remove entries older than 30 days
echo "$BLACKLIST" | jq -c '.[]' | while read entry; do
  ID=$(echo $entry | jq -r '.id')
  ADDED_AT=$(echo $entry | jq -r '.addedAt')
  
  AGE=$(( ($NOW - $ADDED_AT) / 1000 / 86400 ))  # Days
  
  if [ $AGE -gt 30 ]; then
    echo "Removing old entry: $ID (age: $AGE days)"
    curl -s $RPC_URL -X POST -H "Content-Type: application/json" \
      -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"net_removeFromBlacklist\",\"params\":[\"$ID\"]}"
  fi
done
```

## Troubleshooting

### No Peers Connecting

**Symptoms**: `net_peerCount` returns 0 or very low number

**Possible Causes**:
1. Firewall blocking incoming connections
2. Incorrect network configuration
3. Too many peers blacklisted
4. Network issues

**Resolution**:
```bash
# 1. Check if listening
curl -s http://localhost:8546 -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"net_listening","params":[]}'

# 2. Check blacklist size
curl -s http://localhost:8546 -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"net_listBlacklistedPeers","params":[]}' | jq '.result | length'

# 3. Manually connect to known good peers
curl -s http://localhost:8546 -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"net_connectToPeer","params":["enode://..."]}'
```

### Peer Keeps Reconnecting After Blacklist

**Symptoms**: Peer appears in blacklist but still showing in peer list

**Possible Causes**:
1. Blacklist not applied yet (check timing)
2. Different addresses being used
3. Peer was added after connection established

**Resolution**:
```bash
# 1. Disconnect first, then blacklist
curl -s http://localhost:8546 -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"net_disconnectPeer","params":["PEER_ID"]}'

# 2. Then blacklist the address
curl -s http://localhost:8546 -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"net_addToBlacklist","params":["IP_ADDRESS",null,"Persistent reconnection"]}'
```

### Blacklist Not Working

**Symptoms**: Blacklisted peers still connecting

**Possible Causes**:
1. Wrong address format
2. Peer using multiple addresses
3. Blacklist cache issue

**Resolution**:
```bash
# Verify blacklist entry exists
curl -s http://localhost:8546 -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"net_listBlacklistedPeers","params":[]}' | \
  jq '.result[] | select(.id=="IP_ADDRESS")'

# If missing, re-add with correct format
curl -s http://localhost:8546 -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"net_addToBlacklist","params":["EXACT_IP",null,"Test"]}'
```

## Related Documentation

- [JSON-RPC API Reference](../api/JSON_RPC_API_REFERENCE.md) - Complete API documentation
- [Node Configuration](node-configuration.md) - Network configuration settings
- [Log Triage](log-triage.md) - Debugging network issues

## Support

For issues or questions:
- GitHub Issues: https://github.com/chippr-robotics/fukuii/issues
- Documentation: https://chippr-robotics.github.io/fukuii/
