# Validation Documentation

This directory contains documentation related to validating Fukuii's compatibility and correctness.

## Gorgoroth Network Compatibility Testing

The Gorgoroth Network (see `ops/gorgoroth/` in repository) is a private test network for validating Fukuii compatibility with other Ethereum Classic clients.

**Main Documentation**: [GORGOROTH_COMPATIBILITY_TESTING.md](../testing/GORGOROTH_COMPATIBILITY_TESTING.md)

### What's Validated

- Network communication and peer connectivity
- Block propagation and consensus
- Mining compatibility across clients
- Fast sync capabilities
- Snap sync capabilities

### Test Configurations

- Fukuii-only networks (3 and 6 nodes)
- Fukuii + Core-Geth mixed network
- Fukuii + Hyperledger Besu mixed network
- Full multi-client network (Fukuii + Core-Geth + Besu)

### For Community Testers

See the [Compatibility Testing Guide](../testing/GORGOROTH_COMPATIBILITY_TESTING.md) for detailed instructions on running validation tests.

### Current Status

See `ops/gorgoroth/VALIDATION_STATUS.md` (internal) for the current progress and roadmap, or [GORGOROTH_VALIDATION_STATUS.md](GORGOROTH_VALIDATION_STATUS.md) in this docs section.

## Related Documentation

- [Testing Documentation](../testing/README.md)
- [Operations Runbooks](../runbooks/README.md)
- [Architecture Documentation](../architecture/architecture-overview.md)
