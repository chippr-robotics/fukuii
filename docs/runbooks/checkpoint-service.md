# Running a Checkpoint Service

This guide explains how to run and use the checkpoint update service in Fukuii to fetch and verify bootstrap checkpoints from multiple sources.

## What is the Checkpoint Service?

The checkpoint update service (`CheckpointUpdateService`) is designed to solve the **initial sync bootstrap problem** where nodes must wait for peer consensus before beginning blockchain synchronization. By providing trusted block references at known heights (checkpoints), nodes can begin syncing immediately without the traditional peer discovery delay.

### Purpose and Use Cases

**Primary Purpose: Faster Initial Sync**
- **Problem**: Traditional nodes wait for 3+ peers to reach consensus on a pivot block before syncing, causing delays during first startup
- **Solution**: Pre-verified checkpoints allow immediate sync start, bypassing the peer wait requirement
- **Benefit**: Reduces initial sync time from minutes/hours to seconds for node bootstrapping

**Use Cases by Network Type:**

1. **Public Networks (ETC Mainnet, Mordor)**
   - Faster onboarding for new node operators
   - Improved reliability in regions with poor network connectivity
   - Critical for nodes behind restrictive firewalls with limited peer access
   - Reduces bootstrap time during network disruptions or low peer availability

2. **Private/Enterprise Networks** ⭐
   - **Essential for private blockchain deployments**: Private networks often have limited peers (3-10 nodes), making peer-based pivot selection unreliable
   - **Consortium networks**: Pre-defined checkpoints ensure all consortium members sync from agreed-upon trusted blocks
   - **Development/Testing environments**: Rapidly deploy test networks without peer discovery delays
   - **Air-gapped deployments**: Nodes can sync without external peer connectivity by using pre-loaded checkpoints
   - **Permissioned networks**: Centrally managed checkpoint updates ensure all nodes maintain consensus on chain history

3. **Disaster Recovery**
   - Quick recovery from database corruption by syncing from verified checkpoints
   - Faster node replacement in production environments
   - Simplified backup/restore procedures

### How It Works

The checkpoint service operates in two modes:

**Mode 1: Static Checkpoints (BootstrapCheckpointLoader)**
- Checkpoints are hardcoded in chain configuration files (`etc-chain.conf`, `mordor-chain.conf`)
- Loaded once at node startup if database is empty (genesis-only state)
- Used as trusted reference points during initial sync
- Documented in [CON-002: Bootstrap Checkpoints ADR](../adr/consensus/CON-002-bootstrap-checkpoints.md)

**Mode 2: Dynamic Updates (CheckpointUpdateService)** ⭐ *This document*
- Fetches checkpoint data from HTTP endpoints
- Verifies checkpoints across multiple sources using quorum consensus
- Enables automated checkpoint updates for production deployments
- **Particularly useful for private networks** where operators maintain their own checkpoint servers

## Overview

The checkpoint update service fetches trusted checkpoint data from configured sources and verifies them using quorum consensus. This is useful for:

- **Private Networks**: Maintaining operator-controlled checkpoint sources for consortium or enterprise deployments
- **Automated Updates**: Keeping checkpoint configurations up-to-date without manual intervention
- **Multi-Source Verification**: Verifying checkpoint data from multiple independent sources for security
- **Production Deployments**: Automating checkpoint management across node fleets

## Architecture

The checkpoint service implements a multi-source verification pattern:

```
┌─────────────────────────────────────────────────┐
│     CheckpointUpdateService                     │
│                                                 │
│  1. Fetch from multiple sources concurrently   │
│  2. Parse JSON responses                       │
│  3. Verify with quorum consensus               │
│  4. Return verified checkpoints                │
└─────────────────────────────────────────────────┘
           │
           ├──> Source 1: Official ETC
           ├──> Source 2: BlockScout
           └──> Source 3: Expedition
```

## JSON Format

Checkpoint sources must return JSON in the following format:

```json
{
  "network": "etc-mainnet",
  "checkpoints": [
    {
      "blockNumber": 19250000,
      "blockHash": "0x1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
    },
    {
      "blockNumber": 14525000,
      "blockHash": "0xabcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
    }
  ]
}
```

### Field Descriptions

- **network**: Network identifier (e.g., "etc-mainnet", "mordor")
- **checkpoints**: Array of checkpoint objects
- **blockNumber**: Block height as a number
- **blockHash**: 32-byte block hash as hex string (with or without "0x" prefix)

## Usage

### Basic Example

