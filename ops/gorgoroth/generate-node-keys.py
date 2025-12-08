#!/usr/bin/env python3
"""
Generate persistent node keys for Gorgoroth network nodes.

This script generates secp256k1 key pairs for each node and creates:
1. node.key files in each node's configuration directory
2. Updated static-nodes.json files with correct enode URLs

The node.key format is:
Line 1: Private key (64 hex chars - 32 bytes)
Line 2: Public key (128 hex chars - 64 bytes, uncompressed without 0x04 prefix)
"""

import os
import sys
import json
from pathlib import Path

try:
    from eth_keys import keys
    from eth_utils import encode_hex
except ImportError:
    print("Error: Required packages not found. Installing...")
    import subprocess
    subprocess.check_call([sys.executable, "-m", "pip", "install", "-q", "eth-keys", "eth-utils"])
    from eth_keys import keys
    from eth_utils import encode_hex


def generate_key_pair():
    """Generate a new secp256k1 key pair."""
    private_key = keys.PrivateKey(os.urandom(32))
    public_key = private_key.public_key
    
    # Get the uncompressed public key (64 bytes without 0x04 prefix)
    public_key_bytes = public_key.to_bytes()
    
    return {
        'private': encode_hex(private_key.to_bytes())[2:],  # Remove 0x prefix
        'public': encode_hex(public_key_bytes)[2:]  # Remove 0x prefix
    }


def create_node_key_file(node_dir, key_pair):
    """Create a node.key file with the key pair."""
    node_key_path = Path(node_dir) / 'node.key'
    with open(node_key_path, 'w') as f:
        f.write(f"{key_pair['private']}\n")
        f.write(f"{key_pair['public']}\n")
    print(f"✓ Created {node_key_path}")
    return node_key_path


def create_enode_url(public_key, hostname, port=30303):
    """Create an enode URL from a public key."""
    return f"enode://{public_key}@{hostname}:{port}"


def update_static_nodes(node_dir, enode_urls, exclude_node):
    """Update static-nodes.json with the correct enode URLs."""
    static_nodes_path = Path(node_dir) / 'static-nodes.json'
    
    # Filter out the current node from the list
    peer_enodes = [url for url in enode_urls if exclude_node not in url]
    
    with open(static_nodes_path, 'w') as f:
        json.dump(peer_enodes, f, indent=2)
        f.write('\n')  # Add trailing newline
    
    print(f"✓ Updated {static_nodes_path}")


def main():
    """Main function to generate keys and update configurations."""
    script_dir = Path(__file__).parent
    conf_dir = script_dir / 'conf'
    
    # Configuration for 3-node network
    nodes = [
        {'name': 'node1', 'hostname': 'fukuii-node1'},
        {'name': 'node2', 'hostname': 'fukuii-node2'},
        {'name': 'node3', 'hostname': 'fukuii-node3'},
    ]
    
    print("=" * 60)
    print("Generating persistent node keys for Gorgoroth network")
    print("=" * 60)
    print()
    
    # Generate keys for all nodes
    node_keys = {}
    for node in nodes:
        node_dir = conf_dir / node['name']
        if not node_dir.exists():
            print(f"✗ Node directory not found: {node_dir}")
            sys.exit(1)
        
        print(f"Generating keys for {node['name']}...")
        key_pair = generate_key_pair()
        node_keys[node['name']] = {
            'key_pair': key_pair,
            'dir': node_dir,
            'hostname': node['hostname']
        }
        create_node_key_file(node_dir, key_pair)
    
    print()
    print("=" * 60)
    print("Generating enode URLs")
    print("=" * 60)
    print()
    
    # Create enode URLs
    enode_urls = []
    for node in nodes:
        node_data = node_keys[node['name']]
        enode_url = create_enode_url(
            node_data['key_pair']['public'],
            node_data['hostname']
        )
        enode_urls.append(enode_url)
        print(f"{node['name']}: {enode_url}")
    
    print()
    print("=" * 60)
    print("Updating static-nodes.json files")
    print("=" * 60)
    print()
    
    # Update static-nodes.json for each node
    for node in nodes:
        node_data = node_keys[node['name']]
        update_static_nodes(node_data['dir'], enode_urls, node['hostname'])
    
    print()
    print("=" * 60)
    print("SUCCESS: Node keys generated and static-nodes.json updated")
    print("=" * 60)
    print()
    print("Next steps:")
    print("1. The node.key files will be mounted into containers via docker-compose")
    print("2. Start the network: cd ../tools && ./fukuii-cli.sh start 3nodes")
    print("3. Verify peer connections after ~30 seconds")
    print()


if __name__ == '__main__':
    main()
