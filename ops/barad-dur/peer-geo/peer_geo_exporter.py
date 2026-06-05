#!/usr/bin/env python3
"""Fukuii peer-geo exporter — geolocates connected peers from the node's own /metrics.

The fukuii node now publishes per-peer telemetry natively as `app_network_peer_info`
(see PeerTelemetry.scala), so this exporter no longer scrapes docker logs. It:

  1. Scrapes `app_network_peer_info{remote_address,client_name,direction,network_id,...}`
     from one or more fukuii nodes' /metrics endpoints.
  2. Geolocates each distinct peer IP (persistent cache first, ip-api.com batch for misses).
  3. Re-exports `fukuii_peer_geo{ip,country,country_code,city,lat,lon,client,direction,network,job}=1`
     for a Grafana Geomap + per-peer table.

Stdlib only (urllib + http.server) so the container needs no pip install. Robust by design:
a scrape or geo-lookup failure never crashes the loop — it serves the last-known snapshot.

Env:
  NODE_TARGETS      comma list of `job=url`, e.g. "etc-mainnet=http://fukuii-primary:9095/metrics"
                    (default: etc-mainnet=http://fukuii-primary:9095/metrics)
  LISTEN_PORT       port to serve /metrics on (default 9099)
  CACHE_FILE        persistent ip->geo cache (default /data/peer_geo_cache.json)
  REFRESH_INTERVAL  seconds between refreshes (default 30)
"""

import ipaddress
import json
import os
import re
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

NODE_TARGETS = os.environ.get(
    "NODE_TARGETS", "etc-mainnet=http://fukuii-primary:9095/metrics"
)
LISTEN_PORT = int(os.environ.get("LISTEN_PORT", "9099"))
CACHE_FILE = os.environ.get("CACHE_FILE", "/data/peer_geo_cache.json")
REFRESH_INTERVAL = int(os.environ.get("REFRESH_INTERVAL", "30"))

# app_network_peer_info{label="val",...} 1
_INFO_LINE = re.compile(r'^app_network_peer_info\{(?P<labels>.*)\}\s+[\d.eE+-]+\s*$')
_LABEL = re.compile(r'(\w+)="((?:[^"\\]|\\.)*)"')


def parse_targets(spec):
    targets = []
    for chunk in spec.split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        if "=" in chunk:
            job, url = chunk.split("=", 1)
        else:
            job, url = "fukuii", chunk
        targets.append((job.strip(), url.strip()))
    return targets


def load_cache():
    try:
        with open(CACHE_FILE) as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


def save_cache(cache):
    try:
        tmp = CACHE_FILE + ".tmp"
        with open(tmp, "w") as fh:
            json.dump(cache, fh)
        os.replace(tmp, CACHE_FILE)
    except OSError as exc:
        print(f"[peer-geo] cache save failed: {exc}", flush=True)


def parse_labels(label_str):
    return {m.group(1): m.group(2).replace('\\"', '"') for m in _LABEL.finditer(label_str)}


def ip_from_remote_address(remote_address):
    # "ip:port"; rsplit so IPv4 is clean and IPv6 keeps its colons.
    if not remote_address:
        return None
    host = remote_address.rsplit(":", 1)[0].strip("[]")
    try:
        ipaddress.ip_address(host)
        return host
    except ValueError:
        return None


def is_public(ip):
    try:
        return ipaddress.ip_address(ip).is_global
    except ValueError:
        return False


def scrape_node(url):
    """Return list of label dicts for each app_network_peer_info series, or [] on failure."""
    try:
        with urllib.request.urlopen(url, timeout=10) as resp:
            body = resp.read().decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001 — never let a scrape error kill the loop
        print(f"[peer-geo] scrape {url} failed: {exc}", flush=True)
        return []
    out = []
    for line in body.splitlines():
        m = _INFO_LINE.match(line)
        if m:
            out.append(parse_labels(m.group("labels")))
    return out


