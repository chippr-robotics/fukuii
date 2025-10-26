# External VM Features - HIBERNATED

⚠️ **Status: HIBERNATED / EXPERIMENTAL**

## Overview

The External VM features in this package are currently in **hibernation**. These components are not core to fukuii's functioning and are not production-ready.

## Why Hibernated?

- **Experimental**: These features were added as experimental functionality
- **Not Core**: External VM support is not required for fukuii's primary blockchain operations
- **Testing Incomplete**: The testing infrastructure for these features needs further development
- **Maintenance**: The upstream mantis-extvm-pb repository is archived (last updated Sept 2021)

## Current Status

- ✅ **Internal VM**: Fully supported and tested (default mode)
- ⚠️ **External VM**: Hibernated, not recommended for production use
- 🔒 **Tests**: VMServerSpec tests are marked as `@Ignore` to prevent blocking CI

## Configuration

The default configuration uses the internal VM (recommended):

```hocon
vm {
  mode = "internal"  # Default and recommended setting
}
```

To use external VM (not recommended):

```hocon
vm {
  mode = "external"
  external {
    vm-type = "mantis"
    host = "127.0.0.1"
    port = 8888
  }
}
```

## Components

### Hibernated Components
- `ExtVMInterface.scala` - External VM interface implementation
- `VMServer.scala` - VM server for external VM communication
- `VMClient.scala` - Client for connecting to external VMs
- `MessageHandler.scala` - Protocol message handling
- `ApiVersionProvider.scala` - Version management
- Supporting utilities and protobuf definitions

### Tests
- `VMServerSpec.scala` - Unit tests (currently ignored)

## Future Work

Before bringing these features out of hibernation:

1. Complete comprehensive testing
2. Update to modern protobuf practices
3. Evaluate external VM requirements
4. Document integration patterns
5. Ensure production-ready error handling
6. Performance benchmarking

## Migration Notes

Users should use `vm.mode = "internal"` (the default). No migration is needed as this is the standard configuration.

## Contact

For questions about external VM features, please open an issue in the fukuii repository.

---

*Last Updated: October 2025*
*Hibernation Date: October 2025*
