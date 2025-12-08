# Welcome to the Gorgoroth Trials - Fukuii Alpha Testing Campaign

**December 8, 2025**  
**Contributors**: @realcodywburns, @fukuii  
**Testing Period**: December 8, 2025 - December 31, 2025 (23:59:59 UTC)

---

## Introduction

The Ethereum Classic community is invited to participate in the **Gorgoroth Trials** - a comprehensive alpha testing campaign for Fukuii, a high-performance Scala 3 implementation of the Ethereum Classic protocol. This is your opportunity to help validate Fukuii's compatibility and readiness before mainnet deployment.

Named after the volcanic plateau in Mordor where Sauron trained his armies, the Gorgoroth Trials represent a crucible through which Fukuii must pass to prove its mettle alongside battle-tested clients like Core-Geth and Hyperledger Besu.

## What Are the Battlenets?

The **Battlenets** are specialized testing environments designed to validate different aspects of Fukuii's implementation:

### 1. Gorgoroth - The Multi-Client Proving Ground

**Gorgoroth** is a private test network where Fukuii faces off against Core-Geth and Hyperledger Besu in controlled combat scenarios. Here we validate:

- **Network Communication** - Can Fukuii shake hands with diverse peers?
- **Mining Compatibility** - Do blocks mined by different clients achieve consensus?
- **Protocol Compatibility** - Does Fukuii speak the same language as Core-Geth and Besu?
- **Faucet Service** - Can testnet tokens flow to those who need them?

**Configurations Available:**
- `3nodes` - 3 Fukuii nodes (baseline validation)
- `fukuii-geth` - 3 Fukuii + 3 Core-Geth nodes
- `fukuii-besu` - 3 Fukuii + 3 Besu nodes
- `mixed` - 3 Fukuii + 3 Core-Geth + 3 Besu nodes (ultimate test)

**Time Investment:** 1-2 hours for basic validation

### 2. Cirith Ungol - The Real-World Challenge (Bonus Trial)

For the more adventurous among you, **Cirith Ungol** awaits. This single-node testing environment connects to the real Ethereum Classic network, validating Fukuii's ability to sync with mainnet's 20M+ blocks and Mordor testnet's history.

**What Cirith Ungol Tests:**
- **SNAP Sync** - Can Fukuii rapidly sync 20M+ blocks?
- **Fast Sync** - Does state synchronization work correctly?
- **Long-term Stability** - Will Fukuii stand through a 24-hour siege?
- **Peer Diversity** - Can Fukuii connect to the wild public network?

**Time Investment:** 4-8 hours (mostly automated sync time)

**Networks Supported:**
- ETC Mainnet (20M+ blocks, 2-6 hour sync)
- Mordor Testnet (10M+ blocks, 1-3 hour sync)

## Why Community Validation Matters

### The Stakes Are High

Fukuii aims to join the ranks of production-ready Ethereum Classic clients. Before we can confidently recommend it for mainnet use, we need to know:

1. **Does it work as advertised?** - Theory meets practice in the Battlenets
2. **Does it play well with others?** - Multi-client networks are ETC's strength
3. **Will it survive in the wild?** - Real-world conditions reveal hidden issues
4. **Is it ready for your infrastructure?** - Different setups expose different challenges

### You Are the Quality Gate

The Fukuii development team has done extensive internal testing, but nothing replaces the diversity of real-world scenarios that community testing provides:

- **Different Operating Systems** - Your Linux, macOS, or Windows setup might reveal OS-specific issues
- **Different Network Conditions** - Your internet connection, firewall rules, and ISP matter
- **Different Hardware** - Your CPU, RAM, and disk configurations create unique scenarios
- **Different Use Cases** - How you want to use Fukuii might differ from our assumptions

### Building Trust Through Transparency

By validating Fukuii publicly and documenting the results, we:

- Build confidence in the implementation
- Create a knowledge base for future users
- Identify issues before they affect production users
- Demonstrate ETC's commitment to multi-client diversity

## What We Expect You To Do

### 1. Choose Your Trial

**For Most Testers - Start with Gorgoroth:**
```bash
# Clone the repository
git clone https://github.com/chippr-robotics/fukuii.git
cd fukuii/ops/gorgoroth

# Start a test network
fukuii-cli start 3nodes

# Wait 60 seconds for initialization
sleep 60

# Run the automated test suite
cd test-scripts
./run-test-suite.sh 3nodes
```

**For Advanced Testers - Try Cirith Ungol (Bonus):**
```bash
# After completing Gorgoroth trials
cd fukuii/ops/cirith-ungol

# Start syncing with ETC mainnet
./start.sh start

# Monitor progress (SNAP sync: 2-6 hours)
./start.sh logs

# Collect results
./start.sh collect-logs
```