```scala
import com.chipprbots.ethereum.blockchain.data.{CheckpointUpdateService, CheckpointSource}
import org.apache.pekko.actor.ActorSystem
import scala.concurrent.ExecutionContext.Implicits.global

implicit val system = ActorSystem("checkpoint-system")

val service = new CheckpointUpdateService()

// Define checkpoint sources
val sources = Seq(
  CheckpointSource("Official ETC", "https://checkpoints.ethereumclassic.org/mainnet.json", priority = 1),
  CheckpointSource("BlockScout", "https://blockscout.com/etc/mainnet/api/checkpoints", priority = 2)
)

// Fetch and verify checkpoints with quorum of 2
val verifiedCheckpoints = service.fetchLatestCheckpoints(sources, quorumSize = 2)

verifiedCheckpoints.foreach { checkpoints =>
  checkpoints.foreach { checkpoint =>
    println(s"Verified: Block ${checkpoint.blockNumber}, " +
            s"Hash ${checkpoint.blockHash.take(10).map("%02x".format(_)).mkString}..., " +
            s"Agreed by ${checkpoint.sourceCount} sources")
  }
}
```

### Verifying a Single Checkpoint

```scala
import com.chipprbots.ethereum.blockchain.data.BootstrapCheckpoint
import org.apache.pekko.util.ByteString
import org.bouncycastle.util.encoders.Hex

val checkpoint = BootstrapCheckpoint(
  blockNumber = BigInt(19250000),
  blockHash = ByteString(Hex.decode("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"))
)

val isValid = service.verifyCheckpoint(checkpoint, sources, minAgreement = 2)

isValid.foreach { valid =>
  if (valid) {
    println("Checkpoint verified successfully!")
  } else {
    println("Checkpoint verification failed!")
  }
}
```

## Configuration

### Default Sources

The service provides default checkpoint sources for ETC mainnet and Mordor testnet:

```scala
import com.chipprbots.ethereum.blockchain.data.CheckpointUpdateService

// ETC Mainnet sources
val etcSources = CheckpointUpdateService.defaultEtcSources

// Mordor Testnet sources
val mordorSources = CheckpointUpdateService.defaultMordorSources
```

### Custom Sources

You can define custom checkpoint sources for any network:

```scala
val customSources = Seq(
  CheckpointSource(
    name = "Internal Mirror",
    url = "https://internal.example.com/checkpoints.json",
    priority = 1
  ),
  CheckpointSource(
    name = "Backup Source",
    url = "https://backup.example.com/checkpoints.json",
    priority = 2
  )
)
```

### Private Network Configuration

For private/enterprise networks, configure checkpoint sources pointing to your internal infrastructure:

```scala
// Example: Private consortium network
val privateNetworkSources = Seq(
  CheckpointSource(
    name = "Primary Consortium Node",
    url = "https://node1.consortium.internal/api/checkpoints.json",
    priority = 1
  ),
  CheckpointSource(
    name = "Secondary Consortium Node",
    url = "https://node2.consortium.internal/api/checkpoints.json",
    priority = 1
  ),
  CheckpointSource(
    name = "Backup Archive Node",
    url = "https://archive.consortium.internal/api/checkpoints.json",
    priority = 2
  )
)

// For private networks with few nodes, lower quorum is acceptable
// since all sources are trusted consortium members
val quorum = 2 // Majority of 3 sources
service.fetchLatestCheckpoints(privateNetworkSources, quorumSize = quorum)
```

**Private Network Best Practices:**
- Use internal DNS or static IPs for checkpoint sources
- Each consortium member should run a checkpoint endpoint
- Update checkpoints after major network upgrades or hard forks
- Use HTTPS with internal certificates for secure transport
- Set shorter timeouts for LAN environments (e.g., 5s instead of 30s)

### Quorum Size

The quorum size determines how many sources must agree on a checkpoint for it to be verified:

```scala
// Require all 3 sources to agree (highest security, for critical deployments)
service.fetchLatestCheckpoints(sources, quorumSize = 3)

// Require majority (recommended for most use cases)
val quorum = CheckpointUpdateService.recommendedQuorum(sources.size)
service.fetchLatestCheckpoints(sources, quorumSize = quorum)

// Private network with trusted sources (2 out of 3 consortium members)
service.fetchLatestCheckpoints(privateNetworkSources, quorumSize = 2)
```

## Setting Up a Checkpoint Endpoint

To set up your own checkpoint endpoint:

### 1. Create the JSON File

Create a JSON file with the required format:

