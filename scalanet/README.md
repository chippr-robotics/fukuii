# Scalanet - Vendored Networking Library

This directory contains a vendored copy of the **scalanet** networking library, originally developed by Input Output Hong Kong (IOHK).

## What is Scalanet?

Scalanet is a Scala networking library that provides:
- **DevP2P Discovery Protocol**: Ethereum's peer discovery protocol (v4)
- **UDP-based Peer Groups**: For decentralized peer-to-peer communication
- **Kademlia DHT**: Distributed hash table for node discovery
- **ENR Support**: Ethereum Node Records for node information exchange

## Why Vendored?

Scalanet is vendored into the Fukuii project for the following reasons:

1. **Scala 3 Migration**: The original scalanet library does not support Scala 3, and vendoring allows us to migrate it as part of Fukuii's Scala 3 migration
2. **Maintenance**: The original library appears unmaintained, and Fukuii requires ongoing support
3. **Integration**: Tight integration with Fukuii's architecture and requirements
4. **Long-term Stability**: Ensures the critical networking functionality remains available

## Structure

```
scalanet/
├── ATTRIBUTION.md           # Full attribution and license information
├── README.md                # This file
├── discovery/               # DevP2P discovery protocol implementation
│   ├── src/                 # Discovery protocol source code
│   ├── it/                  # Integration tests
│   └── ut/                  # Unit tests
└── src/                     # Core scalanet library
    └── com/chipprbots/scalanet/
        ├── crypto/          # Cryptographic utilities
        └── peergroup/       # Peer group abstractions
```

## License

Scalanet is licensed under the **Apache License, Version 2.0**.

Original work: Copyright 2019 Input Output (HK) Ltd.  
Vendored and maintained by: Chippr Robotics LLC

See `ATTRIBUTION.md` for full license text and attribution details.

## Usage in Fukuii

Scalanet is used by Fukuii's peer discovery subsystem:

- `src/main/scala/com/chipprbots/ethereum/network/discovery/` - Uses scalanet's discovery protocol
- Key components:
  - `PeerDiscoveryManager` - Manages peer discovery lifecycle
  - `DiscoveryServiceBuilder` - Creates discovery service instances  
  - `Secp256k1SigAlg` - Cryptographic signature adapter
  - `RLPCodecs` - Protocol message encoding/decoding

## Modifications

This vendored version includes modifications for:
- **Package rebranding**: Changed from `io.iohk.scalanet` to `com.chipprbots.scalanet` to align with Fukuii's rebranding
- Scala 3 compatibility (planned)
- Integration with Fukuii's codebase
- Bug fixes and improvements
- Dependency updates

All modifications are documented in commit history and licensed under Apache 2.0.

## Original Project

- **Original Repository**: https://github.com/input-output-hk/scalanet
- **Version**: 0.8.0 (commit fce50a1)
- **Date Vendored**: October 27, 2025

For the original project documentation, see the upstream repository.

## Maintenance

This vendored copy is maintained by the Fukuii development team at Chippr Robotics LLC.

Issues and improvements should be reported in the main Fukuii repository:
https://github.com/chippr-robotics/fukuii/issues

---

**For detailed attribution and license information, see `ATTRIBUTION.md`**
