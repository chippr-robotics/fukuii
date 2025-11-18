# Vendored Modules Integration Plan

## Executive Summary

This document outlines the effort required to fully incorporate Fukuii's vendored modules (bytes, crypto, rlp, scalanet) into the main application codebase, eliminating them as independent SBT subprojects.

**Recommendation**: Proceed with **Option 1** (Move to src/main/scala with namespace preservation) - **Low effort, low risk, 2-3 hours**

## Current State

Fukuii has 4 vendored modules currently maintained as separate SBT subprojects:

| Module | Files | Purpose | Dependencies |
|--------|-------|---------|--------------|
| **bytes** | 3 | Hex encoding, ByteString utilities | None |
| **crypto** | ~30 | ECDSA, ECIES, zkSNARK crypto | bytes |
| **rlp** | 7 | RLP encoding/decoding | bytes |
| **scalanet** | ~22 | Low-level networking, TCP, Kademlia DHT | None (on other vendored) |

**Total**: ~102 Scala source files

### Current Module Dependencies

```
node (main application)
  ├── bytes (foundation utilities)
  ├── crypto → bytes
  ├── rlp → bytes
  ├── scalanet (networking layer)
  └── scalanetDiscovery → scalanet
```

### Why These Were Vendored

1. **Scala 3 compatibility** - Original libraries didn't support Scala 3
2. **Customization needs** - Required modifications for Fukuii's specific use cases
3. **Maintenance control** - No longer actively maintained upstream
4. **Dependency stability** - Avoid external dependency breaking changes

## Integration Options

### Option 1: Move to src/main/scala with Namespace Preservation (RECOMMENDED)

**Strategy**: Move vendored code into src/main/scala while keeping logical separation via subpackages to avoid conflicts.

**Structure**:
```
src/main/scala/com/chipprbots/ethereum/
├── utils/
│   └── bytes/                    # From bytes module
│       ├── Hex.scala
│       ├── ByteStringUtils.scala
│       └── ByteUtils.scala
├── crypto/
│   ├── vendored/                 # From crypto module
│   │   ├── ECDSASignature.scala
│   │   ├── ECIESCoder.scala
│   │   ├── SymmetricCipher.scala
│   │   └── zksnark/
│   │       ├── BN128.scala
│   │       ├── PairingCheck.scala
│   │       └── ...
│   └── [existing crypto code]    # App-specific crypto logic
├── rlp/
│   ├── vendored/                 # From rlp module
│   │   ├── RLP.scala
│   │   ├── RLPDerivation.scala
│   │   ├── RLPImplicits.scala
│   │   └── ...
│   └── [existing rlp code]       # App-specific RLP logic
└── network/
    ├── scalanet/                 # From scalanet module
    │   ├── [scalanet networking code]
    │   └── discovery/            # From scalanet/discovery
    │       └── [discovery code]
    └── [existing network code]   # App-specific network logic
```

**Migration Steps**:

1. **Move bytes** (simplest, no conflicts):
   ```bash
   mkdir -p src/main/scala/com/chipprbots/ethereum/utils/bytes
   cp -r bytes/src/main/scala/com/chipprbots/ethereum/utils/* \
         src/main/scala/com/chipprbots/ethereum/utils/bytes/
   cp -r bytes/src/test/scala/* src/test/scala/
   ```

2. **Move crypto**:
   ```bash
   mkdir -p src/main/scala/com/chipprbots/ethereum/crypto/vendored
   cp -r crypto/src/main/scala/com/chipprbots/ethereum/crypto/* \
         src/main/scala/com/chipprbots/ethereum/crypto/vendored/
   cp -r crypto/src/test/scala/* src/test/scala/
   ```

3. **Move rlp**:
   ```bash
   mkdir -p src/main/scala/com/chipprbots/ethereum/rlp/vendored
   cp -r rlp/src/main/scala/com/chipprbots/ethereum/rlp/* \
         src/main/scala/com/chipprbots/ethereum/rlp/vendored/
   cp -r rlp/src/test/scala/* src/test/scala/
   ```

4. **Move scalanet**:
   ```bash
   mkdir -p src/main/scala/com/chipprbots/ethereum/network/scalanet
   cp -r scalanet/src/* \
         src/main/scala/com/chipprbots/ethereum/network/scalanet/
   cp -r scalanet/discovery/src/* \
         src/main/scala/com/chipprbots/ethereum/network/scalanet/discovery/
   # Handle tests similarly
   ```

5. **Update build.sbt**:
   - Remove subproject definitions for bytes, crypto, rlp, scalanet
   - Remove `.dependsOn()` clauses from node project
   - Keep single main project

6. **Update imports**:
   - Find/replace import statements throughout codebase
   - Update package declarations in moved files

7. **Verify**:
   - Run `sbt compile` - should succeed
   - Run `sbt test` - all tests should pass
   - Verify no compilation errors

**Effort**: 2-3 hours  
**Risk**: Low (code uses same package structure already)  
**Complexity**: Low  

