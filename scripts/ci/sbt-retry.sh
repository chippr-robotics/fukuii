#!/usr/bin/env bash
# Run sbt, and run it again only when it failed to DOWNLOAD something (B-417, B-174).
#
# CI fetches sbt itself, the build's dependencies and tools such as scalafix-cli from Maven Central and
# repo.scala-sbt.org on a cold or partly cold cache. Those hosts sometimes answer 429 (rate limited), 5xx or reset the
# connection, and sbt's launcher and coursier then fail the step before any code is compiled or tested:
#   - #1958 smoke-genesis/smoke-network: CantDownloadModule org.scala-sbt:sbt:1.13.0
#   - #2029 smoke-network: HTTP 429 from repo1.maven.org for the sbt 1.13.0 pom
#   - #1948, #1978 Test and Build: Failed to fetch scalafix-cli_3.9.0:0.14.9
#
# A failure is retried only when the log shows a download error AND no compile or test failure, so a real
# compile, format or test failure is never retried or hidden: it fails on the first run, with sbt's own exit code.
#
# Usage: scripts/ci/sbt-retry.sh [sbt arguments...]
# Env:   SBT_RETRY_ATTEMPTS (default 4), SBT_RETRY_DELAY (seconds, default 20; doubles each retry), SBT (default sbt).
set -uo pipefail

attempts="${SBT_RETRY_ATTEMPTS:-4}"
delay="${SBT_RETRY_DELAY:-20}"
sbt_cmd="${SBT:-sbt}"
log="$(mktemp -t sbt-retry.XXXXXX)"
trap 'rm -f "$log"' EXIT

# What a failed download looks like from sbt's launcher, coursier and Ivy.
fetch_re='CantDownloadModule|could not retrieve sbt|download error|Failed to fetch|Error downloading|DownloadingArtifacts|ResolutionError|Server returned HTTP response code: (429|5[0-9][0-9])|Connection reset|Read timed out|connect timed out|UnknownHostException'
# What a real failure looks like; any of these and the run is not retried.
real_re='\[error\] -- .*Error:|\[error\] .*\.scala:[0-9]+:|Compilation failed|\*\*\* FAILED \*\*\*|Failed tests:|Tests: succeeded [0-9]+, failed [1-9]|scalafmt.*not formatted|--check failed|error: unformatted'

for ((i = 1; i <= attempts; i++)); do
  "$sbt_cmd" "$@" 2>&1 | tee "$log"
  status=${PIPESTATUS[0]}
  if [ "$status" -eq 0 ]; then
    exit 0
  fi
  if ! grep -Eq "$fetch_re" "$log" || grep -Eq "$real_re" "$log"; then
    exit "$status"
  fi
  if [ "$i" -lt "$attempts" ]; then
    echo "::warning::sbt $* failed to download a dependency (attempt $i of $attempts); retrying in ${delay}s"
    sleep "$delay"
    delay=$((delay * 2))
  fi
done
echo "::error::sbt $* failed to download a dependency on all $attempts attempts"
exit "$status"
