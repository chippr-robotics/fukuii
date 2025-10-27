# Monix to Cats Effect 3 IO Migration Scripts

Automated scripts to assist with the Monix → CE3 IO migration.

## Overview

These scripts automate portions of the migration process outlined in `docs/MONIX_TO_IO_ACTION_PLAN.md`. They handle simple, mechanical transformations while identifying areas that require manual review.

## Scripts

### 01-analyze-monix-usage.sh
**Purpose**: Analyze current Monix usage across the codebase.

**Usage**:
```bash
./scripts/migration/01-analyze-monix-usage.sh
```

**Output**: Statistics on Task, Observable, and Scheduler usage by module.

### 02-add-fs2-imports.sh
**Purpose**: Identify files needing fs2 imports.

**Usage**:
```bash
./scripts/migration/02-add-fs2-imports.sh
```

**Output**: List of files that will need fs2.Stream imports.

### 03-replace-task-with-io.sh
**Purpose**: Automated Task → IO replacements for simple patterns.

**Usage**:
```bash
./scripts/migration/03-replace-task-with-io.sh <module>
```

**Example**:
```bash
./scripts/migration/03-replace-task-with-io.sh rlp
```

**What it does**:
- Replaces `import monix.eval.Task` with `import cats.effect.IO`
- Replaces `Task[A]` with `IO[A]` in signatures
- Replaces `Task.pure` → `IO.pure`
- Replaces `Task.eval` → `IO.apply`
- Creates backup in `/tmp`

**Important**: Always review changes with `git diff` before committing!

### 04-replace-observable-with-stream.sh
**Purpose**: Analyze Observable usage and suggest fs2.Stream patterns.

**Usage**:
```bash
./scripts/migration/04-replace-observable-with-stream.sh <file>
```

**Example**:
```bash
./scripts/migration/04-replace-observable-with-stream.sh src/main/scala/com/chipprbots/ethereum/db/dataSource/RocksDbDataSource.scala
```

**What it does**:
- Identifies Observable patterns in the file
- Suggests equivalent fs2.Stream operations
- Provides line numbers for manual review

**Note**: Observable → Stream migration requires manual work due to semantic differences.

### 05-replace-scheduler-with-runtime.sh
**Purpose**: Replace Scheduler with IORuntime.

**Usage**:
```bash
./scripts/migration/05-replace-scheduler-with-runtime.sh <module|file>
```

**Example**:
```bash
./scripts/migration/05-replace-scheduler-with-runtime.sh src/test/scala/com/chipprbots/ethereum/SpecBase.scala
```

**What it does**:
- Replaces `Scheduler` imports with `IORuntime`
- Updates implicit parameters
- Replaces `Scheduler.global` → `IORuntime.global`
- Creates backup in `/tmp`

**Important**: Method calls like `.runToFuture` need manual updates!

## Migration Workflow

### Phase 0: Pre-Migration Setup
```bash
# 1. Analyze current usage
./scripts/migration/01-analyze-monix-usage.sh > migration-analysis.txt

# 2. Identify fs2 import needs
./scripts/migration/02-add-fs2-imports.sh
```

### Phase 1: Foundation Modules (rlp, crypto)
```bash
# 3. Migrate rlp module
./scripts/migration/03-replace-task-with-io.sh rlp
git diff rlp/
# Review, test, commit

# 4. Migrate crypto module
./scripts/migration/03-replace-task-with-io.sh crypto
git diff crypto/
# Review, test, commit
```

### Phase 2+: Complex Migrations
```bash
# 5. For Observable files, get suggestions
./scripts/migration/04-replace-observable-with-stream.sh src/main/scala/.../RocksDbDataSource.scala
# Apply changes manually based on suggestions

# 6. Update Scheduler usage
./scripts/migration/05-replace-scheduler-with-runtime.sh src/test/scala/com/chipprbots/ethereum/SpecBase.scala
# Review and update method calls manually
```

## Safety Features

All scripts that modify files:
- ✅ Create backups in `/tmp` before making changes
- ✅ Report what they're doing
- ✅ Suggest review commands

## Testing

After running any migration script:

1. **Review changes**:
   ```bash
   git diff
   ```

2. **Run tests**:
   ```bash
   sbt <module>/test
   ```

3. **Verify compilation**:
   ```bash
   sbt <module>/compile
   ```

## Limitations

These scripts handle **simple, mechanical transformations** only:
- ✅ Import statement changes
- ✅ Type signature updates
- ✅ Simple method call replacements

They **DO NOT** handle:
- ❌ Complex control flow changes
- ❌ Observable → Stream semantics (requires manual work)
- ❌ Actor integration patterns
- ❌ Performance optimization

Always review changes and test thoroughly!

## See Also

- [MONIX_TO_IO_ACTION_PLAN.md](../../docs/MONIX_TO_IO_ACTION_PLAN.md) - Complete migration plan
- [MONIX_TO_IO_MIGRATION_PLAN.md](../../docs/MONIX_TO_IO_MIGRATION_PLAN.md) - Technical patterns
- [MONIX_MIGRATION_PUNCH_LIST.md](../../docs/MONIX_MIGRATION_PUNCH_LIST.md) - Task checklist

## Support

For complex migrations or questions, refer to the comprehensive documentation in the `docs/` directory.
