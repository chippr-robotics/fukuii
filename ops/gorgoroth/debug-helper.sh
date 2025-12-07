#!/bin/bash
# Debug helper script for Gorgoroth 3-node network

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

case "$1" in
    restart)
        echo "🔄 Restarting 3-node network with debug configuration..."
        ./deploy.sh down 3nodes
        sleep 2
        ./deploy.sh up 3nodes
        echo "✅ Network restarted. Waiting 30 seconds for initialization..."
        sleep 30
        ./deploy.sh logs 3nodes
        ;;
    
    collect)
        OUTPUT_DIR="${2:-./debug-logs-$(date +%Y%m%d-%H%M%S)}"
        echo "📦 Collecting logs to $OUTPUT_DIR..."
        ./collect-logs.sh 3nodes "$OUTPUT_DIR"
        echo "✅ Logs collected to $OUTPUT_DIR"
        ;;
    
    analyze)
        LOG_DIR="${2:-./debug-logs}"
        echo "🔍 Analyzing logs in $LOG_DIR..."
        echo ""
        echo "=== Peer Connection Attempts ==="
        grep -h "RLPx.*Auth handshake SUCCESS" "$LOG_DIR"/*.log 2>/dev/null | wc -l || echo "0"
        echo ""
        echo "=== Handshake Failures ==="
        grep -h "Stopping Connection.*failed" "$LOG_DIR"/*.log 2>/dev/null | head -5 || echo "None found"
        echo ""
        echo "=== Blacklist Reasons ==="
        grep -h "Blacklisting peer" "$LOG_DIR"/*.log 2>/dev/null | head -10 || echo "None found"
        echo ""
        echo "=== Fork ID Validation ==="
        grep -h "FORKID_VALIDATION" "$LOG_DIR"/*.log 2>/dev/null | head -10 || echo "None found"
        echo ""
        echo "=== Status Exchange ==="
        grep -h "STATUS_EXCHANGE" "$LOG_DIR"/*.log 2>/dev/null | head -10 || echo "None found"
        echo ""
        echo "=== Successful Handshakes ==="
        grep -h "PEER_HANDSHAKE_SUCCESS" "$LOG_DIR"/*.log 2>/dev/null || echo "None found"
        ;;
    
    watch-connections)
        echo "👀 Watching peer connections (Ctrl+C to stop)..."
        docker compose -f docker-compose-3nodes.yml logs -f 2>&1 | \
            grep --line-buffered -E "(RLPx|HANDSHAKE|Blacklisting|PEER_|STATUS_EXCHANGE)"
        ;;
    
    check-static-nodes)
        echo "🔍 Checking static-nodes.json configuration..."
        echo ""
        echo "Node 1:"
        cat conf/node1/static-nodes.json
        echo ""
        echo "Node 2:"
        cat conf/node2/static-nodes.json
        echo ""
        echo "Node 3:"
        cat conf/node3/static-nodes.json
        ;;
    
    health)
        echo "🏥 Checking node health..."
        for port in 8546 8548 8550; do
            echo -n "Port $port: "
            curl -s http://localhost:$port/health || echo "FAILED"
        done
        ;;
    
    *)
        echo "Fukuii Gorgoroth Debug Helper"
        echo ""
        echo "Usage: $0 <command> [options]"
        echo ""
        echo "Commands:"
        echo "  restart              - Restart the 3-node network with debug config"
        echo "  collect [dir]        - Collect logs to specified directory (default: debug-logs-<timestamp>)"
        echo "  analyze [dir]        - Analyze logs in directory (default: ./debug-logs)"
        echo "  watch-connections    - Watch peer connection events in real-time"
        echo "  check-static-nodes   - Display static-nodes.json for all nodes"
        echo "  health               - Check health of all nodes"
        echo ""
        echo "Examples:"
        echo "  $0 restart                           # Restart network and tail logs"
        echo "  $0 collect ./my-debug-logs           # Collect logs to custom directory"
        echo "  $0 analyze ./debug-logs              # Analyze collected logs"
        echo "  $0 watch-connections                 # Watch connections in real-time"
        exit 1
        ;;
esac
