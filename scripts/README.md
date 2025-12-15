# Insomnia to OpenAPI Conversion Script

This script converts the Fukuii Insomnia workspace JSON to an OpenAPI 3.0 specification that can be rendered with Swagger UI in the documentation.

## Purpose

The `convert_insomnia_to_openapi.py` script:

1. Reads `insomnia_workspace.json` from the repository root
2. Extracts all JSON-RPC API endpoints with their:
   - Method names and descriptions
   - Request parameters and examples
   - Namespace/tag organization
3. Generates a complete OpenAPI 3.0 specification
4. Outputs to `docs/api/openapi.json`

## Usage

```bash
# From repository root
python3 scripts/convert_insomnia_to_openapi.py
```

**Output:**
```
✅ Converted 83 endpoints to OpenAPI spec
✅ Created 11 namespace tags
✅ Written to: /path/to/fukuii/docs/api/openapi.json
```

## When to Run

Run this script whenever you:

- Add new JSON-RPC endpoints to Insomnia workspace
- Modify existing endpoint descriptions or examples
- Change endpoint parameters or request/response formats
- Update namespace organization

## Integration with Docs

The generated `openapi.json` is automatically:

1. Copied to the built site by MkDocs
2. Loaded by the Swagger UI plugin on the Interactive API Reference page
3. Displayed with full interactivity (browse, filter, try-it-out)

## Validation

To verify the generated OpenAPI spec:

```bash
# Check basic structure
python3 -c "import json; data = json.load(open('docs/api/openapi.json')); \
  print(f'Title: {data[\"info\"][\"title\"]}'); \
  print(f'Paths: {len(data[\"paths\"])}'); \
  print(f'Tags: {len(data[\"tags\"])}')"

# Validate with online tools
# Upload docs/api/openapi.json to:
# - https://editor.swagger.io/
# - https://apitools.dev/swagger-parser/online/
```

## OpenAPI Spec Features

The generated specification includes:

- **Complete endpoint documentation**: All 83 endpoints across 11 namespaces
- **Request schemas**: JSON-RPC 2.0 format with parameter examples
- **Response schemas**: Success and error responses
- **Namespace tags**: Organized by ETH, WEB3, NET, PERSONAL, etc.
- **Security warnings**: Clearly marked dangerous namespaces
- **Server configurations**: Default local endpoints
- **Reusable components**: Common JSON-RPC request/response/error schemas

## Maintenance

The script is designed to be:

- **Idempotent**: Running multiple times produces the same output
- **Comprehensive**: Captures all request groups and endpoints
- **Standards-compliant**: Generates valid OpenAPI 3.0.3 specification
- **Self-documenting**: Includes rich descriptions and examples

## Troubleshooting

### Script fails to find insomnia_workspace.json

**Solution**: Ensure you're running from the repository root or the file exists.

### Generated spec has missing endpoints

**Solution**: Check that all endpoints in Insomnia workspace have:
- Valid JSON request bodies
- Parent folder/group associations
- Non-empty method names

### Swagger UI doesn't load the spec

**Solution**: 
1. Check `docs/api/openapi.json` was created
2. Verify JSON is valid: `python3 -m json.tool docs/api/openapi.json > /dev/null`
3. Ensure MkDocs build succeeded: `mkdocs build --strict`
4. Check browser console for loading errors

## Related Files

- `insomnia_workspace.json` - Source Insomnia collection
- `docs/api/openapi.json` - Generated OpenAPI specification
- `docs/api/interactive-api-reference.md` - Documentation page using the spec
- `mkdocs.yml` - Configuration enabling swagger-ui-tag plugin
- `requirements-docs.txt` - Python dependencies including mkdocs-swagger-ui-tag

## Future Enhancements

Potential improvements to the conversion script:

- [ ] Extract response schemas from Insomnia environments
- [ ] Add parameter type validation based on JSON-RPC patterns
- [ ] Generate separate specs per namespace for modular docs
- [ ] Add support for WebSocket endpoints
- [ ] Include rate limiting information in spec
- [ ] Auto-generate security scheme configurations

---

# Bootnode Management Script

## update-bootnodes.sh

Automatically updates the ETC bootnode configuration by fetching and validating bootnodes from authoritative sources.

