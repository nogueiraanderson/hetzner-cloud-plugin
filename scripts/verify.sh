#!/usr/bin/env bash
# Verify hetzner-cloud plugin on Jenkins instances by creating a temp job,
# triggering it, checking logs, and cleaning up.
# Usage: ./scripts/verify.sh <inst1> [inst2] ...
set -euo pipefail

INSTANCES=("$@")
JOB_NAME="verify-hetzner-plugin-temp"

if [[ ${#INSTANCES[@]} -eq 0 ]]; then
    echo "Usage: $0 <inst1> [inst2] ..."
    exit 1
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="${REPO_ROOT}/scripts/verify-job.xml"

for inst in "${INSTANCES[@]}"; do
    echo "=== $inst ==="

    # Create job
    echo "  Creating job..."
    jenkins job -i "$inst" create "$JOB_NAME" -c "$CONFIG" 2>&1 | head -1 || true

    # Trigger build
    echo "  Triggering..."
    jenkins build "$inst/$JOB_NAME" 2>&1 | head -1 || true

    # Wait for completion (poll every 10s, max 5 min for Hetzner provisioning)
    for i in $(seq 1 30); do
        sleep 10
        raw=$(jenkins status "$inst/$JOB_NAME" 2>&1)
        status=$(echo "$raw" | grep -oP 'result: \K\w+' || echo "")
        if [[ -n "$status" && "$status" != "null" ]]; then
            echo "  Result: $status"
            break
        fi
        elapsed=$((i * 10))
        echo "  Waiting... (${elapsed}s)"
        [[ $i -eq 30 ]] && echo "  TIMEOUT after 5 min"
    done

    # Show verification markers from logs
    echo "  --- Verification ---"
    jenkins logs "$inst/$JOB_NAME" 2>&1 \
        | grep -E 'PLUGIN_VERSION|ORPHAN_CLEANER|HETZNER_CLOUDS|CLOUD=|VERIFY_OK|uname:' \
        || echo "  (no markers found in log)"
    echo "  ---"

    # Delete job
    echo "  Cleaning up..."
    echo y | jenkins job -i "$inst" delete "$JOB_NAME" 2>&1 | head -1 || true

    echo ""
done
