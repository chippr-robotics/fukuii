#!/usr/bin/env python3
"""Peer geo exporter for the barad-dur Grafana node-map.

Reads peer IPs the fukuii node logs during discovery/connection, geo-locates them
(ip-api.com batch, free, cached on disk), and exposes a Prometheus /metrics endpoint:

  fukuii_peer_location{ip,country,cc,city,latitude,longitude} 1   # one series per active peer
  fukuii_peers_by_country{country,cc} N
  fukuii_peer_total N
  fukuii_peer_located N

Runs on the HOST (so it can `docker logs fukuii-primary` without touching the node). Prometheus
scrapes it at the docker-network gateway 172.21.0.1:9099. Entirely outside the node — safe to run
during the recovery scan.
"""
import json
import os
import re
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 9099
CONTAINER = "fukuii-primary"
LOG_WINDOW = "15m"        # peers seen within this window are "active"
ACTIVE_TTL = 1800         # drop a peer from the map this many seconds after last sighting
POLL_INTERVAL = 60
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "peer_geo_cache.json")

# PeerAddress(1.2.3.4) and peer=1.2.3.4:port
IP_RE = re.compile(r"PeerAddress\((\d{1,3}(?:\.\d{1,3}){3})\)|peer=(\d{1,3}(?:\.\d{1,3}){3}):")
_geo = {}            # ip -> {country, cc, city, lat, lon} or None (unlocatable)
_active = {}         # ip -> last_seen epoch
_lock = threading.Lock()


def _private(ip):
    return ip.startswith(("10.", "127.", "192.168.", "169.254.", "0.")) or \
        ip.startswith("172.") and 16 <= int(ip.split(".")[1]) <= 31


def load_cache():
    global _geo
    try:
        _geo = json.load(open(CACHE))
    except Exception:
        _geo = {}


def save_cache():
    try:
        json.dump(_geo, open(CACHE, "w"))
    except Exception:
        pass


def geolocate(ips):
    todo = [ip for ip in ips if ip not in _geo]
    for i in range(0, len(todo), 100):
        batch = todo[i:i + 100]
        try:
            req = urllib.request.Request(
                "http://ip-api.com/batch?fields=query,status,country,countryCode,city,lat,lon",
                data=json.dumps([{"query": ip} for ip in batch]).encode(),
                headers={"Content-Type": "application/json"},
            )
            for r in json.load(urllib.request.urlopen(req, timeout=20)):
                if r.get("status") == "success":
                    _geo[r["query"]] = {"country": r.get("country", ""), "cc": r.get("countryCode", ""),
                                        "city": r.get("city", ""), "lat": r.get("lat"), "lon": r.get("lon")}
                else:
                    _geo[r["query"]] = None
            time.sleep(4)  # ip-api batch limit ~15/min
        except Exception as e:
            print("geo error:", e, flush=True)
            break
    save_cache()


def poller():
    while True:
        try:
            p = subprocess.run(["docker", "logs", CONTAINER, "--since", LOG_WINDOW],
                               capture_output=True, text=True, timeout=45)
            ips = set()
            for m in IP_RE.finditer((p.stdout or "") + (p.stderr or "")):
                ip = m.group(1) or m.group(2)
                if ip and not _private(ip):
                    ips.add(ip)
            geolocate(list(ips))
            now = time.time()
            with _lock:
                for ip in ips:
                    _active[ip] = now
                for ip in [k for k, v in _active.items() if now - v > ACTIVE_TTL]:
                    del _active[ip]
            print(f"poll: {len(ips)} active peers, {sum(1 for ip in ips if _geo.get(ip))} located", flush=True)
        except Exception as e:
            print("poll error:", e, flush=True)
        time.sleep(POLL_INTERVAL)


def render():
    with _lock:
        ips = list(_active)
    out = ["# HELP fukuii_peer_location Active peer with geo (value always 1).",
           "# TYPE fukuii_peer_location gauge"]
    by_cc, located = {}, 0

    def esc(s):
        return str(s).replace("\\", "\\\\").replace('"', '\\"')
    for ip in ips:
        g = _geo.get(ip)
        if not g:
            continue
        located += 1
        out.append(
            f'fukuii_peer_location{{ip="{ip}",country="{esc(g["country"])}",cc="{g["cc"]}",'
            f'city="{esc(g["city"])}",latitude="{g["lat"]}",longitude="{g["lon"]}"}} 1')
        key = (g["country"], g["cc"])
        by_cc[key] = by_cc.get(key, 0) + 1
    out += ["# HELP fukuii_peers_by_country Active peer count per country.",
            "# TYPE fukuii_peers_by_country gauge"]
    for (country, cc), n in sorted(by_cc.items(), key=lambda x: -x[1]):
        out.append(f'fukuii_peers_by_country{{country="{esc(country)}",cc="{cc}"}} {n}')
    out += [f"fukuii_peer_total {len(ips)}", f"fukuii_peer_located {located}"]
    return ("\n".join(out) + "\n").encode()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/metrics":
            body = render()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    load_cache()
    threading.Thread(target=poller, daemon=True).start()
    print(f"peer_geo_exporter on :{PORT} (cache: {CACHE})", flush=True)
    HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
