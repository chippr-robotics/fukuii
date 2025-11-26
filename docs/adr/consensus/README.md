# Consensus ADRs

This directory contains Architecture Decision Records related to consensus mechanisms, networking protocols, P2P communication, and blockchain synchronization.

## Naming Convention

Consensus ADRs use the format: `CON-NNN-title.md` where NNN is a zero-padded sequential number.

Examples:
- `CON-001-rlpx-protocol-deviations.md`
- `CON-002-bootstrap-checkpoints.md`

## Current ADRs

- [CON-001: RLPx Protocol Deviations and Peer Bootstrap Challenge](CON-001-rlpx-protocol-deviations-and-peer-bootstrap.md) - Accepted
- [CON-002: Bootstrap Checkpoints for Improved Initial Sync](CON-002-bootstrap-checkpoints.md) - Accepted
- [CON-003: Block Sync Improvements - Enhanced Reliability and Performance](CON-003-block-sync-improvements.md) - Accepted
- [CON-004: MESS (Modified Exponential Subjective Scoring) Implementation](CON-004-mess-implementation.md) - Accepted
- [CON-005: ETH66 Protocol Aware Message Formatting](CON-005-eth66-protocol-aware-message-formatting.md) - Accepted
- [CON-006: ForkId Compatibility During Initial Sync](CON-006-forkid-compatibility-during-initial-sync.md) - Accepted

## Creating a New Consensus ADR

1. Use the next sequential number (e.g., `CON-006-title.md`)
2. Follow the standard ADR template structure
3. Link it in the index above
4. Update the main ADR README
