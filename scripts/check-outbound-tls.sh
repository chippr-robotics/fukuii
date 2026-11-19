#!/usr/bin/env bash
# Spec 030 FR-045 (slice 7, research D-10): the allow-list ratchet of the places that make TLS for an outbound HTTPS
# client, or open an outbound connection, outside the outbound factory (tls/OutboundTls).
#
# Every outbound HTTPS client gets its TLS from the factory, keyed by a profile: host names checked, trust named, a
# client certificate when the profile has one, no fallback to the JDK's trust on an error, no redirect from https to
# http. Nothing in the types stops a new client from building its own SSLContext or opening a URL, so this check
# counts every such construct under src/main/scala and compares the counts with scripts/outbound-tls-allowlist.txt,
# where each line says why that use is allowed. It fails as scripts/check-head-writers.sh does:
#   - a use in a file that has no line for that construct;
#   - more uses than the line allows, or fewer, or a line whose file is gone;
#   - a line with no reason, an unknown construct, a count that is not a positive number, a line given twice.
# A line whose first characters are `//`, `/*` or `*` is a comment and is not counted.
#
# Usage: check-outbound-tls.sh [--root DIR] [--allowlist FILE] [--inventory]
#   --root DIR        the tree to check (default: this repository)
#   --allowlist FILE  the list to check it against (default: DIR/scripts/outbound-tls-allowlist.txt)
#   --inventory       print `construct count file` for every use in the tree, and check nothing
# Exit: 0 as listed; 1 the tree and the list differ; 2 the check could not run.
#
# Tested by src/test/scala/com/chipprbots/ethereum/scripts/OutboundTlsRatchetSpec.scala.
set -euo pipefail
export LC_ALL=C

# The constructs, by the name the allow-list gives them. Extended regular expressions.
#
#   ssl-context     an SSLContext made by hand
#   https-context   a Pekko HTTPS connection context
#   jdk-client      a JDK HttpClient
#   url-open        a URL opened directly (URL.openStream, URL.openConnection)
#   pekko-request   a Pekko client request, which takes the TLS of the context it is given
NAMES="ssl-context https-context jdk-client url-open pekko-request"

regex_of() {
  case "$1" in
    ssl-context)   echo '\bSSLContext\.getInstance\(' ;;
    https-context) echo '\bConnectionContext\.https(Client|Server)?\b' ;;
    jdk-client)    echo '\bHttpClient\.(newBuilder|newHttpClient)\(' ;;
    url-open)      echo '\.(openStream|openConnection)\(' ;;
    pekko-request) echo '\bHttp\([^)]*\)\.singleRequest\(' ;;
    *) return 1 ;;
  esac
}

scope_of() { echo ''; }

COMMENT='^[[:space:]]*(//|/\*|\*)'

usage() { sed -n '/^# Usage:/,/^# Exit:/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; }

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
allowlist=""
inventory=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --root)      [ "$#" -ge 2 ] || { usage; exit 2; }; root="$2"; shift 2 ;;
    --allowlist) [ "$#" -ge 2 ] || { usage; exit 2; }; allowlist="$2"; shift 2 ;;
    --inventory) inventory=1; shift ;;
    *) usage; exit 2 ;;
  esac
done
[ -n "$allowlist" ] || allowlist="$root/scripts/outbound-tls-allowlist.txt"

sources="$root/src/main/scala"
if [ ! -d "$sources" ]; then
  echo "outbound-tls: no main sources at $sources: nothing would be checked" >&2
  exit 2
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# The uses of one entry point in one file, comment lines aside: `line:text`.
sites() { # regex file
  { grep -nE -e "$1" "$2" || true; } | { grep -vE "^[0-9]+:${COMMENT#^}" || true; }
}

# found: `name count file`, in the order of NAMES and then by file.
: > "$work/found"
for name in $NAMES; do
  regex="$(regex_of "$name")"
  scope="$(scope_of "$name")"
  { grep -rlE --include='*.scala' -e "$regex" "$sources" || true; } | sort | while IFS= read -r file; do
    if [ -n "$scope" ] && ! grep -qE -e "$scope" "$file"; then continue; fi
    count="$({ grep -vE "$COMMENT" "$file" || true; } | { grep -oE -e "$regex" || true; } | wc -l | tr -d ' ')"
    [ "$count" -gt 0 ] && echo "$name $count ${file#"$root"/}" >> "$work/found"
    true
  done