**Pros**:
- ✅ Maintains logical separation, easy to understand
- ✅ Avoids conflicts with existing code
- ✅ Clear migration path
- ✅ Can be done incrementally (one module at a time)
- ✅ Easy to revert if issues arise

**Cons**:
- ⚠️ Slightly deeper package nesting
- ⚠️ Import statements need updating

### Option 2: Full Integration with Merge (NOT RECOMMENDED)

**Strategy**: Merge vendored code directly into existing packages, resolving conflicts and deduplicating code.

**Key Challenges**:

1. **Crypto package conflict**:
   - `crypto/src/.../crypto/` exists (vendored)
   - `src/main/scala/.../crypto/` exists (app code)
   - Need to analyze and merge functionality

2. **RLP package conflict**:
   - Similar conflict situation
   - May have duplicate functionality

**Migration Steps**:

1. Analyze conflicts file-by-file
2. Merge or rename conflicting files
3. Identify and remove duplicate code
4. Update all imports across entire codebase
5. Extensive testing required

**Effort**: 8-16 hours  
**Risk**: Medium-High (conflicts, potential for introducing bugs)  
**Complexity**: High  

**Pros**:
- ✅ Cleaner final structure
- ✅ Potential code deduplication

**Cons**:
- ❌ High risk of breaking changes
- ❌ Requires deep understanding of both code paths
- ❌ Difficult to revert
- ❌ Extensive testing needed
- ❌ May uncover hidden dependencies

## Recommendation

**Proceed with Option 1** for the following reasons:

1. **Low Risk**: Maintains existing code separation, minimal chance of breakage
2. **Quick Implementation**: Can be completed in 2-3 hours
3. **Incremental**: Can migrate one module at a time
4. **Reversible**: Easy to undo if issues arise
5. **Clear Intent**: `/vendored/` subdirectories clearly indicate origin

## Benefits of Full Integration

Regardless of the chosen approach, integrating vendored modules provides:

### Build Simplification
- **Single SBT project** instead of multi-module build
- **Faster compilation** - no cross-project dependencies
- **Simpler CI/CD** - one build target
- **Reduced build.sbt complexity** - ~100 fewer lines

### Development Experience
- **Better IDE support** - single module easier to navigate
- **Faster iteration** - single compile/test cycle
- **Easier refactoring** - no artificial module boundaries
- **Simplified debugging** - all code in one place

### Maintenance
- **No subproject management** - fewer moving parts
- **No version alignment** between modules
- **Easier dependency management** - one dependency tree
- **Clearer ownership** - all code is "app code"

## Implementation Timeline

### Phase 1: bytes module (30 minutes)
- Simplest module, no conflicts
- Move to `utils/bytes/`
- Update imports
- Test

### Phase 2: crypto module (45 minutes)
- Move to `crypto/vendored/`
- Update imports
- Handle zksnark subpackage
- Test

### Phase 3: rlp module (30 minutes)
- Move to `rlp/vendored/`
- Update imports
- Test

### Phase 4: scalanet modules (45 minutes)
- Move scalanet to `network/scalanet/`
- Move discovery to `network/scalanet/discovery/`
- Update imports
- Test

### Phase 5: Cleanup (15 minutes)
- Remove empty directories
- Remove subproject definitions from build.sbt
- Update documentation
- Final test run

**Total Estimated Time**: 2-3 hours

## Risks and Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| Import statement errors | Medium | Systematic find/replace, compiler will catch |
| Test failures | Low | Tests move with code, should work unchanged |
| Package conflicts | Low | Using `/vendored/` namespaces avoids conflicts |
| Build configuration errors | Low | Remove subprojects, simplify build.sbt |
| Forgotten dependencies | Medium | Compiler will identify missing imports |

## Post-Integration Maintenance

After integration, these modules are no longer "vendored" - they're part of Fukuii:

1. **No separate maintenance** - code is just part of the app
2. **Direct modifications** - change as needed for features
3. **Refactoring freedom** - merge with existing code over time
4. **Clear ownership** - maintained by Fukuii team

## Future Considerations

After initial integration, consider:

1. **Gradual merge** of `crypto/vendored/` with `crypto/` over time
2. **Gradual merge** of `rlp/vendored/` with `rlp/` over time
3. **Code deduplication** if overlapping functionality found
4. **Package reorganization** as understanding deepens

These can be done incrementally without urgent timeline.

## Architecture Diagrams

See [Architecture Diagrams](../architecture/ARCHITECTURE_DIAGRAMS.md) for:
- System Context (C4 Level 1)
- Container Diagram (C4 Level 2)
- Component Diagram - Current State (C4 Level 3)
- Component Diagram - Proposed State (C4 Level 3)

## Questions?

For questions about this integration plan:
1. Check existing vendored code structure
2. Review build.sbt subproject definitions
3. Refer to Scala/SBT documentation for module structure
4. Consult with team before starting major changes
