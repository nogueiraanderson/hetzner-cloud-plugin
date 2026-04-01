#!/usr/bin/env bash
# Check plugin version and executor status across all instances.
# Usage: ./scripts/check.sh
set -euo pipefail

INSTANCES="${1:-rel ps80 psmdb pxc pxb pg ps57 pmm cloud ps3}"

for inst in $INSTANCES; do
    ver=$(jenkins admin -i "$inst" groovy -e \
        'println Jenkins.instance.pluginManager.plugins.find { it.shortName == "hetzner-cloud" }?.version' \
        2>/dev/null | python3 -c "
import json, sys
try:
    print(json.load(sys.stdin).get('message','?'))
except:
    print('?')
" 2>/dev/null || echo "?")
    busy=$(jenkins admin -i "$inst" executors -r 2>&1 | tail -n +2 | wc -l)
    total=$(jenkins admin -i "$inst" executors 2>&1 | tail -n +2 | wc -l)
    echo "$inst: v$ver ($busy/$total busy)"
done
