# Test Documentation Guide

This directory contains the consolidated test documentation for the Fukuii project.

---

## Active Documents (Use These)

### 1. TEST_AUDIT_SUMMARY.md
**Primary document for test audit information**

**Contents:**
- Quick stats and metrics
- Test organization by functional system
- Test execution commands (by tier and system)
- Coverage report commands
- Known issues and remediation plans
- Next actions in priority order
- Phase 2 completion summary

**When to use:**
- Starting point for understanding test organization
- Planning test execution strategies
- Identifying and fixing test issues
- Understanding test quality scores

### 2. TEST_COVERAGE_INTEGRATION.md
**Technical reference for coverage reporting and CI/CD**

**Contents:**
- Quick command reference for coverage generation
- Tag-to-system mapping
- Coverage report structure and scripts
- CI/CD integration examples (GitHub Actions)
- Isolated logging configuration (logback-test.xml)
- Coverage goals by system
- Troubleshooting guide

**When to use:**
- Generating coverage reports
- Setting up CI/CD pipelines
- Configuring isolated logging
- Debugging coverage or test execution issues

### 3. TEST_CATEGORIZATION.csv
**Detailed file-by-file mapping**

**Contents:**
- Test file path
- Module assignment
- Test type (Unit, Integration, Benchmark, etc.)
- Functional system
- Current tags
- Recommended tags
- Implementation notes

**When to use:**
- Looking up specific test files
- Tracking tagging progress
- Understanding test categorization
- Planning test modifications

---

## Archived Documents (Historical Reference)

Located in `docs/archive/`:

- **PHASE2_TEST_ANALYSIS.md** - Detailed session-by-session analysis
- **TEST_INVENTORY.md** - Original comprehensive inventory
- **TEST_TAGGING_ACTION_PLAN.md** - Original 4-phase implementation plan
- **SUMMARY.md** - Original task completion summary

These documents contain historical context and detailed analysis from Phase 2 work but have been superseded by the consolidated active documents.

---

## Quick Start

### I want to... run tests by system
→ See TEST_AUDIT_SUMMARY.md "Quick Test Execution" section

### I want to... generate coverage reports
→ See TEST_COVERAGE_INTEGRATION.md "Quick Commands" section

### I want to... understand test quality
→ See TEST_AUDIT_SUMMARY.md "Test Organization" table

### I want to... fix flaky tests
→ See TEST_AUDIT_SUMMARY.md "Known Issues & Remediation" section

### I want to... set up CI/CD
→ See TEST_COVERAGE_INTEGRATION.md "CI/CD Integration" section

### I want to... look up a specific test file
→ See TEST_CATEGORIZATION.csv

---

## Document Relationship

```
TEST_AUDIT_SUMMARY.md (Executive Summary)
├── What: Test organization, quality, issues
├── Why: Strategic planning and prioritization
└── Next: Points to TEST_COVERAGE_INTEGRATION.md

TEST_COVERAGE_INTEGRATION.md (Technical Guide)
├── How: Commands, scripts, configuration
├── Why: Implementation and automation
└── Next: Points back to TEST_AUDIT_SUMMARY.md

TEST_CATEGORIZATION.csv (Data Reference)
├── What: Detailed file-level data
└── Why: Tracking and lookup
```

---

## Maintenance

### When tagging new tests
1. Add tags to test files
2. Update TEST_CATEGORIZATION.csv
3. Verify with coverage commands

### When adding new test systems
1. Update TEST_AUDIT_SUMMARY.md table
2. Add tag mapping to TEST_COVERAGE_INTEGRATION.md
3. Add SBT command to build.sbt if needed

### When fixing issues
1. Update issue status in TEST_AUDIT_SUMMARY.md
2. Remove from "Known Issues" when resolved
3. Update quality scores if applicable

---

**Last Updated:** 2025-11-18
