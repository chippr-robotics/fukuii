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
    
    print("=== Validation Results ===")
    print(f"Insomnia requests: {len(insomnia_requests)}")
    print(f"OpenAPI paths: {len(openapi_paths)}")
    
    # Check if counts match
    if len(insomnia_requests) == len(openapi_paths):
        print("✅ OpenAPI spec is in sync with Insomnia workspace")
        return 0
    else:
        print("❌ OpenAPI spec is OUT OF SYNC with Insomnia workspace")
        print(f"   Expected {len(insomnia_requests)} paths, found {len(openapi_paths)}")
        print("   Run: python3 scripts/convert_insomnia_to_openapi.py")
        return 1


if __name__ == "__main__":
    sys.exit(main())
