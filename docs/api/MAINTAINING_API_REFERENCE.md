# Maintaining the Interactive API Reference

This guide explains how to maintain the Interactive API Reference that displays the Fukuii JSON-RPC API specification on the documentation website.

## Overview

The Interactive API Reference is generated from the Insomnia workspace and displayed using OpenAPI (Swagger) specification:

```
insomnia_workspace.json 
    ↓ (convert script)
docs/api/openapi.json
    ↓ (MkDocs + Swagger UI plugin)
Interactive API Reference webpage
```

## When to Update

Update the API reference whenever you:

1. **Add new JSON-RPC endpoints**
2. **Modify existing endpoint parameters or behavior**
3. **Change endpoint descriptions or examples**
4. **Update namespace organization**
5. **Add/remove entire namespaces**

## Step-by-Step Update Process

### 1. Update the Insomnia Workspace

First, update the `insomnia_workspace.json` file in the repository root:

**Option A: Use Insomnia Desktop App**

1. Open Insomnia and import `insomnia_workspace.json`
2. Make your changes (add/edit endpoints)
3. Export the workspace: `Application → Preferences → Data → Export Data → Current Workspace`
4. Replace `insomnia_workspace.json` with the exported file

**Option B: Edit JSON Directly**

1. Open `insomnia_workspace.json` in your editor
2. Add/modify request objects following the existing structure
3. Ensure each request has:
   - Unique `_id`
   - `name` (the method name, e.g., "eth_blockNumber")
   - `description` (what the method does)
   - `body.text` (JSON-RPC request example)
   - Correct `parentId` (namespace folder)

### 2. Regenerate the OpenAPI Specification

Run the conversion script:

```bash
python3 scripts/convert_insomnia_to_openapi.py
```

**Expected output:**
```
✅ Converted 83 endpoints to OpenAPI spec
✅ Created 11 namespace tags
✅ Written to: /path/to/fukuii/docs/api/openapi.json
```

### 3. Validate the Changes

Run the validation script to ensure everything is in sync:

```bash
python3 scripts/validate_openapi.py
```

**Expected output:**
```
=== Validation Results ===
Insomnia requests: 83
OpenAPI paths: 83
✅ OpenAPI spec is in sync with Insomnia workspace
```

### 4. Test Locally

Build and preview the documentation:

```bash
# Install dependencies (if not already installed)
pip install -r requirements-docs.txt

# Build the docs
mkdocs build --strict

# Serve locally and preview
mkdocs serve
# Visit http://localhost:8000/api/interactive-api-reference/
```

**What to check:**
- [ ] All endpoints appear in Swagger UI
- [ ] Namespaces (tags) are correctly organized
- [ ] Request examples are valid JSON
- [ ] Descriptions are clear and helpful
- [ ] Try-it-out functionality works (if enabled)

### 5. Commit and Push

```bash
git add insomnia_workspace.json docs/api/openapi.json
git commit -m "Update API reference: [describe your changes]"
git push
```

The CI workflow will automatically:
1. Validate the OpenAPI spec
2. Build the documentation
3. Deploy preview (for PRs)
4. Publish to docs site (on merge to main)

## Troubleshooting

### Issue: Conversion script fails

**Error:** `JSONDecodeError` when parsing insomnia_workspace.json

**Solution:**
1. Validate the JSON syntax: `python3 -m json.tool insomnia_workspace.json > /dev/null`
2. Check for:
   - Missing commas between objects
   - Unescaped quotes in strings
   - Trailing commas

### Issue: Validation fails with count mismatch

**Error:** `OpenAPI spec is OUT OF SYNC`