done

if [ "$inventory" -eq 1 ]; then
  cat "$work/found"
  exit 0
fi

if [ ! -f "$allowlist" ]; then
  echo "outbound-tls: no allow-list at $allowlist" >&2
  exit 2
fi

# listed: the allow-list as `name count file`; what cannot be read goes to `problems`.
awk -v names="$NAMES" -v problems="$work/problems" '
  BEGIN { n = split(names, a, " "); for (i = 1; i <= n; i++) known[a[i]] = 1 }
  /^[[:space:]]*(#|$)/ { next }
  {
    where = "line " FNR ": " $0
    if (!($1 in known)) { print "UNREADABLE: `" $1 "` is not an entry point (" where ")" > problems; next }
    if ($2 !~ /^[1-9][0-9]*$/) { print "UNREADABLE: `" $2 "` is not a count above zero (" where ")" > problems; next }
    if (NF < 3) { print "UNREADABLE: no file (" where ")" > problems; next }
    if (NF < 4) { print "NO REASON: every line says why the use is there (" where ")" > problems; next }
    if ($4 !~ /^(FACTORY|USES|PLAIN|PROBE|S8)$/) { print "NO VERDICT: a reason starts with FACTORY, USES, PLAIN, PROBE or S8 (" where ")" > problems; next }
    if (($1, $3) in seen) { print "LISTED TWICE: " $1 " in " $3 " (" where ")" > problems; next }
    seen[$1, $3] = 1
    print $1, $2, $3
  }
' "$allowlist" > "$work/listed"
touch "$work/problems"

# verdicts: `kind name file found allowed` for every difference between the tree and the list.
# (By file name and not by `FNR == NR`, which is also true of the second file when the list is empty.)
awk '
  FILENAME == ARGV[1] { allowed[$1, $3] = $2; next }
  {
    met[$1, $3] = 1
    if (!(($1, $3) in allowed)) print "unlisted", $1, $3, $2, 0
    else if ($2 > allowed[$1, $3] + 0) print "over", $1, $3, $2, allowed[$1, $3]
    else if ($2 < allowed[$1, $3] + 0) print "under", $1, $3, $2, allowed[$1, $3]
  }
  END {
    for (key in allowed) if (!(key in met)) { split(key, k, SUBSEP); print "gone", k[1], k[2], 0, allowed[key] }
  }
' "$work/listed" "$work/found" | sort > "$work/verdicts"

failed=0
while IFS= read -r problem; do
  echo "outbound-tls: $problem"
  failed=1
done < "$work/problems"

while read -r kind name file found allowed; do
  failed=1
  case "$kind" in
    unlisted)
      echo "outbound-tls: NOT LISTED: $name in $file ($found)"
      sites "$(regex_of "$name")" "$root/$file" | sed "s|^|  $file:|"
      echo "  if it has to stay, the line to add:  $name $found $file <FACTORY, USES, PLAIN, PROBE or S8, then why>"
      ;;
    over)
      echo "outbound-tls: MORE THAN LISTED: $name in $file: $found found, $allowed allowed"
      sites "$(regex_of "$name")" "$root/$file" | sed "s|^|  $file:|"
      echo "  if the new use has to stay, the line's count becomes:  $name $found $file ..."
      ;;
    under)
      echo "outbound-tls: FEWER THAN LISTED: $name in $file: $found found, $allowed allowed"
      echo "  lower the line's count:  $name $found $file ..."
      ;;
    gone)
      echo "outbound-tls: NO LONGER USED: $name in $file ($allowed allowed)"
      echo "  remove the line:  $name $allowed $file ..."
      ;;
  esac
done < "$work/verdicts"

if [ "$failed" -ne 0 ]; then
  cat >&2 <<EOF

TLS for an outbound client is made somewhere the allow-list does not say, or the list says more than is there. A NEW
client: take its TLS from tls/OutboundTls (a profile named in config, \`system\` by default; spec 030 FR-040, FR-044),
or, if it makes no TLS connection, add a line to ${allowlist#"$root"/} that says so (PLAIN). A use that is GONE: lower
its count or remove its line, in the same change.
EOF
  exit 1
fi

echo "outbound tls: $(wc -l < "$work/listed" | tr -d ' ') lines and $(awk '{ s += $2 } END { print s + 0 }' "$work/listed") uses, all as listed"
