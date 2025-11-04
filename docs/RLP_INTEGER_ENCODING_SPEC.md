# RLP Integer Encoding - Network Sync Error Fix

> **Note**: This issue has been moved to the official runbook documentation.

## Location

This issue is now documented in:

**[docs/runbooks/known-issues.md - Issue 13: Network Sync Error - Zero Length BigInteger](./runbooks/known-issues.md#issue-13-network-sync-error---zero-length-biginteger)**

## Quick Reference

- **Error**: `NumberFormatException: Zero length BigInteger`
- **Status**: Fixed in v1.0.1
- **Severity**: High
- **Impact**: Network sync failures

## Summary

The ArbitraryIntegerMpt serializer did not handle empty byte arrays correctly when deserializing BigInt values. According to Ethereum RLP specification, empty byte arrays represent integer zero, but Java's BigInteger constructor throws an exception on empty arrays.

**Fix**: Check for empty arrays before calling BigInt constructor:
```scala
if (bytes.isEmpty) BigInt(0) else BigInt(bytes)
```

## Full Documentation

For complete details including:
- Symptoms and root cause analysis
- Ethereum specification compliance
- Test coverage (31 new tests)
- Verification procedures
- Related issues and references

See: **[docs/runbooks/known-issues.md#issue-13](./runbooks/known-issues.md#issue-13-network-sync-error---zero-length-biginteger)**
