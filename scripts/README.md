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
