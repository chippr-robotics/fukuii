# Running a Checkpoint Service

This guide explains how to run and use the checkpoint update service in Fukuii to fetch and verify bootstrap checkpoints from multiple sources.

## Overview

The checkpoint update service (`CheckpointUpdateService`) fetches trusted checkpoint data from configured sources and verifies them using quorum consensus. This is useful for:

- Maintaining up-to-date checkpoint configurations
- Verifying checkpoint data from multiple independent sources
- Automating checkpoint updates for production deployments

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

You can define custom checkpoint sources:

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

### Quorum Size

The quorum size determines how many sources must agree on a checkpoint for it to be verified:

```scala
// Require all 3 sources to agree
service.fetchLatestCheckpoints(sources, quorumSize = 3)

// Require majority (recommended)
val quorum = CheckpointUpdateService.recommendedQuorum(sources.size)
service.fetchLatestCheckpoints(sources, quorumSize = quorum)
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

Always verify block hashes from multiple trusted sources:

```bash
# Query your fully-synced node
fukuii eth_getBlockByNumber 19250000 false

# Compare with block explorers
curl https://blockscout.com/etc/mainnet/api?module=block&action=getblockreward&blockno=19250000
```

### 3. Serve the File

#### Option A: Static Web Server

```bash
# Using nginx
cp mainnet.json /var/www/html/checkpoints/

# Or using Python
python3 -m http.server 8000 --directory /path/to/checkpoints/
```

#### Option B: AWS S3

```bash
aws s3 cp mainnet.json s3://my-bucket/checkpoints/mainnet.json --acl public-read
```

#### Option C: GitHub Pages

```bash
# Commit to docs/ directory in your repository
git add docs/checkpoints/mainnet.json
git commit -m "Add checkpoint data"
git push

# Enable GitHub Pages for the docs/ directory
# Accessible at: https://USERNAME.github.io/REPO/checkpoints/mainnet.json
```

### 4. Enable CORS (Optional)

If serving from a different domain, enable CORS:

```nginx
# nginx configuration
location /checkpoints/ {
    add_header 'Access-Control-Allow-Origin' '*';
    add_header 'Access-Control-Allow-Methods' 'GET';
}
```

## Security Considerations

### Checkpoint Verification

1. **Multiple Sources**: Always use multiple independent sources for verification
2. **Quorum Consensus**: Require majority agreement (recommended quorum size: `(n+1)/2`)
3. **Known Blocks**: Use well-known fork activation blocks as checkpoints
4. **Regular Updates**: Update checkpoint data after major network upgrades

### Source Trust

Only use checkpoint sources that:

- Are operated by trusted organizations
- Have a track record of reliability
- Use HTTPS for secure transport
- Publish block hashes that can be independently verified

### Recommended Sources for ETC

- **Official ETC Resources**: Community-maintained checkpoint data
- **Block Explorers**: BlockScout, Expedition
- **Node Operators**: Major mining pools and infrastructure providers
- **Your Own Node**: Run a fully-synced node for independent verification

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
