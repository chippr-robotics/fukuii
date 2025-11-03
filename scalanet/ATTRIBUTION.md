# Scalanet Attribution and License

## Original Source

This code is derived from **scalanet**, a Scala networking library developed by Input Output Hong Kong (IOHK).

- **Original Repository**: https://github.com/input-output-hk/scalanet
- **Version**: 0.8.0 (commit fce50a1)
- **Date Vendored**: October 27, 2025
- **Vendored By**: Chippr Robotics LLC for the Fukuii Ethereum client project

## Reason for Vendoring

Scalanet is vendored into the Fukuii project to:
1. Support Scala 3 migration while maintaining DevP2P protocol compatibility
2. Ensure long-term maintainability as part of the Fukuii project
3. Eliminate external dependency on unmaintained library

## License

Scalanet is licensed under the **Apache License, Version 2.0**.

```
Copyright 2019 Input Output (HK) Ltd.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## Attribution

**Original Work**: Input Output (HK) Ltd. (IOHK)  
**Copyright**: 2019 Input Output (HK) Ltd.

We acknowledge and appreciate the original development work by IOHK. This code is used in compliance with the Apache 2.0 license, which permits redistribution and modification.

## Modifications

This vendored version includes modifications for:
- **Package rebranding**: All package names changed from `io.iohk.scalanet` to `com.chipprbots.scalanet` to align with Fukuii's rebranding from IOHK/Mantis to Chippr Robotics/Fukuii
- Scala 3 compatibility (planned)
- Integration with Fukuii codebase
- Bug fixes and improvements

All modifications are also licensed under Apache 2.0 and copyright by Chippr Robotics LLC.

## Upstream Acknowledgment

The original scalanet library was developed by Input Output (HK) Ltd. as part of their blockchain infrastructure work. We are grateful for their contribution to the open-source community.

## Structure

This directory contains the following scalanet components:

- **discovery/** - DevP2P v4 discovery protocol implementation
- **src/** - Core networking abstractions and peer group management

## Further Information

For the original project documentation and history, see:
- Original repository: https://github.com/input-output-hk/scalanet
- License: https://github.com/input-output-hk/scalanet/blob/develop/LICENSE
- Original README: https://github.com/input-output-hk/scalanet/blob/develop/README.md

---

**Maintained by**: Chippr Robotics LLC as part of the Fukuii project  
**Contact**: https://github.com/chippr-robotics/fukuii
