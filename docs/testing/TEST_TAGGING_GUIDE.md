# Test Tagging Guide for Fukuii

This document provides guidance on applying ScalaTest tags to test files in the Fukuii project, implementing the test categorization strategy defined in TEST-002.

## Overview

ScalaTest tags enable selective test execution for different CI/CD scenarios:
- **Tier 1 - Essential Tests**: Fast feedback (< 5 minutes) - runs on every commit
- **Tier 2 - Standard Tests**: Comprehensive validation (< 30 minutes) - runs on every PR
- **Tier 3 - Comprehensive Tests**: Full ethereum/tests compliance (< 3 hours) - runs nightly

## Available Tags

All tags are defined in: `src/test/scala/com/chipprbots/ethereum/testing/Tags.scala`

See the Tags.scala file for complete documentation of all available tags.

## Quick Reference

### Directory-to-Tag Mapping

| Directory | Primary Tags |
|-----------|--------------|
| `src/test/scala/.../db/` | `UnitTest, DatabaseTest` |
| `src/test/scala/.../vm/` | `UnitTest, VMTest` |
| `crypto/src/test/` | `UnitTest, CryptoTest` |
| `rlp/src/test/` | `UnitTest, RLPTest` |
| `src/it/scala/.../ethtest/` | `IntegrationTest, EthereumTest, SlowTest` |
| `src/it/scala/.../sync/` | `IntegrationTest, SyncTest, SlowTest` |

## Tagging Status

### Completed ✅
- Database tests (18 files)
- Crypto tests (9 files)
- RLP tests (1 file)  
- Bytes tests (2 files)
- Integration tests (15 files)
- VM core tests (7 files)
- MPT tests (2 files)
- Consensus tests (1 file)

**Total: 55+ files tagged**

## References

- [TEST-001](../adr/testing/TEST-001-ethereum-tests-adapter.md)
- [TEST-002](../adr/testing/TEST-002-test-suite-strategy-and-kpis.md)
- [Tags Source](../../src/test/scala/com/chipprbots/ethereum/testing/Tags.scala)
