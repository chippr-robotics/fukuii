#!/usr/bin/env bash
# Generate the TLS server certificate for fukuii's JSON-RPC HTTPS endpoint.
#
# Run it once per deployment: every node gets its own key pair and its own
# random keystore password. Nothing this script writes belongs in version
# control. See docs/runbooks/tls-operations.md.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: gen-cert.sh [options]

Generate a self-signed TLS server certificate for the fukuii JSON-RPC HTTPS
endpoint (network.rpc.http.mode = "https"). Run it once per deployment.

Writes three files to the output directory:
  <name>.p12       PKCS#12 keystore holding the private key and certificate (mode 600)
  <name>.password  the keystore password, random for every run (mode 600)
  <name>.crt       the certificate in PEM, for clients to trust (mode 644)

The certificate is a server certificate: basicConstraints CA:FALSE, keyUsage
digitalSignature + keyEncipherment, extendedKeyUsage serverAuth, and the
subjectAltName entries given with --san. Clients check the host name they
connect to against those entries, so list every name and address they use.

Options:
  -s, --san LIST     subjectAltName entries, comma separated, DNS:<name> or IP:<address>
                     (default: DNS:localhost,IP:127.0.0.1)
  -c, --cn NAME      subject common name (default: the value of the first --san entry)
  -d, --days N       validity in days (default: 365)
  -o, --out-dir DIR  output directory (default: the directory holding this script)
  -n, --name NAME    file name stem (default: fukuii-rpc)
  -f, --force        replace existing output files (certificate rotation)
  -h, --help         show this help

Example, for a node that clients reach as rpc.example.org and 10.0.0.5:
  tls/gen-cert.sh --san DNS:rpc.example.org,IP:10.0.0.5

Requires openssl.
USAGE
}

die() {
  echo "gen-cert.sh: $*" >&2
  exit 1
}

need_value() {
  if [ $# -lt 2 ] || [ -z "$2" ]; then
    die "$1 needs a value (see --help)"
  fi
}

SAN="DNS:localhost,IP:127.0.0.1"
CN=""
DAYS=365
OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAME="fukuii-rpc"
FORCE=0

while [ $# -gt 0 ]; do
  case "$1" in
    -s | --san)
      need_value "$@"
      SAN="$2"
      shift 2
      ;;
    -c | --cn)
      need_value "$@"
      CN="$2"
      shift 2
      ;;
    -d | --days)
      need_value "$@"
      DAYS="$2"
      shift 2
      ;;
    -o | --out-dir)
      need_value "$@"
      OUT_DIR="$2"
      shift 2
      ;;
    -n | --name)
      need_value "$@"
      NAME="$2"
      shift 2
      ;;
    -f | --force)
      FORCE=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown option: $1"
      ;;
  esac
done

command -v openssl >/dev/null 2>&1 || die "openssl is required but not installed"

# The values below are written into an OpenSSL config file, so they are held to
# plain host names, addresses and file names.
IFS=',' read -r -a SAN_ENTRIES <<<"$SAN"
[ "${#SAN_ENTRIES[@]}" -gt 0 ] || die "--san needs at least one entry"
for entry in "${SAN_ENTRIES[@]}"; do
  if ! [[ "$entry" =~ ^DNS:[A-Za-z0-9*._-]+$ || "$entry" =~ ^IP:[0-9A-Fa-f.:]+$ ]]; then
    die "--san entry '$entry' is not DNS:<name> or IP:<address>"
  fi
done
if [ -z "$CN" ]; then
  CN="${SAN_ENTRIES[0]#*:}"
fi
cn_pattern='^[A-Za-z0-9*._: -]{1,64}$'
[[ "$CN" =~ $cn_pattern ]] || die "--cn '$CN' must be 1-64 letters, digits, spaces or . _ : * -"
[[ "$DAYS" =~ ^[1-9][0-9]{0,4}$ ]] || die "--days must be a whole number of days, 1-99999"
[[ "$NAME" =~ ^[A-Za-z0-9._-]+$ ]] || die "--name may use letters, digits, '.', '_' and '-'"

mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
P12="$OUT_DIR/$NAME.p12"
PASSWORD_FILE="$OUT_DIR/$NAME.password"
CRT="$OUT_DIR/$NAME.crt"

if [ "$FORCE" -ne 1 ]; then
  for f in "$P12" "$PASSWORD_FILE" "$CRT"; do
    if [ -e "$f" ]; then
      die "$f already exists; move it aside or pass --force"
    fi
  done
fi

# Everything below is created owner-only. The private key exists only inside the
# keystore and, transiently, encrypted in the work directory.
umask 077
WORK="$(mktemp -d "$OUT_DIR/.gen-cert.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# A fresh random password per run. Two copies, because OpenSSL reads a second
# line when -passin and -passout name the same file.
openssl rand -hex 24 >"$WORK/password"
cp "$WORK/password" "$WORK/password.in"

cat >"$WORK/openssl.cnf" <<EOF
[req]
distinguished_name = dn
x509_extensions = server_cert
prompt = no
default_md = sha256

[dn]
CN = $CN

[server_cert]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = $SAN
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
EOF

openssl req -x509 -newkey rsa:4096 -sha256 -days "$DAYS" \
  -config "$WORK/openssl.cnf" -extensions server_cert \
  -keyout "$WORK/key.pem" -passout "file:$WORK/password" \
  -out "$WORK/cert.pem"

openssl pkcs12 -export -name "$NAME" \
  -inkey "$WORK/key.pem" -passin "file:$WORK/password.in" \
  -in "$WORK/cert.pem" \
  -keypbe AES-256-CBC -certpbe AES-256-CBC -macalg sha256 \
  -out "$WORK/keystore.p12" -passout "file:$WORK/password"

# Read it back with the password before installing anything.
openssl pkcs12 -in "$WORK/keystore.p12" -passin "file:$WORK/password" -noout

mv -f "$WORK/keystore.p12" "$P12"
mv -f "$WORK/password" "$PASSWORD_FILE"
mv -f "$WORK/cert.pem" "$CRT"
chmod 600 "$P12" "$PASSWORD_FILE"
chmod 644 "$CRT"

echo
echo "Wrote:"
echo "  $P12  (keystore, mode 600)"
echo "  $PASSWORD_FILE  (keystore password, mode 600)"
echo "  $CRT  (certificate, mode 644)"
echo
openssl x509 -in "$CRT" -noout -subject -enddate -fingerprint -sha256
echo "subjectAltName=$SAN"
cat <<EOF

Point the node at the keystore:

  fukuii.network.rpc.http {
    mode = "https"
    certificate {
      keystore-path = "$P12"
      keystore-type = "pkcs12"
      password-file = "$PASSWORD_FILE"
    }
  }

Clients trust the certificate itself, for example:
  curl --cacert "$CRT" https://localhost:8546
EOF
