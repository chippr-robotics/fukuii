# Quick Guide: Addressing Compilation Warnings

This guide helps you quickly address the 121 compilation warnings documented in the codebase.

## Quick Wins (Automated)

### 1. Remove Unused Imports (65+ instances)

**Using Scalafix:**
```bash
# Install scalafix plugin (if not already in project)
# Add to project/plugins.sbt:
# addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.11.1")

# Run automated cleanup
sbt "scalafixAll RemoveUnused"
```

**Manual cleanup example:**
```scala
// Before
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
import com.chipprbots.ethereum.db.storage.ReceiptStorage.BlockHash
import scala.concurrent.Future

class MyClass {
  // BlockHash and Future never used
}

// After
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError

class MyClass {
  // Only what's needed
}
```

### 2. Fix Format Issues

```bash
# Format all files
export PATH="$PATH:/home/runner/.local/share/coursier/bin"
scalafmt src/

# Or using sbt
sbt scalafmtAll
```

## Medium Effort Fixes

### 3. Convert Unused Vars to Vals

**Example from RocksDbDataSource.scala:**
```scala
// Before
private var nameSpaces: Seq[Namespace],  // Never reassigned!

// After
private val nameSpaces: Seq[Namespace],
```

**How to find them:**
```bash
# Search for vars that might be vals
grep -r "private var" src/ | grep -v "reassigned"
```

### 4. Remove Dead Code

**Example from BN128.scala:**
```scala
// Before
private def isGroupElement(p: Point[Fp2]): Boolean = {
  // Complex logic...
}
// This method is never called!

// After
// Remove the entire method
```

**How to verify it's safe:**
1. Search for method calls: `grep -r "isGroupElement" src/`
2. If only found in definition, it's safe to remove
3. Run tests after removal: `sbt test`

## Higher Effort Fixes

### 5. Fix Unused Parameters

**When to remove:**
```scala
// Clear unused parameter
def processBlock(block: Block, unusedParam: Int): Unit = {
  doSomething(block)
  // unusedParam never referenced
}

// Fix: Remove it
def processBlock(block: Block): Unit = {
  doSomething(block)
}
```

**When to keep (mark as intentionally unused):**
```scala
// API compatibility or override requirement
abstract class Base {
  def process(data: Data, metadata: Metadata): Unit
}

class Impl extends Base {
  // metadata required by interface but not used in this impl
  def process(data: Data, _metadata: Metadata): Unit = {
    doSomething(data)
  }
}
```

### 6. Review Implicit Parameters

**Example from ConsoleUIUpdater.scala:**
```scala
// Before
def update()(implicit system: ActorSystem): Unit = {
  // system never used
  doUpdate()
}

// After - either use it or remove it
def update(): Unit = {
  doUpdate()
}
```

## Prevention (Build Configuration)

### 7. Add Compiler Warnings

**Add to build.sbt:**
```scala
scalacOptions ++= Seq(
  "-Wunused:imports",      // Warn on unused imports
  "-Wunused:privates",     // Warn on unused private members
  "-Wunused:locals",       // Warn on unused local definitions
  "-Wunused:explicits",    // Warn on unused explicit parameters
  "-Wunused:implicits",    // Warn on unused implicit parameters
  "-Wunused:params",       // Warn on unused parameters
  "-Wunused:patvars"       // Warn on unused pattern variables
)
```

### 8. Enable Scalafix in CI

**Add to .scalafix.conf:**
```conf
rules = [
  RemoveUnused,
  OrganizeImports
]

RemoveUnused.imports = true
RemoveUnused.privates = true
RemoveUnused.locals = true
```

**Add to CI workflow (.github/workflows/ci.yml):**
```yaml
- name: Check for unused code
  run: sbt "scalafixAll --check RemoveUnused"
```

## Testing Your Changes

After fixing warnings:

```bash
# 1. Format check
sbt scalafmtCheckAll

# 2. Compile check
sbt compile

# 3. Run tests
sbt test

# 4. Run integration tests
sbt it:test

# 5. Check for new warnings
sbt compile 2>&1 | grep -i "warn"
```

## Common Patterns

### Pattern 1: JSON RPC Imports
Many JSON RPC files import JsonSerializers but don't use it.

**Quick fix:**
```bash
# Find all instances
grep -l "import.*JsonSerializers" src/main/scala/com/chipprbots/ethereum/jsonrpc/*.scala

# Check each file and remove unused imports
```

### Pattern 2: Test Future Imports
Many test files import Future but use sync test patterns.

**Quick fix:**
```bash
# Find test files with unused Future
grep -l "import scala.concurrent.Future" src/test/ | while read f; do
  if ! grep -q "Future\[" "$f"; then
    echo "Unused Future import in: $f"
  fi
done
```

### Pattern 3: Duplicate Imports
Same import appears multiple times in test files.

**Example:**
```scala
// Before (in 10+ test files)
import com.chipprbots.ethereum.consensus.validators.BlockHeaderError
// But using different error type

// After (remove if unused)
// Just remove the line
```

## Priority Order

Work through warnings in this order:

1. **Formatting issues** (build blocking) ✅ DONE
2. **Unused imports** (easy, safe, automated)
3. **Unused privates** (easy, check for usage first)
4. **Mutable vars → vals** (medium, test after change)
5. **Unused parameters** (harder, may need API discussion)
6. **Enable prevention** (add compiler flags, CI checks)

## Resources

- **Full Analysis:** See `SILENT_ERRORS_REPORT.md`
- **Scalafix Docs:** https://scalacenter.github.io/scalafix/
- **Scalafmt Docs:** https://scalameta.org/scalafmt/

## Questions?

If unsure about a warning:
1. Search codebase for usage
2. Check git history for context
3. Ask team if it's needed for compatibility
4. When in doubt, mark with underscore prefix instead of removing
