<div align="center">
  <img src="docs/images/fukuii-logo-cute.png" alt="Fukuii Logo" width="400"/>
</div>

# Fukuii Ethereum Client

Fukuii is a continuation and re‑branding of the Ethereum Classic client previously known as Mantis. Mantis was developed by Input Output (HK) as a Scala client for the Ethereum Classic (ETC) network. This project is an independent fork maintained by Chippr Robotics LLC with the aim of modernising the codebase and ensuring long‑term support.

Fukuii retains the robust architecture and ETC compatibility of Mantis while introducing new features, updated dependencies and a streamlined build. This fork has been renamed throughout the code and documentation:

Executable scripts are renamed from mantis to fukuii.

Java/Scala packages under io.iohk have been moved to com.chipprbots.

Environment variables and configuration keys prefixed with mantis have been changed to fukuii.

Important Notes

Licence: This project continues to be distributed under the Apache 2.0 licence. A copy of the licence is included in the LICENSE file. The original NOTICE file from IOHK is preserved as required by the licence, and Chippr Robotics LLC has added its own attribution.

Origin: Fukuii is derived from the Mantis
 client. Mantis is a trademark of IOHK; we use the name here only to describe the origin of this fork. 

Chordoes Fukuii is a worm which controls a zombie mantis.

## CI/CD and Project Hygiene

This project uses GitHub Actions for continuous integration and delivery:

- ✅ **Automated Testing**: All tests run on every push and PR
- 🔍 **Code Quality**: Automated formatting and style checks
- 🐳 **Docker Builds**: Automatic container image builds
- 🚀 **Releases**: Automated release creation with milestone tracking
- 📊 **Dependency Checks**: Weekly dependency monitoring

**Quick Links:**
- [Workflow Documentation](.github/workflows/README.md)
- [Quick Start Guide](.github/QUICKSTART.md)
- [Branch Protection Setup](.github/BRANCH_PROTECTION.md)

**For Contributors:** Before submitting a PR, run `sbt pp` to check formatting, style, and tests locally.

Getting started
Prerequisites

To build Fukuii from source you will need:

JDK 8 or higher.

sbt
 (Scala build tool).

Python (for certain auxiliary scripts).

Building the client

Update git submodules:

git submodule update --init --recursive


Build the distribution using sbt:

sbt dist


After the build completes, a distribution zip archive will be placed under target/universal/. Unzip it to run the client.

Running the client

The distribution’s bin/ directory contains a launcher script named fukuii. To join the ETC network:

./bin/fukuii etc


The launcher accepts the same network names that Mantis did (etc, eth, mordor, testnet-internal). See the configuration files under src/universal/conf for more details.

Command line interface (CLI)

Fukuii’s CLI tool provides utilities for key generation and other functions. The entry point is:

./bin/fukuii cli [options]


For example, to generate a new private key:

./bin/fukuii cli generate-private-key

Configuration and Environment

Many configuration properties begin with the prefix fukuii instead of mantis. For example, the RPC settings are controlled by keys like fukuii.network.rpc.http.mode. Similarly, the environment variable FUKUII_DEV=true enables developer‑friendly settings during the build.

Migrating from Mantis

If you have an existing deployment of Mantis, follow these steps to migrate:

Update your configuration files by replacing mantis with fukuii in key names and environment variables.

Rename any directories or files under ~/.mantis to ~/.fukuii. The layout of the data directory remains the same.

Review custom scripts or automation to ensure they invoke fukuii instead of mantis.

Contributing

We welcome contributions! Please submit issues and pull requests via our GitHub repository. When modifying code derived from Mantis, include a notice in the header of changed files stating that you changed the file and add your own copyright line.

Contact

For questions or support, reach out to Chippr Robotics LLC via our GitHub repository.