### Purpose

The script ensures that Fukuii maintains a healthy list of 20 active bootnodes by:
- Fetching bootnodes from authoritative sources (core-geth, Hyperledger Besu)
- Validating current bootnodes against these authoritative lists
- Removing bootnodes that are no longer in authoritative sources
- Maintaining exactly 20 active bootnodes with proper port configuration

### Usage

```bash
# Run manually
bash scripts/update-bootnodes.sh

# The script will:
# 1. Extract current bootnodes from src/main/resources/conf/chains/etc-chain.conf
# 2. Fetch bootnodes from core-geth and Besu repositories
# 3. Validate and select 20 bootnodes
# 4. Update the configuration file
# 5. Create a timestamped backup
```

### Automated Execution

The script runs automatically via GitHub Actions on a nightly schedule:
- Workflow: `.github/workflows/nightly.yml`
- Job: `nightly-bootnode-update`
- Schedule: Daily at 00:00 GMT (midnight UTC)

When changes are detected, the workflow automatically creates a pull request with:
- Updated bootnode configuration
- Detailed change summary
- Backup of previous configuration

### Bootnode Sources

1. **Core-geth**: https://github.com/etclabscore/core-geth
   - Official Ethereum Classic client implementation
   - Maintains authoritative list of ETC bootnodes

2. **Hyperledger Besu**: https://github.com/hyperledger/besu
   - Enterprise-grade Ethereum client
   - Supports ETC mainnet with maintained bootnode list

### Selection Logic

The script uses a priority-based selection process:

1. **Priority 1**: Keep current bootnodes that exist in external authoritative sources
2. **Priority 2**: Add new bootnodes from external authoritative sources
3. **Priority 3**: Keep remaining current bootnodes (up to 20 total)

This ensures:
- Stability: Current working bootnodes are preserved when possible
- Freshness: New authoritative bootnodes are added
- Reliability: Non-authoritative or inactive bootnodes are removed

### Validation

Each bootnode is validated to ensure:
- Proper enode URL format: `enode://[pubkey]@[ip]:[port]`
- Valid public key (128 hex characters, case-insensitive)
- Valid IP address or hostname
- Valid port number
- Proper discovery port specification (if applicable)
- Presence in authoritative sources (core-geth, Hyperledger Besu)

### Configuration File

Target file: `src/main/resources/conf/chains/etc-chain.conf`

The script updates the `bootstrap-nodes` array in the ETC chain configuration, maintaining:
- Header comments indicating automated management
- Timestamp of last update
- Source attribution (core-geth, Besu)
- Proper HOCON array formatting

### Backups

Before each update, the script creates a timestamped backup:
```
src/main/resources/conf/chains/etc-chain.conf.backup.YYYYMMDD_HHMMSS
```

Backups are retained locally but are not committed to version control (excluded via .gitignore).

### Manual Intervention

If manual bootnode management is required:
1. Update `src/main/resources/conf/chains/etc-chain.conf` directly
2. Commit your changes
3. The script will respect your changes on the next run if they match authoritative sources

To disable automated updates:
1. Comment out or remove the `nightly-bootnode-update` job in `.github/workflows/nightly.yml`
2. Or update the script to skip specific bootnodes

### Troubleshooting

**Issue**: Script reports "Only X bootnodes available (target: 20)"
- **Cause**: Authoritative sources have fewer than 20 bootnodes
- **Resolution**: This is expected; script will use all available bootnodes

**Issue**: Important bootnode is being removed
- **Cause**: Bootnode is not in core-geth or Besu authoritative lists
- **Resolution**: 
  1. Verify the bootnode is still active and reliable
  2. Submit PR to core-geth or Besu to add the bootnode
  3. Or manually maintain the bootnode in configuration and disable automation

**Issue**: Script fails to fetch from external sources
- **Cause**: Network issues or repository changes
- **Resolution**: Check GitHub Actions logs for specific error messages

### Related Files

- `.github/workflows/nightly.yml` - GitHub Actions workflow
- `src/main/resources/conf/chains/etc-chain.conf` - Target configuration file
- `ops/cirith-ungol/conf/static-nodes.json` - Additional static nodes configuration