```bash
cat > mainnet.json <<EOF
{
  "network": "etc-mainnet",
  "checkpoints": [
    {
      "blockNumber": 19250000,
      "blockHash": "0xYOUR_BLOCK_HASH_HERE"
    },
    {
      "blockNumber": 14525000,
      "blockHash": "0xYOUR_BLOCK_HASH_HERE"
    }
  ]
}
EOF
```

### 2. Verify Block Hashes

**For Public Networks:**
Always verify block hashes from multiple trusted sources:

```bash
# Query your fully-synced node
fukuii eth_getBlockByNumber 19250000 false

# Compare with block explorers
curl https://blockscout.com/etc/mainnet/api?module=block&action=getblockreward&blockno=19250000
```

**For Private Networks:**
Extract block hashes from your network's authoritative nodes:

```bash
# Query the primary consortium node
curl -X POST --data '{
  "jsonrpc":"2.0",
  "method":"eth_getBlockByNumber",
  "params":["0x1000", false],
  "id":1
}' http://primary-node.internal:8545

# Verify against secondary nodes
curl -X POST --data '{
  "jsonrpc":"2.0",
  "method":"eth_getBlockByNumber",
  "params":["0x1000", false],
  "id":1
}' http://secondary-node.internal:8545

# Extract just the hash
curl ... | jq -r '.result.hash'
```

### 3. Serve the File

#### Option A: Static Web Server (Recommended for Private Networks)

```bash
# Using nginx (best for production private networks)
cp mainnet.json /var/www/html/checkpoints/

# Configure nginx for internal access only
# /etc/nginx/sites-available/checkpoints
server {
    listen 80;
    server_name checkpoint-server.internal;
    
    location /checkpoints/ {
        root /var/www/html;
        # Restrict to internal network
        allow 10.0.0.0/8;
        allow 172.16.0.0/12;
        allow 192.168.0.0/16;
        deny all;
    }
}

# Or using Python for development/testing
python3 -m http.server 8000 --directory /path/to/checkpoints/
```

#### Option B: AWS S3 (For Cloud Private Networks)

```bash
# Private bucket with VPC endpoint access
aws s3 cp mainnet.json s3://my-private-bucket/checkpoints/mainnet.json

# Configure bucket policy for VPC-only access
aws s3api put-bucket-policy --bucket my-private-bucket --policy '{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Deny",
    "Principal": "*",
    "Action": "s3:GetObject",
    "Resource": "arn:aws:s3:::my-private-bucket/*",
    "Condition": {
      "StringNotEquals": {
        "aws:sourceVpc": "vpc-xxxxxxxx"
      }
    }
  }]
}'

# Public S3 (for public networks only)
aws s3 cp mainnet.json s3://my-bucket/checkpoints/mainnet.json --acl public-read
```

#### Option C: GitHub Pages (Public Networks Only)

```bash
# Commit to docs/ directory in your repository
git add docs/checkpoints/mainnet.json
git commit -m "Add checkpoint data"
git push

# Enable GitHub Pages for the docs/ directory
# Accessible at: https://USERNAME.github.io/REPO/checkpoints/mainnet.json
```

#### Option D: Internal API Server (Enterprise Private Networks)

For enterprise deployments, serve checkpoints through your existing API infrastructure:

```python
# Example: Flask API for checkpoint service
from flask import Flask, jsonify
import psycopg2

app = Flask(__name__)

@app.route('/api/checkpoints.json')
def get_checkpoints():
    # Query from your blockchain database
    conn = psycopg2.connect("dbname=blockchain")
    cur = conn.execute("""
        SELECT block_number, block_hash 
        FROM checkpoints 
        WHERE is_verified = true 
        ORDER BY block_number DESC
    """)
    
    checkpoints = [
        {"blockNumber": str(row[0]), "blockHash": row[1]}
        for row in cur.fetchall()
    ]
    
    return jsonify({
        "network": "private-consortium",
        "checkpoints": checkpoints
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8080)
```

### 4. Enable CORS (Public Networks) or Internal Access Control (Private Networks)

**For Public Networks:**
If serving from a different domain, enable CORS:

```nginx
# nginx configuration
location /checkpoints/ {
    add_header 'Access-Control-Allow-Origin' '*';
    add_header 'Access-Control-Allow-Methods' 'GET';
}
```

**For Private Networks:**
Implement internal access controls instead of CORS:

