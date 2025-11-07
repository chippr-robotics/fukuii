# Historical Documentation and Scripts

This directory contains historical documentation and one-time migration scripts that are preserved for reference but are no longer actively used.

## Contents

### `rebrand.sh`
One-time rebranding script used to migrate the codebase from "Mantis" (IOHK) to "Fukuii" (Chippr Robotics).

**Status**: Migration completed. This script is preserved for historical reference.

**What it did**:
- Renamed packages from `io.iohk` to `com.chipprbots`
- Renamed directories and configuration files
- Updated string references throughout the codebase
- Created `docker/fukuii/` from `docker/mantis/`
- Created `ets/config/fukuii/` from `ets/config/mantis/`

**Note**: If you need to understand what changed during the rebrand, refer to:
1. This script
2. Git history around the rebrand date
3. [docs/adr/001-scala-3-migration.md](../adr/001-scala-3-migration.md) - Contains context about the overall migration

## Should I run these scripts?

**No.** These scripts are for historical reference only. The migrations they performed are already complete in the current codebase.

## Related Documentation

- [docs/MIGRATION_HISTORY.md](../MIGRATION_HISTORY.md) - Detailed history of the Scala 3 migration
- [docs/adr/001-scala-3-migration.md](../adr/001-scala-3-migration.md) - Architecture decision record
- [CHANGELOG.md](../../CHANGELOG.md) - Version history and changes
