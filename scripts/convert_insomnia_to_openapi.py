#!/usr/bin/env python3
"""
Convert Insomnia workspace JSON to OpenAPI 3.0 specification.
This script reads the insomnia_workspace.json file and generates a comprehensive
OpenAPI spec that can be displayed with Redoc or Swagger UI in MkDocs.
"""

import json
import sys
import re
from pathlib import Path
from typing import Dict, List, Any


def parse_json_body(body_text: str) -> Dict[str, Any]:
    """Parse JSON body from Insomnia request."""
    try:
        return json.loads(body_text)
    except json.JSONDecodeError:
        return {}


def convert_insomnia_to_openapi(insomnia_file: Path, output_file: Path):
    """Convert Insomnia workspace to OpenAPI 3.0 specification."""
    
    # Load Insomnia workspace
    with open(insomnia_file, 'r') as f:
        insomnia_data = json.load(f)
    
    # Initialize OpenAPI spec
    openapi_spec = {
        "openapi": "3.0.3",
        "info": {
            "title": "Fukuii JSON-RPC API",
            "description": """
# Fukuii JSON-RPC API Reference

Complete reference for all JSON-RPC endpoints in the Fukuii Ethereum Classic client.

## Quick Start

All JSON-RPC methods are called via HTTP POST to the RPC endpoint (default: http://localhost:8546).

Example request:
```bash
curl -X POST http://localhost:8546 \\
  -H "Content-Type: application/json" \\
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "eth_blockNumber",
    "params": []
  }'
```

## Namespaces

Fukuii organizes methods into namespaces:

- **ETH**: Core blockchain operations (blocks, transactions, state)
- **WEB3**: Utility methods (hashing, version)
- **NET**: Network and peer information
- **PERSONAL**: Account management (dev/test only)
- **DEBUG**: Debugging and diagnostics
- **QA**: Testing utilities (test networks only)
- **CHECKPOINTING**: ETC checkpointing system
- **FUKUII**: Custom Fukuii extensions
- **TEST**: Test harness methods (test networks only)
- **IELE**: IELE VM support (if enabled)
- **RPC**: RPC metadata

## Security

⚠️ **Important**: Only expose `eth`, `web3`, and `net` namespaces in production.
Never expose `personal`, `debug`, `test`, or `qa` namespaces on public networks.

## Configuration

Enable/disable namespaces in your configuration:
```hocon
fukuii.network.rpc {
  http {
    enabled = true
    interface = "127.0.0.1"
    port = 8546
    apis = "eth,web3,net"  # Comma-separated list
  }
}
```
""",
            "version": "1.0.0",
            "contact": {
                "name": "Chippr Robotics LLC",
                "url": "https://github.com/chippr-robotics/fukuii"
            },
            "license": {
                "name": "Apache 2.0",
                "url": "https://www.apache.org/licenses/LICENSE-2.0.html"
            }
        },
        "servers": [
            {
                "url": "http://localhost:8546",
                "description": "Local development node"
            },
            {
                "url": "http://localhost:8545",
                "description": "Alternative local port"
            }
        ],
        "paths": {},
        "components": {
            "schemas": {
                "JSONRPCRequest": {
                    "type": "object",
                    "required": ["jsonrpc", "method", "params", "id"],
                    "properties": {
                        "jsonrpc": {
                            "type": "string",
                            "enum": ["2.0"],
                            "description": "JSON-RPC version"
                        },
                        "method": {
                            "type": "string",
                            "description": "Method name"
                        },
                        "params": {
                            "type": "array",
                            "description": "Method parameters"
                        },
                        "id": {
                            "oneOf": [
                                {"type": "string"},
                                {"type": "integer"}
                            ],
                            "description": "Request identifier"
                        }
                    }
                },
                "JSONRPCResponse": {
                    "type": "object",
                    "required": ["jsonrpc", "id"],
                    "properties": {
                        "jsonrpc": {
                            "type": "string",
                            "enum": ["2.0"]
                        },
                        "result": {
                            "description": "Result of the method call"
                        },
                        "error": {
                            "$ref": "#/components/schemas/JSONRPCError"
                        },
                        "id": {
                            "oneOf": [
                                {"type": "string"},
                                {"type": "integer"}
                            ]
                        }
                    }
                },
                "JSONRPCError": {
                    "type": "object",
                    "required": ["code", "message"],
                    "properties": {
                        "code": {
                            "type": "integer",
                            "description": "Error code"
                        },
                        "message": {
                            "type": "string",
                            "description": "Error message"
                        },
                        "data": {
                            "description": "Additional error data"
                        }
                    }
                }
            }
        },
        "tags": []
    }
    
    # Group requests by folder (namespace)
    groups: Dict[str, List[Dict]] = {}
    group_names: Dict[str, str] = {}
    
    for resource in insomnia_data.get("resources", []):
        if resource["_type"] == "request_group":
            group_id = resource["_id"]
            group_name = resource["name"]
            group_names[group_id] = group_name
            groups[group_id] = []
    
    # Process requests
    for resource in insomnia_data.get("resources", []):
        if resource["_type"] != "request":
            continue
        
        parent_id = resource.get("parentId")
        group_name = group_names.get(parent_id, "Other")
        
        # Find the top-level group (namespace)
        current_id = parent_id
        while current_id in group_names:
            parent_of_current = None
            for r in insomnia_data.get("resources", []):
                if r["_id"] == current_id and r["_type"] == "request_group":
                    parent_of_current = r.get("parentId")
                    break
            
            if parent_of_current and parent_of_current in group_names:
                group_name = group_names[parent_of_current]
                current_id = parent_of_current
            else:
                break
        
        if parent_id not in groups:
            groups[parent_id] = []
        
        groups[parent_id].append({
            "resource": resource,
            "namespace": group_name
        })
    
    # Create tags for namespaces
    namespace_descriptions = {
        "ETH": "Core Ethereum blockchain operations including blocks, transactions, accounts, and smart contracts",
        "WEB3": "Utility methods for hashing and client information",
        "NET": "Network status and peer connection information",
        "PERSONAL": "Account management methods (⚠️ Development/test only)",
        "DEBUG": "Debugging and diagnostic methods (⚠️ Use with caution)",
        "QA": "Quality assurance and testing utilities (❌ Test networks only)",
        "CHECKPOINTING": "ETC checkpointing system for finality",
        "FUKUII": "Custom Fukuii-specific extensions",
        "TEST": "Test harness methods (❌ Test networks only)",
        "IELE": "IELE VM support (if enabled)",
        "RPC": "RPC metadata and module information"
    }
    
    seen_namespaces = set()
    
    # Generate paths for each request
    for group_id, requests in groups.items():
        for item in requests:
            resource = item["resource"]
            namespace = item["namespace"]
            
            # Add namespace tag if not seen
            if namespace not in seen_namespaces:
                seen_namespaces.add(namespace)
                openapi_spec["tags"].append({
                    "name": namespace,
                    "description": namespace_descriptions.get(namespace, f"{namespace} namespace methods")
                })
            
            method_name = resource.get("name", "unknown")
            description = resource.get("description", "")
            
            # Parse request body
            body_data = resource.get("body", {})
            body_text = body_data.get("text", "{}")
            body_json = parse_json_body(body_text)
            
            # Create operation ID from method name (ensure uniqueness)
            operation_id = method_name.replace("-", "_")  # Keep underscores for uniqueness
            
            # Create path (we'll use /{method} pattern)
            path = f"/{method_name}"
            
            if path not in openapi_spec["paths"]:
                openapi_spec["paths"][path] = {}
            
            # Create request body schema
            request_schema = {
                "type": "object",
                "required": ["jsonrpc", "method", "params", "id"],
                "properties": {
                    "jsonrpc": {
                        "type": "string",
                        "enum": ["2.0"],
                        "example": "2.0"
                    },
                    "method": {
                        "type": "string",
                        "enum": [method_name],
                        "example": method_name
                    },
                    "params": {
                        "type": "array",
                        "items": {},
                        "example": body_json.get("params", [])
                    },
                    "id": {
                        "oneOf": [
                            {"type": "string"},
                            {"type": "integer"}
                        ],
                        "example": 1
                    }
                }
            }
            
            # Build operation
            operation = {
                "tags": [namespace],
                "summary": method_name,
                "description": description or f"Execute {method_name} JSON-RPC method",
                "operationId": operation_id,
                "requestBody": {
                    "required": True,
                    "content": {
                        "application/json": {
                            "schema": request_schema,
                            "example": body_json
                        }
                    }
                },
                "responses": {
                    "200": {
                        "description": "Successful response",
                        "content": {
                            "application/json": {
                                "schema": {
                                    "$ref": "#/components/schemas/JSONRPCResponse"
                                }
                            }
                        }
                    },
                    "400": {
                        "description": "Bad request - Invalid JSON-RPC request"
                    },
                    "500": {
                        "description": "Internal server error"
                    }
                }
            }
            
            openapi_spec["paths"][path]["post"] = operation
    
    # Sort paths alphabetically
    openapi_spec["paths"] = dict(sorted(openapi_spec["paths"].items()))
    
    # Write OpenAPI spec
    with open(output_file, 'w') as f:
        json.dump(openapi_spec, f, indent=2)
    
    print(f"✅ Converted {len(openapi_spec['paths'])} endpoints to OpenAPI spec")
    print(f"✅ Created {len(openapi_spec['tags'])} namespace tags")
    print(f"✅ Written to: {output_file}")


def main():
    # Get paths
    repo_root = Path(__file__).parent.parent
    insomnia_file = repo_root / "insomnia_workspace.json"
    output_file = repo_root / "docs" / "api" / "openapi.json"
    
    if not insomnia_file.exists():
        print(f"❌ Error: {insomnia_file} not found", file=sys.stderr)
        sys.exit(1)
    
    convert_insomnia_to_openapi(insomnia_file, output_file)


if __name__ == "__main__":
    main()
