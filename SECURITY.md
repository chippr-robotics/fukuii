# Security Policy

## Our Commitment to Security

Chippr Robotics LLC and the Fukuii project are committed to maintaining the highest security standards for our Ethereum Classic client and related blockchain infrastructure. Security is fundamental to building trust in blockchain technology and protecting our users and the broader Ethereum Classic ecosystem.

## Security Principles

We follow these core security principles in all our work:

### 1. Defense in Depth
- Multiple layers of security controls throughout the codebase
- Secure defaults in all configuration options
- Principle of least privilege in system design

### 2. Transparency and Openness
- Open source codebase for community review
- Public disclosure of security vulnerabilities after remediation
- Clear documentation of security features and best practices

### 3. Secure Development Lifecycle
- Mandatory code review for all changes
- Automated security scanning in CI/CD pipeline
- Regular dependency vulnerability assessments
- Static analysis tools (Scapegoat, Scalafix) to catch common vulnerabilities
- Code coverage requirements to ensure thorough testing

### 4. Supply Chain Security
- Signed Docker images with Cosign (keyless, using GitHub OIDC)
- SLSA Level 3 provenance attestations for build artifacts
- Software Bill of Materials (SBOM) in CycloneDX format for all releases
- Immutable digest references for tamper-proof deployments
- Weekly automated dependency vulnerability checks

### 5. Cryptographic Best Practices
- Use of industry-standard cryptographic libraries
- Secure random number generation
- Proper key management and storage
- Support for TLS/SSL with modern cipher suites

## Actively Supported Projects

The following projects are actively maintained and receive security updates:

### Fukuii Ethereum Classic Client
- **Repository**: [chippr-robotics/fukuii](https://github.com/chippr-robotics/fukuii)
- **Description**: Full-node Ethereum Classic (ETC) client written in Scala
- **Status**: Active development and maintenance
- **Blockchain Networks Supported**:
  - Ethereum Classic (ETC) mainnet
  - Mordor testnet
  - Other ETC-compatible networks

### Related Blockchain Infrastructure
- **Docker Images**: Official container images published to `ghcr.io/chippr-robotics/chordodes_fukuii`
- **Build Artifacts**: Distribution packages and assembly JARs for each release
- **Tooling**: CLI utilities for key generation and blockchain operations

## Supported Versions

We provide security updates for the following versions:

| Version | Supported          | Status |
| ------- | ------------------ | ------ |
| 0.1.x   | :white_check_mark: | Current development version |
| < 0.1.0 | :x:                | Pre-release, not supported |

**Note**: As Fukuii is in active development (version 0.1.0), we recommend always using the latest release. Once version 1.0.0 is reached, we will maintain security support for multiple stable versions according to semantic versioning principles.

## Reporting a Vulnerability

We take all security vulnerabilities seriously and appreciate responsible disclosure from security researchers and community members.

### How to Report

**For security vulnerabilities, please DO NOT open a public GitHub issue.**

Instead, please report security vulnerabilities through one of the following methods:

1. **GitHub Security Advisories** (Preferred)
   - Navigate to the [Security tab](https://github.com/chippr-robotics/fukuii/security/advisories) of our repository
   - Click "Report a vulnerability"
   - Provide detailed information about the vulnerability

2. **Email**
   - Send an email to: **security@chippr-robotics.com**
   - Use PGP encryption if possible (key available on request)
   - Include "SECURITY" in the subject line

### What to Include in Your Report

Please provide as much information as possible to help us understand and reproduce the issue:

- **Description**: Clear description of the vulnerability
- **Impact**: Potential impact and severity assessment
- **Affected Components**: Specific modules, functions, or configurations affected
- **Reproduction Steps**: Detailed steps to reproduce the vulnerability
- **Proof of Concept**: Code or commands demonstrating the issue (if applicable)
- **Suggested Fix**: Your recommendations for remediation (optional)
- **Disclosure Timeline**: Your intended disclosure timeline or embargo period

### What to Expect

When you report a security vulnerability, you can expect the following response timeline:

- **Initial Response**: Within 48 hours of receiving your report
- **Validation**: Within 5 business days, we will validate the vulnerability and assess its severity
- **Status Updates**: Regular updates every 7 days on remediation progress
- **Resolution**: Target resolution within 90 days for confirmed vulnerabilities (critical issues prioritized)
- **Public Disclosure**: Coordinated disclosure after patch is available and users have been notified

### Severity Assessment

We use the CVSS (Common Vulnerability Scoring System) to assess severity:

- **Critical** (CVSS 9.0-10.0): Immediate attention, expedited patching
- **High** (CVSS 7.0-8.9): Priority patching within 30 days
- **Medium** (CVSS 4.0-6.9): Patching within 90 days
- **Low** (CVSS 0.1-3.9): Addressed in regular release cycle

### Recognition and Bug Bounty

While we do not currently offer a monetary bug bounty program, we will:
- Publicly acknowledge security researchers who responsibly disclose vulnerabilities (unless you prefer to remain anonymous)
- Include your name in our security acknowledgments
- Credit you in release notes when the fix is published

We are evaluating the establishment of a formal bug bounty program for future implementation.

## Security Best Practices for Users

### Running Fukuii Securely

1. **Use Official Releases**
   - Download from official GitHub releases only
   - Verify Docker image signatures with Cosign
   - Check SBOM for dependency vulnerabilities

2. **Network Security**
   - Run Fukuii behind a firewall
   - Restrict RPC access to trusted networks only
   - Use TLS/SSL for RPC endpoints
   - Never expose private keys or keystore files

3. **Configuration Security**
   - Review and customize security settings in configuration files
   - Use strong passwords for encrypted keystores
   - Enable authentication for RPC endpoints
   - Disable unnecessary features and APIs

4. **System Security**
   - Keep your operating system and dependencies up to date
   - Run Fukuii with minimal system privileges
   - Monitor logs for suspicious activity
   - Implement proper backup procedures for blockchain data and keys

5. **Docker Security**
   - Use official signed images from `ghcr.io/chippr-robotics/chordodes_fukuii`
   - Verify image signatures before deployment
   - Run containers with security-enhanced profiles
   - Keep Docker engine updated

### Verifying Release Artifacts

**Verify Docker Image Signatures:**
```bash
cosign verify \
  --certificate-identity-regexp=https://github.com/chippr-robotics/fukuii \
  --certificate-oidc-issuer=https://token.actions.githubusercontent.com \
  ghcr.io/chippr-robotics/chordodes_fukuii:v0.1.0
```

**Check SBOM for Vulnerabilities:**
Each release includes a CycloneDX SBOM that can be analyzed with tools like Grype or Trivy to identify known vulnerabilities in dependencies.

## Security Features

### Cryptography
- Secure key generation and management utilities
- Support for standard Ethereum key formats (JSON keystore)
- TLS/SSL support for secure communications
- Secp256k1 elliptic curve cryptography for Ethereum transactions

### Network Security
- Peer-to-peer encryption support
- Configurable firewall and network policies
- Rate limiting and DDoS protection mechanisms

### Code Security
- Mandatory code review process for all changes
- Automated static analysis (Scalafix, Scapegoat)
- Code coverage monitoring (70% minimum threshold)
- Regular dependency vulnerability scanning
- CodeQL analysis in development

## Past Security Advisories

Security advisories will be published here as they are resolved. Currently, there are no published advisories for Fukuii.

When vulnerabilities are disclosed, we will maintain a list with:
- Advisory ID and CVE number (if assigned)
- Severity rating
- Affected versions
- Description of vulnerability
- Remediation steps
- Credit to reporter

## Security Audit and Compliance

### Audit History
Fukuii is derived from the Mantis client originally developed by Input Output (Hong Kong) Ltd. While formal security audits have not yet been conducted specifically for the Fukuii fork, the codebase inherits from a professionally developed Ethereum client.

We are committed to pursuing formal security audits as the project matures.

### Compliance Considerations
- **License Compliance**: Apache 2.0 license for all components
- **Export Controls**: Compliance with cryptography export regulations
- **Data Privacy**: No personal data collection by default

## Contributing to Security

We welcome security contributions from the community:

1. **Security-Focused Pull Requests**: Submit PRs that improve security
2. **Security Testing**: Help us test security features and configurations
3. **Documentation**: Improve security documentation and best practices
4. **Code Review**: Participate in security-focused code reviews

Please review our [Contributing Guide](CONTRIBUTING.md) for general contribution guidelines.

## Security Development Practices

### Code Review Requirements
- All code changes require review by at least one maintainer
- Security-sensitive changes require review by security-focused maintainers
- Automated checks must pass before merge (format, lint, test, coverage)

### Continuous Security
- **CI/CD Security**: GitHub Actions with signed commits and artifacts
- **Dependency Scanning**: Weekly automated vulnerability checks
- **Static Analysis**: Scalafix and Scapegoat run on every commit
- **Dynamic Testing**: Comprehensive test suite with code coverage tracking
- **Container Scanning**: Docker images scanned for vulnerabilities before release

### Incident Response
In case of a security incident:
1. **Detection**: Monitor for security events and anomalies
2. **Containment**: Isolate affected systems and prevent spread
3. **Analysis**: Investigate root cause and impact
4. **Remediation**: Develop and deploy fixes
5. **Communication**: Notify affected users and the community
6. **Post-Mortem**: Document lessons learned and improve processes

## Additional Resources

- [Contributing Guide](CONTRIBUTING.md) - Development and code quality standards
- [Docker Documentation](docker/README.md) - Container security and verification
- [GitHub Workflows](.github/workflows/README.md) - CI/CD and release automation
- [Static Analysis Inventory](STATIC_ANALYSIS_INVENTORY.md) - Code quality tools
- [OWASP Top 10](https://owasp.org/www-project-top-ten/) - General web security guidance
- [Ethereum Security](https://ethereum.org/en/security/) - Ethereum-specific security considerations

## Contact

For general inquiries about Fukuii, please use:
- GitHub Issues: https://github.com/chippr-robotics/fukuii/issues
- GitHub Discussions: https://github.com/chippr-robotics/fukuii/discussions

For security-related matters, please use the reporting methods described above in the "Reporting a Vulnerability" section.

---

**Last Updated**: October 2025  
**Version**: 1.0  
**Maintainer**: Chippr Robotics LLC