def geolocate_batch(ips, cache):
    """Fill `cache` for any uncached public IPs via ip-api.com batch (<=100/request)."""
    missing = [ip for ip in ips if ip not in cache and is_public(ip)]
    if not missing:
        return
    for i in range(0, len(missing), 100):
        batch = missing[i : i + 100]
        payload = json.dumps(
            [{"query": ip, "fields": "status,country,countryCode,city,lat,lon,query"} for ip in batch]
        ).encode()
        try:
            req = urllib.request.Request(
                "http://ip-api.com/batch", data=payload, headers={"Content-Type": "application/json"}
            )
            with urllib.request.urlopen(req, timeout=20) as resp:
                results = json.load(resp)
        except Exception as exc:  # noqa: BLE001
            print(f"[peer-geo] geo batch failed: {exc}", flush=True)
            return
        for r in results:
            if r.get("status") == "success":
                cache[r["query"]] = {
                    "country": r.get("country", ""),
                    "cc": r.get("countryCode", ""),
                    "city": r.get("city", ""),
                    "lat": r.get("lat", 0.0),
                    "lon": r.get("lon", 0.0),
                }
        # ip-api free tier: 15 req/min. One batch covers 100 IPs; pace if we loop again.
        if i + 100 < len(missing):
            time.sleep(4)


def esc(v):
    return str(v).replace("\\", "\\\\").replace('"', '\\"').replace("\n", " ")


def build_metrics(snapshots, cache):
    """snapshots: list of (job, [label dicts]). Returns Prometheus text exposition."""
    lines = [
        "# HELP fukuii_peer_geo Geolocated peer this node is connected to (value always 1).",
        "# TYPE fukuii_peer_geo gauge",
    ]
    seen = set()
    counts = {}
    for job, series in snapshots:
        counts[job] = 0
        for labels in series:
            ip = ip_from_remote_address(labels.get("remote_address", ""))
            if not ip:
                continue
            counts[job] += 1
            geo = cache.get(ip, {})
            # one series per (job, peer) so reconnects on a new port don't collide
            key = (job, labels.get("peer", ip))
            if key in seen:
                continue
            seen.add(key)
            tags = {
                "node": job,
                "ip": ip,
                "peer": labels.get("peer", ""),
                "client": labels.get("client_name", "unknown"),
                "direction": labels.get("direction", ""),
                "capability": labels.get("capability", ""),
                "network": labels.get("network_id", ""),
                "snap": labels.get("snap", ""),
                "country": geo.get("country", "Unknown"),
                "country_code": geo.get("cc", ""),
                "city": geo.get("city", ""),
                "lat": geo.get("lat", 0.0),
                "lon": geo.get("lon", 0.0),
            }
            label_str = ",".join(f'{k}="{esc(v)}"' for k, v in tags.items())
            lines.append(f"fukuii_peer_geo{{{label_str}}} 1")
    lines.append("# HELP fukuii_peer_geo_count Connected peers seen per scraped node.")
    lines.append("# TYPE fukuii_peer_geo_count gauge")
    for job, n in counts.items():
        lines.append(f'fukuii_peer_geo_count{{node="{esc(job)}"}} {n}')
    lines.append("# HELP fukuii_peer_geo_cache_size Geolocations cached.")
    lines.append("# TYPE fukuii_peer_geo_cache_size gauge")
    lines.append(f"fukuii_peer_geo_cache_size {len(cache)}")
    return "\n".join(lines) + "\n"


class State:
    def __init__(self):
        self.lock = threading.Lock()
        self.metrics = "# peer-geo exporter starting\n"


STATE = State()


def refresh_loop(targets):
    cache = load_cache()
    while True:
        try:
            snapshots = [(job, scrape_node(url)) for job, url in targets]
            all_ips = {
                ip
                for _, series in snapshots
                for ip in (ip_from_remote_address(s.get("remote_address", "")) for s in series)
                if ip
            }
            before = len(cache)
            geolocate_batch(sorted(all_ips), cache)
            if len(cache) != before:
                save_cache(cache)
            text = build_metrics(snapshots, cache)
            with STATE.lock:
                STATE.metrics = text
            total = sum(len(s) for _, s in snapshots)
            print(f"[peer-geo] refreshed: {total} peers, {len(cache)} cached geos", flush=True)
        except Exception as exc:  # noqa: BLE001
            print(f"[peer-geo] refresh error: {exc}", flush=True)
        time.sleep(REFRESH_INTERVAL)


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        if self.path not in ("/metrics", "/"):
            self.send_response(404)
            self.end_headers()
            return
        with STATE.lock:
            body = STATE.metrics.encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; version=0.0.4")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args):  # silence per-request logging
        pass


def main():
    targets = parse_targets(NODE_TARGETS)
    print(f"[peer-geo] targets={targets} port={LISTEN_PORT} cache={CACHE_FILE}", flush=True)
    threading.Thread(target=refresh_loop, args=(targets,), daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", LISTEN_PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
