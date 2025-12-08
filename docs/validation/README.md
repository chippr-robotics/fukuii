# Validation Documentation

This directory contains documentation related to validating Fukuii's compatibility and correctness.

## Gorgoroth Network Compatibility Testing

The [Gorgoroth Network](../../ops/gorgoroth/) is a private test network for validating Fukuii compatibility with other Ethereum Classic clients.

**Main Documentation**: [GORGOROTH_COMPATIBILITY.md](GORGOROTH_COMPATIBILITY.md)

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

See [Validation Status](../../ops/gorgoroth/VALIDATION_STATUS.md) for the current progress and roadmap.

## Related Documentation

- [Testing Documentation](../testing/README.md)
- [Operations Runbooks](../runbooks/README.md)
- [Architecture Documentation](../architecture/architecture-overview.md)