```nginx
# nginx configuration for private network
location /checkpoints/ {
    # Allow only internal network ranges
    allow 10.0.0.0/8;
    allow 172.16.0.0/12;
    allow 192.168.0.0/16;
    deny all;
    
    # Optional: Add authentication
    auth_basic "Checkpoint Service";
    auth_basic_user_file /etc/nginx/.htpasswd;
}
```

## Private Network Deployment Example

Here's a complete example for setting up a checkpoint service in a private consortium network:

### Scenario: 5-Node Private Consortium

**Network Setup:**
- 3 validator nodes (consortium members)
- 1 archive node (for historical data)
- 1 checkpoint service (managed by lead consortium member)

**Step 1: Configure Checkpoint Sources on Each Node**

```scala
// In your node configuration
val consortiumCheckpointSources = Seq(
  CheckpointSource(
    name = "Primary Checkpoint Server",
    url = "http://checkpoint.consortium.internal:8080/api/checkpoints.json",
    priority = 1
  ),
  CheckpointSource(
    name = "Validator Node 1",
    url = "http://validator1.consortium.internal:8545/checkpoints",
    priority = 2
  ),
  CheckpointSource(
    name = "Archive Node",
    url = "http://archive.consortium.internal:8545/checkpoints",
    priority = 2
  )
)

// Require 2 out of 3 sources to agree
val service = new CheckpointUpdateService()
service.fetchLatestCheckpoints(consortiumCheckpointSources, quorumSize = 2)
```

**Step 2: Set Up Checkpoint Service**

```bash
# On checkpoint server
cat > /var/www/html/api/checkpoints.json <<EOF
{
  "network": "private-consortium-v1",
  "checkpoints": [
    {
      "blockNumber": "5000",
      "blockHash": "0x..."
    },
    {
      "blockNumber": "10000",
      "blockHash": "0x..."
    }
  ]
}
EOF

# Update checkpoints after each major milestone
./scripts/update-checkpoints.sh
```

**Step 3: Automated Checkpoint Updates**

```scala
// Schedule periodic updates (every 6 hours)
import scala.concurrent.duration._

system.scheduler.scheduleAtFixedRate(
  initialDelay = 0.hours,
  interval = 6.hours
) { () =>
  val service = new CheckpointUpdateService()
  
  service.fetchLatestCheckpoints(consortiumCheckpointSources, quorumSize = 2).foreach { checkpoints =>
    if (checkpoints.nonEmpty) {
      log.info(s"Updated ${checkpoints.size} checkpoints from consortium sources")
      service.updateConfiguration(checkpoints)
    }
  }
}
```

**Benefits for Private Networks:**
- **Faster node deployment**: New consortium members can join and sync immediately
- **Network independence**: No dependency on external public infrastructure
- **Controlled updates**: Consortium manages checkpoint timing and selection
- **Compliance**: Meet enterprise requirements for internal-only data sources
- **Disaster recovery**: Rapid network recovery from agreed-upon checkpoints

## Security Considerations

### Checkpoint Verification

**For All Networks:**
1. **Multiple Sources**: Always use multiple independent sources for verification
2. **Quorum Consensus**: Require majority agreement (recommended quorum size: `(n+1)/2`)
3. **Known Blocks**: Use well-known fork activation blocks as checkpoints
4. **Regular Updates**: Update checkpoint data after major network upgrades

**For Private Networks - Additional Considerations:**
5. **Internal Source Trust**: Verify that checkpoint sources are controlled by trusted consortium members
6. **Network Isolation**: Ensure checkpoint endpoints are only accessible within the private network
7. **Authentication**: Consider adding authentication to checkpoint endpoints
8. **Audit Logging**: Track which nodes fetch checkpoints and when
9. **Version Control**: Maintain checkpoint history to enable rollback if needed
10. **Governance**: Establish consortium agreement process for checkpoint updates

### Source Trust

**Public Networks:**
Only use checkpoint sources that:
- Are operated by trusted organizations
- Have a track record of reliability
- Use HTTPS for secure transport
- Publish block hashes that can be independently verified

**Private Networks:**
Additional requirements for checkpoint sources:
- **Consortium Membership**: Sources should be operated by consortium members
- **Internal PKI**: Use internal certificates for HTTPS on private networks
- **Access Control**: Implement IP whitelisting or VPN-only access
- **Multi-Party Verification**: Require sign-off from multiple consortium members before updating checkpoints
- **Backup Sources**: Maintain at least one offline/backup checkpoint source

### Recommended Sources for ETC

