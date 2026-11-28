#!/usr/bin/env bash
# Generate TLS material for fukuii's listeners: a self-signed server
# certificate, or a test CA with the server and client certificates it issues
# (mutual TLS, spec 030).
#
# Every run makes a fresh key pair and a fresh random password. Nothing this
# script writes belongs in version control. See docs/runbooks/tls-operations.md.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: gen-cert.sh [ca|server|client] [options]

Without a command, generate a self-signed TLS server certificate (the form
earlier releases wrote). With a command:

  ca      a certificate authority: <name>.p12 holds its key (keep it offline),
          <name>.crt its certificate, and <name>-truststore.p12 a truststore
          with the CA alone, for a listener's tls profile `truststore`
  server  a server certificate issued by the CA given with --ca
  client  a client certificate issued by the CA given with --ca, for a
          listener with client-auth = want or need

Every keystore is written as:
  <name>.p12       PKCS#12 keystore holding the private key and certificate (mode 600)
  <name>.password  the keystore password, random for every run (mode 600)
  <name>.crt       the certificate in PEM (mode 644)

Options:
  -s, --san LIST     subjectAltName entries, comma separated: DNS:<name>, IP:<address>,
                     or (client only) URI:<uri>
                     (server default: DNS:localhost,IP:127.0.0.1; client default: none)
  -c, --cn NAME      subject common name (server default: the first --san value;
                     ca default: "fukuii test CA"; client: required)
  -d, --days N       validity in days (default: 365; ca: 1825)
  -o, --out-dir DIR  output directory (default: the directory holding this script)
  -n, --name NAME    file name stem (default: fukuii-rpc; ca: fukuii-ca;
                     client: fukuii-client-<cn>)
      --ca STEM      server and client: the CA, as the path of its files without the
                     extension, e.g. tls/fukuii-ca (reads .p12, .password and .crt)
  -f, --force        replace existing output files (certificate rotation)
  -h, --help         show this help

Examples, a CA, a server certificate and a client certificate:
  tls/gen-cert.sh ca
  tls/gen-cert.sh server --ca tls/fukuii-ca --san DNS:rpc.example.org,IP:10.0.0.5
  tls/gen-cert.sh client --ca tls/fukuii-ca --cn monitor

Requires openssl; `ca` also needs keytool (shipped with the JDK) for the truststore.
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

COMMAND="self-signed"
case "${1:-}" in
  ca | server | client)
    COMMAND="$1"
    shift
    ;;
esac

SAN=""
SAN_SET=0
CN=""
DAYS=""
OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAME=""
CA_STEM=""
FORCE=0

while [ $# -gt 0 ]; do
  case "$1" in
    -s | --san)
      need_value "$@"
      SAN="$2"
      SAN_SET=1
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
    --ca)
      need_value "$@"
      CA_STEM="$2"
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

# Defaults per command.
case "$COMMAND" in
  self-signed | server)
    [ "$SAN_SET" -eq 1 ] || SAN="DNS:localhost,IP:127.0.0.1"
    [ -n "$NAME" ] || NAME="fukuii-rpc"
    [ -n "$DAYS" ] || DAYS=365
    ;;
  ca)
    [ "$SAN_SET" -eq 0 ] || die "ca takes no --san"
    [ -n "$CN" ] || CN="fukuii test CA"
    [ -n "$NAME" ] || NAME="fukuii-ca"
    [ -n "$DAYS" ] || DAYS=1825
    ;;
  client)
    [ -n "$CN" ] || die "client needs --cn, the name the node's identity rules match"
    [ -n "$NAME" ] || NAME="fukuii-client-$CN"
    [ -n "$DAYS" ] || DAYS=365
    ;;
esac

case "$COMMAND" in
  server | client) [ -n "$CA_STEM" ] || die "$COMMAND needs --ca <stem of the CA's files>, made by 'gen-cert.sh ca'" ;;
  *) [ -z "$CA_STEM" ] || die "--ca is for the server and client commands" ;;
esac

# The values below are written into an OpenSSL config file, so they are held to
# plain host names, addresses, URIs and file names.
SAN_ENTRIES=()
if [ -n "$SAN" ]; then
  IFS=',' read -r -a SAN_ENTRIES <<<"$SAN"
