#!/bin/bash
set -euo pipefail

for cmd in pwgen keytool; do
  if ! command -v "$cmd" &>/dev/null; then
    echo "ERROR: $cmd is required but not installed" >&2
    exit 1
  fi
done

cd "$(dirname "${BASH_SOURCE[0]}")"

export PW
PW="$(pwgen -Bs 10 1)"
echo "$PW" > ./password

rm -f ./fukuiiCA.p12

keytool -genkeypair \
  -keystore fukuiiCA.p12 \
  -storetype PKCS12 \
  -dname "CN=127.0.0.1" \
  -ext "san=ip:127.0.0.1,dns:localhost" \
  -keypass:env PW \
  -storepass:env PW \
  -keyalg RSA \
  -keysize 4096 \
  -validity 9999 \
  -ext KeyUsage:critical="keyCertSign" \
  -ext BasicConstraints:critical="ca:true"
