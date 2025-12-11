#!/usr/bin/env bash
# Deprecated helper preserved for compatibility.
# Cirith Ungol is now managed via ops/tools/fukuii-cli.sh cirith-ungol ...

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}" )" && pwd)"
CLI="$SCRIPT_DIR/../tools/fukuii-cli.sh"

if [[ ! -x "$CLI" ]]; then
    echo "Error: Unable to locate fukuii-cli at $CLI" >&2
    exit 1
fi

ACTION="${1:-start}"
if [[ $# -gt 0 ]]; then
    shift
fi

case "$ACTION" in
    start|up)
        MODE="${1:-fast}"
        exec "$CLI" cirith-ungol start "$MODE"
        ;;
    stop|down)
        exec "$CLI" cirith-ungol stop
        ;;
    restart)
        MODE="${1:-fast}"
        exec "$CLI" cirith-ungol restart "$MODE"
        ;;
    logs)
        exec "$CLI" cirith-ungol logs "$@"
        ;;
    collect-logs|capture)
        exec "$CLI" cirith-ungol collect-logs "$@"
        ;;
    smoketest)
        MODE="${1:-fast}"
        exec "$CLI" cirith-ungol smoketest "$MODE"
        ;;
    status)
        exec "$CLI" cirith-ungol status
        ;;
    clean)
        exec "$CLI" cirith-ungol clean
        ;;
    help|--help|-h)
        exec "$CLI" cirith-ungol help
        ;;
    *)
        echo "Unknown command '$ACTION'. Use fukuii-cli cirith-ungol help for options." >&2
        exit 1
        ;;
esac