fi
if [ "$COMMAND" != "client" ] && [ "$COMMAND" != "ca" ]; then
  [ "${#SAN_ENTRIES[@]}" -gt 0 ] || die "--san needs at least one entry"
fi
for entry in "${SAN_ENTRIES[@]}"; do
  if [[ "$entry" =~ ^DNS:[A-Za-z0-9*._-]+$ || "$entry" =~ ^IP:[0-9A-Fa-f.:]+$ ]]; then
    continue
  fi
  if [ "$COMMAND" = "client" ] && [[ "$entry" =~ ^URI:[A-Za-z][A-Za-z0-9+.-]*:[A-Za-z0-9/._~%+:@-]+$ ]]; then
    continue
  fi
  if [ "$COMMAND" = "client" ]; then
    die "--san entry '$entry' is not DNS:<name>, IP:<address> or URI:<uri>"
  fi
  die "--san entry '$entry' is not DNS:<name> or IP:<address>"
done
if [ -z "$CN" ]; then
  CN="${SAN_ENTRIES[0]#*:}"
fi
cn_pattern='^[A-Za-z0-9*._: -]{1,64}$'
[[ "$CN" =~ $cn_pattern ]] || die "--cn '$CN' must be 1-64 letters, digits, spaces or . _ : * -"
[[ "$DAYS" =~ ^[1-9][0-9]{0,4}$ ]] || die "--days must be a whole number of days, 1-99999"
[[ "$NAME" =~ ^[A-Za-z0-9._-]+$ ]] || die "--name may use letters, digits, '.', '_' and '-'"

if [ -n "$CA_STEM" ]; then
  for ext in p12 password crt; do
    [ -r "$CA_STEM.$ext" ] || die "the CA file $CA_STEM.$ext is missing or unreadable"
  done
fi

if [ "$COMMAND" = "ca" ]; then
  command -v keytool >/dev/null 2>&1 || die "keytool (from the JDK) is required for the CA's truststore"
fi

mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
P12="$OUT_DIR/$NAME.p12"
PASSWORD_FILE="$OUT_DIR/$NAME.password"
CRT="$OUT_DIR/$NAME.crt"
TRUST_P12="$OUT_DIR/$NAME-truststore.p12"
TRUST_PASSWORD_FILE="$OUT_DIR/$NAME-truststore.password"

OUTPUTS=("$P12" "$PASSWORD_FILE" "$CRT")
if [ "$COMMAND" = "ca" ]; then
  OUTPUTS+=("$TRUST_P12" "$TRUST_PASSWORD_FILE")
fi
if [ "$FORCE" -ne 1 ]; then
  for f in "${OUTPUTS[@]}"; do
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

SAN_LINE=""
if [ "${#SAN_ENTRIES[@]}" -gt 0 ]; then
  SAN_LINE="subjectAltName = $(
    IFS=,
    echo "${SAN_ENTRIES[*]}"
  )"
fi

case "$COMMAND" in
  ca)
    EXTENSIONS="basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign"
    ;;
  self-signed | server)
    EXTENSIONS="basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth"
    ;;
  client)
    EXTENSIONS="basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth"
    ;;
esac

cat >"$WORK/openssl.cnf" <<EOF
[req]
distinguished_name = dn
prompt = no
default_md = sha256

[dn]
CN = $CN

[ext]
$EXTENSIONS
$SAN_LINE
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
EOF

if [ "$COMMAND" = "self-signed" ] || [ "$COMMAND" = "ca" ]; then
  openssl req -x509 -newkey rsa:4096 -sha256 -days "$DAYS" \
    -config "$WORK/openssl.cnf" -extensions ext \
    -keyout "$WORK/key.pem" -passout "file:$WORK/password" \
    -out "$WORK/cert.pem" 2>/dev/null
  CHAIN_ARGS=()
