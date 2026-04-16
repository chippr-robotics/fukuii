#!/usr/bin/env python3
import argparse
import json
import time
import urllib.parse
import urllib.request


def rpc(url: str, method: str, params=None, *, id_: int = 1, timeout: float = 5.0):
    if params is None:
        params = []
    payload = json.dumps({"jsonrpc": "2.0", "id": id_, "method": method, "params": params}).encode()
    req = urllib.request.Request(url, data=payload, headers={"content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        out = json.loads(resp.read().decode())
    if out.get("error"):
        raise RuntimeError(out["error"])
    return out["result"]


def extract_host_from_enode(enode: str) -> str | None:
    # enode://<pubkey>@<host>:<port>[?discport=...]
    if not enode.startswith("enode://"):
        return None
    # URI parser doesn't like enode scheme in all environments; parse manually.
    try:
        at = enode.rfind("@")
        if at == -1:
            return None
        hostport = enode[at + 1 :]
        hostport = hostport.split("?")[0]
        host = hostport.split(":", 1)[0]
        return host
    except Exception:
        return None


def main() -> int:
    p = argparse.ArgumentParser(
        description="Collect enode URLs from core-geth (admin_peers) and ask Fukuii to connect to them (net_connectToPeer)."
    )
    p.add_argument("--coregeth-url", default="http://127.0.0.1:18545", help="core-geth JSON-RPC URL")
    p.add_argument("--fukuii-url", default="http://127.0.0.1:8546", help="Fukuii JSON-RPC URL")
    p.add_argument("--limit", type=int, default=30, help="maximum enodes to attempt")
    p.add_argument("--sleep", type=float, default=0.0, help="seconds to sleep before showing final status")
    p.add_argument("--dry-run", action="store_true", help="print selected enodes, do not call net_connectToPeer")
    p.add_argument(
        "--snap-only",
        action="store_true",
        help="only select peers that advertise SNAP capability (caps includes 'snap/1')",
    )
    args = p.parse_args()

    before = rpc(args.fukuii_url, "net_peerCount")

    # Determine Fukuii's own advertised host (so we don't try self-connect).
    fukuii_host = None
    try:
        node_info = rpc(args.fukuii_url, "net_nodeInfo")
        if isinstance(node_info, dict) and isinstance(node_info.get("enode"), str):
            fukuii_host = extract_host_from_enode(node_info["enode"])
    except Exception:
        pass

    peers = rpc(args.coregeth_url, "admin_peers")
    snap_enodes: list[str] = []
    other_enodes: list[str] = []
    for peer in peers:
        enode = peer.get("enode")
        if not (isinstance(enode, str) and enode.startswith("enode://")):
            continue
        if fukuii_host and f"@{fukuii_host}:" in enode:
            continue

        caps = peer.get("caps")
        caps_list: list[str] = caps if isinstance(caps, list) else []
        has_snap = any(isinstance(c, str) and c == "snap/1" for c in caps_list)
        has_eth = any(isinstance(c, str) and c.startswith("eth/") for c in caps_list)

        # Focus on peers that are likely to serve SNAP range requests.
        # We prioritize SNAP-capable peers first; optionally restrict to them.
        if has_snap and has_eth:
            snap_enodes.append(enode)
        else:
            other_enodes.append(enode)

    # Dedup while preserving order (also keeps SNAP-first priority)
    seen: set[str] = set()
    if args.snap_only:
        selected = snap_enodes
    else:
        selected = snap_enodes + other_enodes
    enodes = [e for e in selected if not (e in seen or seen.add(e))]
    enodes = enodes[: max(args.limit, 0)]

    print(f"Fukuii net_peerCount before: {before}")
    print(
        f"Core-geth peers: {len(peers)}; snap-capable enodes: {len(snap_enodes)}; other enodes: {len(other_enodes)}; selected: {len(enodes)}"
    )

    if args.dry_run:
        for en in enodes:
            print(en)
        return 0

    ok = 0
    for i, en in enumerate(enodes, start=1):
        res = rpc(args.fukuii_url, "net_connectToPeer", [en], id_=1000 + i)
        print(f"[{i:02d}] net_connectToPeer -> {res}  {en}")
        ok += 1 if res else 0

    print(f"connectToPeer true: {ok}/{len(enodes)}")

    if args.sleep > 0:
        time.sleep(args.sleep)

    after = rpc(args.fukuii_url, "net_peerCount")
    print(f"Fukuii net_peerCount after: {after}")

    # Show a compact status breakdown
    peers_out = rpc(args.fukuii_url, "net_listPeers")
    if isinstance(peers_out, dict) and "peers" in peers_out:
        peers_out = peers_out["peers"]

    status_counts: dict[str, int] = {}
    if isinstance(peers_out, list):
        for peer in peers_out:
            st = peer.get("status") or "UNKNOWN"
            status_counts[st] = status_counts.get(st, 0) + 1

    print("Statuses:", status_counts)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