### 2. Document Your Experience

We need to know:

- **What worked** - Success stories help us understand what's stable
- **What didn't work** - Failures help us fix bugs before release
- **What was confusing** - Documentation gaps need to be filled
- **What surprised you** - Unexpected behavior reveals design issues

### 3. Submit Field Reports

Your feedback is invaluable. Here's how to share it:

#### Option 1: GitHub Issues with Field Report Template (Preferred)

Visit the [Fukuii GitHub Issues](https://github.com/chippr-robotics/fukuii/issues/new/choose) and select the **"Gorgoroth Trials Field Report"** template.

This template will guide you through submitting:
- System information
- Test duration and configuration
- Test results (pass/fail for each area)
- What worked well
- Issues encountered
- Performance metrics
- Suggestions for improvement

**Quick Link**: [Submit Field Report](https://github.com/chippr-robotics/fukuii/issues/new?template=gorgoroth_field_report.md&labels=gorgoroth-trials,validation-results)

#### Option 2: GitHub Discussions (For General Feedback)

For less formal feedback or questions, use [GitHub Discussions](https://github.com/chippr-robotics/fukuii/discussions) in the "Gorgoroth Trials" category.

#### Option 3: GitHub Issues (For Specific Bugs)

#### Option 3: GitHub Issues (For Specific Bugs)

If you discover a specific bug unrelated to your field report, create a GitHub issue:

1. Go to https://github.com/chippr-robotics/fukuii/issues/new/choose
2. Select "Bug report" template
3. Add label: `gorgoroth-trials`
4. Include:
   - Steps to reproduce
   - Expected behavior
   - Actual behavior
   - Log snippets
   - System information

### 4. Help Others

The Gorgoroth Trials are a community effort. If you figure something out:

- Share your solutions in discussions
- Help troubleshoot others' issues
- Document workarounds you discover
- Suggest improvements to documentation

## Testing Timeline

### Alpha Testing Period

**Start**: December 8, 2025 (00:00:00 UTC)  
**End**: December 31, 2025 (23:59:59 UTC)

### What Happens After?

1. **January 1-7, 2026** - Development team analyzes all field reports
2. **January 8-14, 2026** - Critical bugs are addressed
3. **January 15, 2026** - Beta testing phase begins (if alpha is successful)
4. **TBD** - Production release recommendation to ETC community

Your feedback during the alpha testing period directly impacts the timeline and quality of the beta release.

## Quick Start Guide

### Prerequisites

- Docker 20.10+ with Docker Compose 2.0+
- 8GB+ RAM (16GB recommended for Cirith Ungol)
- 20GB+ free disk space (100GB for Cirith Ungol mainnet)
- Stable internet connection
- `curl`, `jq` for testing scripts

### 5-Minute Gorgoroth Quick Start

```bash
# 1. Clone repository
git clone https://github.com/chippr-robotics/fukuii.git
cd fukuii/ops/gorgoroth

# 2. Install fukuii-cli (optional but recommended)
sudo cp ../tools/fukuii-cli.sh /usr/local/bin/fukuii-cli
sudo chmod +x /usr/local/bin/fukuii-cli

# 3. Start a test network
fukuii-cli start 3nodes

# 4. Wait for initialization
echo "Waiting 60 seconds for nodes to initialize..."
sleep 60

# 5. Run automated tests
cd test-scripts
./run-test-suite.sh 3nodes

# 6. Check results in test-results-TIMESTAMP directory
```

### Results Location

All test results are saved in timestamped directories:
- Gorgoroth: `ops/gorgoroth/test-scripts/test-results-YYYYMMDD-HHMMSS/`
- Cirith Ungol: `ops/cirith-ungol/captured-logs/`

## Available Documentation

Comprehensive guides are available to help you:

### Gorgoroth Testing
- **[Gorgoroth Quick Start](https://github.com/chippr-robotics/fukuii/blob/main/ops/gorgoroth/QUICKSTART.md)** - Get running in 5 minutes
- **[Compatibility Testing Guide](https://github.com/chippr-robotics/fukuii/blob/main/ops/gorgoroth/COMPATIBILITY_TESTING.md)** - Detailed test procedures
- **[Faucet Testing Guide](https://github.com/chippr-robotics/fukuii/blob/main/ops/gorgoroth/FAUCET_TESTING.md)** - Test token distribution
- **[Validation Status](https://github.com/chippr-robotics/fukuii/blob/main/ops/gorgoroth/VALIDATION_STATUS.md)** - Current progress

### Cirith Ungol Testing (Bonus)
- **[Cirith Ungol Testing Guide](https://github.com/chippr-robotics/fukuii/blob/main/ops/cirith-ungol/TESTING_GUIDE.md)** - Real-world sync validation

### General Documentation
- **[Fukuii Documentation](https://github.com/chippr-robotics/fukuii/tree/main/docs)** - Full documentation index
- **[Architecture Overview](https://github.com/chippr-robotics/fukuii/blob/main/docs/architecture/architecture-overview.md)** - How Fukuii works

## Troubleshooting

### Common Issues

**Nodes won't connect:**
- Wait 60-90 seconds after first start for node key generation
- Check static-nodes.json files have correct enode IDs
- Verify Docker networking is functioning

**Port conflicts:**
- Another service may be using ports 8545, 8546, or 30303
- Stop conflicting services or modify docker-compose.yml

**Out of disk space:**
- Gorgoroth: Needs ~5GB for test network
- Cirith Ungol: Needs 80-100GB for ETC mainnet

**Tests failing:**
- Check all containers are running: `docker ps`
- View logs: `fukuii-cli logs 3nodes`
- Collect diagnostic logs: `fukuii-cli collect-logs 3nodes ./my-logs`

### Getting Help

- **GitHub Discussions**: Ask questions, share findings
- **Documentation**: Check the guides linked above
- **Issue Tracker**: Report bugs with reproduction steps
- **Community**: Help each other succeed

## Recognition and Rewards

While this is volunteer-based community testing, we recognize and value your contributions:

### Hall of Fame

Testers who submit comprehensive field reports will be:
- Listed in the project's CONTRIBUTORS.md file
- Mentioned in release notes
- Given special recognition in community channels

### What Makes a Great Field Report?

- **Completeness** - All sections filled out
- **Clarity** - Clear description of issues and successes
- **Evidence** - Log snippets, screenshots, metrics
- **Reproducibility** - Steps others can follow
- **Constructiveness** - Suggestions for improvement

## A Note on Cirith Ungol (The Bonus Trial)

Cirith Ungol represents the next level of validation - testing Fukuii against the real Ethereum Classic network. This is **optional** but highly valuable:

**Why Test with Cirith Ungol?**
- Validates sync performance with 20M+ real blocks
- Tests peer diversity (public network has many client types)
- Reveals issues that only appear with substantial state
- Measures real-world resource usage

**When to Try Cirith Ungol:**
- After successfully completing Gorgoroth trials
- When you have 4-8 hours for sync to complete
- If you have 100GB+ available disk space
- If you're comfortable with longer-running tests

**What We Learn:**
- SNAP sync performance and reliability
- Fast sync completion and state verification
- Long-term stability (24+ hour continuous operation)
- Memory usage patterns with large state
- Disk I/O characteristics

Even if only a handful of testers attempt Cirith Ungol, the data will be invaluable for understanding Fukuii's production readiness.

## Final Thoughts

The Gorgoroth Trials are more than just testing - they're a demonstration of the Ethereum Classic community's commitment to:

- **Decentralization** - Multiple independent client implementations
- **Quality** - Thorough validation before production use
- **Transparency** - Open testing and public results
- **Collaboration** - Community-driven development

Your participation, whether testing for 15 minutes or running week-long stability tests, contributes to a stronger, more resilient Ethereum Classic network.

### The Challenge Awaits

The gates of Gorgoroth are open. The trials have begun.

Will Fukuii prove worthy to join the ranks of production Ethereum Classic clients? Your testing will help answer that question.

**Ready to begin?**

1. Visit the [Fukuii Repository](https://github.com/chippr-robotics/fukuii)
2. Follow the [Gorgoroth Quick Start](https://github.com/chippr-robotics/fukuii/blob/main/ops/gorgoroth/QUICKSTART.md)
3. Submit your field report to [GitHub Discussions](https://github.com/chippr-robotics/fukuii/discussions)

---

## Important Links

- **Repository**: https://github.com/chippr-robotics/fukuii
- **Discussions**: https://github.com/chippr-robotics/fukuii/discussions
- **Issues**: https://github.com/chippr-robotics/fukuii/issues
- **Documentation**: https://github.com/chippr-robotics/fukuii/tree/main/docs
- **Gorgoroth Quick Start**: https://github.com/chippr-robotics/fukuii/blob/main/ops/gorgoroth/QUICKSTART.md
- **Cirith Ungol Guide**: https://github.com/chippr-robotics/fukuii/blob/main/ops/cirith-ungol/TESTING_GUIDE.md

---

**For the glory of Ethereum Classic!**

*- The Fukuii Development Team*  
*@realcodywburns | @fukuii*

---

*This announcement will also be published on the [Ethereum Classic website](https://ethereumclassic.org) for broader community visibility.*
