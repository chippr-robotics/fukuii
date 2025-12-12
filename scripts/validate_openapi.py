#!/usr/bin/env python3
"""
Validate that the OpenAPI spec is in sync with the Insomnia workspace.
This script ensures the openapi.json is up-to-date.
"""

import json
import sys
from pathlib import Path


def main():
    repo_root = Path(__file__).parent.parent
    insomnia_file = repo_root / "insomnia_workspace.json"
    openapi_file = repo_root / "docs" / "api" / "openapi.json"
    
    # Check files exist
    if not insomnia_file.exists():
        print(f"❌ Error: {insomnia_file} not found", file=sys.stderr)
        sys.exit(1)
    
    if not openapi_file.exists():
        print(f"❌ Error: {openapi_file} not found", file=sys.stderr)
        print("   Run: python3 scripts/convert_insomnia_to_openapi.py", file=sys.stderr)
        sys.exit(1)
    
    # Load both files
    with open(insomnia_file, 'r') as f:
        insomnia_data = json.load(f)
    
    with open(openapi_file, 'r') as f:
        openapi_data = json.load(f)
    
    # Count requests in Insomnia workspace
    insomnia_requests = [
        r for r in insomnia_data.get("resources", [])
        if r.get("_type") == "request"
    ]
    
    # Count paths in OpenAPI spec
    openapi_paths = openapi_data.get("paths", {})
    
    # Get method names from both sources
    insomnia_methods = {r.get("name") for r in insomnia_requests}
    openapi_methods = {path.lstrip('/') for path in openapi_paths.keys()}
    
    print("=== Validation Results ===")
    print(f"Insomnia requests: {len(insomnia_requests)}")
    print(f"OpenAPI paths: {len(openapi_paths)}")
    
    # Check for missing or extra endpoints
    missing_in_openapi = insomnia_methods - openapi_methods
    extra_in_openapi = openapi_methods - insomnia_methods
    
    if missing_in_openapi:
        print(f"\n⚠️  Missing in OpenAPI ({len(missing_in_openapi)}):")
        for method in sorted(missing_in_openapi)[:5]:
            print(f"   - {method}")
        if len(missing_in_openapi) > 5:
            print(f"   ... and {len(missing_in_openapi) - 5} more")
    
    if extra_in_openapi:
        print(f"\n⚠️  Extra in OpenAPI ({len(extra_in_openapi)}):")
        for method in sorted(extra_in_openapi)[:5]:
            print(f"   - {method}")
        if len(extra_in_openapi) > 5:
            print(f"   ... and {len(extra_in_openapi) - 5} more")
    
    # Check if counts match and no differences
    if len(insomnia_requests) == len(openapi_paths) and not missing_in_openapi and not extra_in_openapi:
        print("✅ OpenAPI spec is in sync with Insomnia workspace")
        return 0
    else:
        print("\n❌ OpenAPI spec is OUT OF SYNC with Insomnia workspace")
        if len(insomnia_requests) != len(openapi_paths):
            print(f"   Count mismatch: Expected {len(insomnia_requests)} paths, found {len(openapi_paths)}")
        print("   Run: python3 scripts/convert_insomnia_to_openapi.py")
        return 1


if __name__ == "__main__":
    sys.exit(main())