else
  # The CA's key comes out of its keystore into the work directory, encrypted with
  # a password of this run only, and is removed with it.
  openssl rand -hex 24 >"$WORK/ca-work-password"
  openssl pkcs12 -in "$CA_STEM.p12" -passin "file:$CA_STEM.password" -nocerts \
    -passout "file:$WORK/ca-work-password" -out "$WORK/ca-key.pem" 2>/dev/null ||
    die "cannot open $CA_STEM.p12 with $CA_STEM.password"
  openssl req -new -newkey rsa:4096 -sha256 \
    -config "$WORK/openssl.cnf" \
    -keyout "$WORK/key.pem" -passout "file:$WORK/password" \
    -out "$WORK/req.csr" 2>/dev/null
  openssl x509 -req -sha256 -days "$DAYS" -in "$WORK/req.csr" \
    -CA "$CA_STEM.crt" -CAkey "$WORK/ca-key.pem" -passin "file:$WORK/ca-work-password" \
    -CAcreateserial -CAserial "$WORK/ca.srl" \
    -extfile "$WORK/openssl.cnf" -extensions ext \
    -out "$WORK/cert.pem" 2>/dev/null
  openssl verify -CAfile "$CA_STEM.crt" "$WORK/cert.pem" >/dev/null || die "the issued certificate does not verify"
  CHAIN_ARGS=(-certfile "$CA_STEM.crt")
fi

openssl pkcs12 -export -name "$NAME" \
  -inkey "$WORK/key.pem" -passin "file:$WORK/password.in" \
  -in "$WORK/cert.pem" "${CHAIN_ARGS[@]}" \
  -keypbe AES-256-CBC -certpbe AES-256-CBC -macalg sha256 \
  -out "$WORK/keystore.p12" -passout "file:$WORK/password"

# Read it back with the password before installing anything.
openssl pkcs12 -in "$WORK/keystore.p12" -passin "file:$WORK/password" -noout

if [ "$COMMAND" = "ca" ]; then
  # A truststore holding the CA's certificate as a trusted entry, which is what the
  # JDK reads from a PKCS#12 truststore; its own random password.
  openssl rand -hex 24 >"$WORK/trust-password"
  keytool -importcert -noprompt -alias "$NAME" -file "$WORK/cert.pem" \
    -keystore "$WORK/truststore.p12" -storetype pkcs12 \
    -storepass:file "$WORK/trust-password" >/dev/null 2>&1 ||
    die "keytool could not write the truststore"
  mv -f "$WORK/truststore.p12" "$TRUST_P12"
  mv -f "$WORK/trust-password" "$TRUST_PASSWORD_FILE"
  chmod 600 "$TRUST_PASSWORD_FILE"
  chmod 644 "$TRUST_P12"
fi

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
if [ "$COMMAND" = "ca" ]; then
  echo "  $TRUST_P12  (truststore with the CA alone)"
  echo "  $TRUST_PASSWORD_FILE  (truststore password, mode 600)"
fi
echo
openssl x509 -in "$CRT" -noout -subject -enddate -fingerprint -sha256
[ -z "$SAN_LINE" ] || echo "$SAN_LINE"

case "$COMMAND" in
  ca)
    cat <<EOF

Keep $P12 and its password offline: they issue certificates. A listener that
asks for client certificates trusts this CA through the truststore:

  fukuii.tls.profiles.rpc.truststore {
    path = "$TRUST_P12"
    type = "pkcs12"
    password-file = "$TRUST_PASSWORD_FILE"
  }

Issue certificates with:
  $0 server --ca ${P12%.p12} --san DNS:<host>
  $0 client --ca ${P12%.p12} --cn <client name>
EOF
    ;;
  self-signed | server)
    cat <<EOF

Point the node at the keystore:

  fukuii.tls.profiles.rpc.keystore {
    path = "$P12"
    type = "pkcs12"
    password-file = "$PASSWORD_FILE"
  }
  fukuii.network.rpc.http.tls { enabled = true, profile = "rpc" }

Clients trust the certificate (self-signed) or the CA, for example:
  curl --cacert "$CRT" https://localhost:8546
EOF
    ;;
  client)
    cat <<EOF

The client presents this keystore, for example with curl:
  curl --cert-type P12 --cert "$P12:\$(cat $PASSWORD_FILE)" --cacert <server CA or certificate> https://<host>:8546
EOF
    ;;
esac