**Solution:**
1. Re-run the conversion script: `python3 scripts/convert_insomnia_to_openapi.py`
2. Check if you edited `openapi.json` directly (don't do this - edit Insomnia workspace instead)
3. Ensure all requests in Insomnia have valid JSON bodies

### Issue: Endpoint doesn't appear in Swagger UI

**Possible causes:**
1. Request is missing `name` or has invalid `name`
2. Request body has invalid JSON
3. Request is not properly linked to a namespace folder via `parentId`

**Solution:**
1. Check the request in `insomnia_workspace.json`
2. Verify it has a valid parent folder
3. Re-run conversion script
4. Check browser console for JavaScript errors

### Issue: Documentation build fails

**Error during `mkdocs build`:**

**Solution:**
1. Check Python dependencies: `pip install -r requirements-docs.txt`
2. Validate OpenAPI spec format: Upload to https://editor.swagger.io/
3. Check MkDocs configuration in `mkdocs.yml`
4. Review build logs for specific error messages

## File Structure

```
fukuii/
├── insomnia_workspace.json          # Source of truth
├── docs/
│   └── api/
│       ├── openapi.json             # Generated OpenAPI spec
│       ├── interactive-api-reference.md  # Page with Swagger UI
│       └── README.md                # API docs index
├── scripts/
│   ├── convert_insomnia_to_openapi.py   # Conversion script
│   ├── validate_openapi.py              # Validation script
│   └── README.md                        # Scripts documentation
├── mkdocs.yml                       # MkDocs configuration
└── requirements-docs.txt            # Python dependencies
```

## CI/CD Integration

The documentation workflow (`.github/workflows/docs-preview.yml`) automatically:

1. **On PR with docs changes:**
   - Validates OpenAPI spec is in sync
   - Builds documentation with `--strict` mode
   - Deploys preview to PR
   
2. **On merge to main:**
   - Validates and builds docs
   - Publishes to GitHub Pages

**Workflow triggers on changes to:**
- `docs/**`
- `mkdocs.yml`
- `requirements-docs.txt`
- `scripts/convert_insomnia_to_openapi.py`
- `scripts/validate_openapi.py`
- `insomnia_workspace.json`

## Best Practices

### 1. Always update Insomnia workspace first
Never edit `openapi.json` directly. Always make changes in `insomnia_workspace.json` and regenerate.

### 2. Use clear descriptions
Each endpoint should have a concise, helpful description that explains:
- What the method does
- When to use it
- Any important notes (e.g., "⚠️ Development only")

### 3. Provide complete examples
Request examples should:
- Use realistic parameter values
- Show all required parameters
- Include optional parameters where helpful
- Use the environment variables (e.g., `{{ address }}`)

### 4. Organize by namespace
Keep endpoints grouped in logical namespaces:
- **ETH**: Core blockchain operations
- **WEB3**: Utility methods
- **NET**: Network information
- **PERSONAL**: Account management (dev only)
- etc.

### 5. Mark dangerous endpoints
Use warning symbols in descriptions:
- ⚠️ for development/test only
- ❌ for never use in production
- ✅ for production-ready

### 6. Test before committing
Always run the full validation and build cycle before pushing:
```bash
python3 scripts/convert_insomnia_to_openapi.py
python3 scripts/validate_openapi.py
mkdocs build --strict
```

## Advanced: Adding New Namespaces

To add a new namespace (e.g., "ADMIN"):

1. **In Insomnia workspace:**
   ```json
   {
     "_id": "fld_admin001",
     "parentId": "wrk_097d43914a4d4aea8b6f73f647921182",
     "name": "ADMIN",
     "description": "Administrative methods",
     "_type": "request_group"
   }
   ```

2. **Add requests under the namespace:**
   ```json
   {
     "_id": "req_admin_status",
     "parentId": "fld_admin001",
     "name": "admin_status",
     "description": "Get admin status",
     "body": {
       "mimeType": "application/json",
       "text": "{\n  \"jsonrpc\": \"2.0\",\n  \"id\": 1,\n  \"method\": \"admin_status\",\n  \"params\": []\n}"
     },
     "_type": "request"
   }
   ```

3. **Update conversion script** (if needed):
   Add namespace description to `namespace_descriptions` dict in `convert_insomnia_to_openapi.py`:
   ```python
   "ADMIN": "Administrative methods for node management"
   ```

4. **Regenerate and validate:**
   ```bash
   python3 scripts/convert_insomnia_to_openapi.py
   python3 scripts/validate_openapi.py
   mkdocs build --strict
   ```

## Getting Help

If you encounter issues:

1. Check this guide's troubleshooting section
2. Review existing Insomnia workspace structure for examples
3. Validate your JSON syntax
4. Check CI logs for detailed error messages
5. Open an issue on GitHub with:
   - What you're trying to do
   - Error messages
   - Steps to reproduce

## Additional Resources

- [OpenAPI Specification 3.0](https://spec.openapis.org/oas/v3.0.3)
- [Insomnia Documentation](https://docs.insomnia.rest/)
- [MkDocs Material](https://squidfunk.github.io/mkdocs-material/)
- [Swagger UI](https://swagger.io/tools/swagger-ui/)
- [Fukuii API Documentation](https://chippr-robotics.github.io/fukuii/api/)
