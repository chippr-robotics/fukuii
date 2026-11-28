#!/bin/bash
# Container healthcheck for Fukuii (spec 030 S11, B-149).
#
# The process must be running. The JSON-RPC check then asks web3_clientVersion of FUKUII_HEALTH_URL, which defaults to
# http://localhost:8546, the base config's port. Set it to https://... for a listener with tls.enabled, and give curl
# what that listener needs:
#   FUKUII_HEALTH_CACERT  PEM CA that issued the listener's certificate (its host name must match the URL's)
#   FUKUII_HEALTH_CERT    PEM client certificate, for a listener with client-auth = "need"
#   FUKUII_HEALTH_KEY     its PEM private key
# FUKUII_HEALTH_RPC = optional (default) reports a listener that does not answer and stays healthy, so a node with
# RPC off is not killed; = required makes it unhealthy.
set -u

if ! pgrep -f "com.chipprbots.ethereum.App" > /dev/null; then
  echo "Fukuii process is not running"
  exit 1
fi

url="${FUKUII_HEALTH_URL:-http://localhost:8546}"
mode="${FUKUII_HEALTH_RPC:-optional}"
args=(-sf --max-time 8 -X POST -H "Content-Type: application/json"
  --data '{"jsonrpc":"2.0","method":"web3_clientVersion","params":[],"id":1}')
[ -n "${FUKUII_HEALTH_CACERT:-}" ] && args+=(--cacert "$FUKUII_HEALTH_CACERT")
[ -n "${FUKUII_HEALTH_CERT:-}" ] && args+=(--cert "$FUKUII_HEALTH_CERT")
[ -n "${FUKUII_HEALTH_KEY:-}" ] && args+=(--key "$FUKUII_HEALTH_KEY")

if curl "${args[@]}" "$url" > /dev/null 2>&1; then
  echo "Fukuii is healthy - RPC responding at $url"
  exit 0
fi

if [ "$mode" = "required" ]; then
  echo "Fukuii RPC is not responding at $url"
  exit 1
fi
echo "Fukuii process is running; RPC not responding at $url (FUKUII_HEALTH_RPC=optional)"
exit 0