- **Official ETC Resources**: Community-maintained checkpoint data
- **Block Explorers**: BlockScout, Expedition
- **Node Operators**: Major mining pools and infrastructure providers
- **Your Own Node**: Run a fully-synced node for independent verification

### Recommended Sources for Private Networks

- **Primary Validator**: Main consensus node operated by lead consortium member
- **Secondary Validators**: Checkpoint endpoints on each consortium member's infrastructure
- **Archive Node**: Dedicated historical data node for long-term checkpoint verification
- **Offline Backup**: Manual checkpoint file stored in version control (Git) as fallback

## Monitoring

### Logging

The service provides detailed logging at different levels:

```scala
// Enable debug logging to see detailed checkpoint verification
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.{Level, Logger}

val logger = LoggerFactory.getLogger("com.chipprbots.ethereum.blockchain.data").asInstanceOf[Logger]
logger.setLevel(Level.DEBUG)
```

### Expected Log Messages

```
[INFO]  Fetching checkpoints from 3 sources (quorum: 2)
[DEBUG] Fetching checkpoints from Official ETC: https://checkpoints.ethereumclassic.org/mainnet.json
[DEBUG] Successfully fetched 4 checkpoints from Official ETC
[DEBUG] Successfully parsed 4 checkpoints from JSON for network: etc-mainnet
[INFO]  Checkpoint verified: block 19250000, hash 1234567890..., agreement from 2/3 sources
[INFO]  Updating configuration with 4 verified checkpoints
```

### Error Handling

```scala
service.fetchLatestCheckpoints(sources, quorumSize = 2).recover {
  case ex: Exception =>
    println(s"Failed to fetch checkpoints: ${ex.getMessage}")
    Seq.empty
}
```

## Best Practices

1. **Use Multiple Sources**: Configure at least 3 independent checkpoint sources
2. **Set Appropriate Quorum**: Use majority consensus (recommended: `(n+1)/2`)
3. **Regular Updates**: Fetch new checkpoints after network upgrades
4. **Monitor Failures**: Track and alert on checkpoint fetch failures
5. **Verify Independently**: Cross-reference checkpoint data with your own node
6. **HTTPS Only**: Always use HTTPS sources to prevent MITM attacks
7. **Timeout Configuration**: Set reasonable timeouts (default: 10s connect, 30s idle)

## Troubleshooting

### Issue: "Only X sources succeeded, required Y"

**Cause**: Not enough sources returned valid checkpoint data.

**Solution**:
- Check network connectivity to checkpoint sources
- Verify source URLs are accessible
- Review source logs for HTTP errors
- Reduce quorum size temporarily for testing

### Issue: "JSON parsing error"

**Cause**: Checkpoint source returned invalid JSON.

**Solution**:
- Verify the source URL returns valid JSON
- Check the JSON format matches the expected schema
- Test the URL manually: `curl https://source-url.com/checkpoints.json`

### Issue: "Failed to convert checkpoint data"

**Cause**: Invalid hex hash in checkpoint data.

**Solution**:
- Verify block hashes are valid 32-byte hex strings
- Ensure hashes are properly formatted (with or without "0x" prefix)
- Check source data quality

## Integration Example

### Automated Checkpoint Updates

```scala
import scala.concurrent.duration._
import org.apache.pekko.actor.ActorSystem

implicit val system = ActorSystem("checkpoint-updater")
import system.dispatcher

// Schedule periodic checkpoint updates
system.scheduler.scheduleAtFixedRate(
  initialDelay = 0.seconds,
  interval = 24.hours
) { () =>
  val service = new CheckpointUpdateService()
  val sources = CheckpointUpdateService.defaultEtcSources
  
  service.fetchLatestCheckpoints(sources, quorumSize = 2).foreach { checkpoints =>
    if (checkpoints.nonEmpty) {
      service.updateConfiguration(checkpoints)
      println(s"Updated ${checkpoints.size} checkpoints")
    }
  }
}
```

## Related Documentation

- [CON-002: Bootstrap Checkpoints](../adr/consensus/CON-002-bootstrap-checkpoints.md) - Architecture decision record
- [Node Configuration](node-configuration.md) - Configuring bootstrap checkpoints
- [First Start Guide](first-start.md) - Initial node setup with checkpoints

## Support

For issues or questions about the checkpoint service:

1. Check the [troubleshooting section](#troubleshooting) above
2. Review logs with DEBUG level enabled
3. Open an issue on the [Fukuii GitHub repository](https://github.com/chippr-robotics/fukuii/issues)
4. Join the ETC community channels for checkpoint verification assistance
