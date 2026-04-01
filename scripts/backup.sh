#!/usr/bin/env bash
# Backup Hetzner cloud config from a Jenkins instance via Script Console.
# Exports the full cloud XML config for disaster recovery.
# Usage: ./scripts/backup.sh <instance>
set -euo pipefail

inst="${1:?Usage: backup.sh <instance>}"
backup_file="/tmp/hetzner-backup-${inst}.xml"

echo "=== Backing up $inst cloud config ==="

groovy_script=$(cat <<'GROOVY'
import jenkins.model.Jenkins
def clouds = Jenkins.instance.clouds.findAll { it.class.name.contains('Hetzner') }
if (clouds.isEmpty()) {
    println "NO_HETZNER_CLOUDS"
    return
}
def writer = new StringWriter()
def xstream = Jenkins.XSTREAM2
clouds.each { cloud ->
    xstream.toXML(cloud, writer)
    writer.write("\n<!-- cloud-separator -->\n")
}
println writer.toString()
GROOVY
)

result=$(jenkins admin -i "$inst" groovy -e "$groovy_script" 2>&1 \
    | python3 -c "
import json, sys
try:
    msg = json.load(sys.stdin).get('message','')
    print(msg)
except:
    sys.stdin.seek(0)
    print(sys.stdin.read())
" 2>/dev/null || echo "FAIL")

if [[ "$result" == "NO_HETZNER_CLOUDS" ]]; then
    echo "  No Hetzner clouds configured on $inst"
    exit 1
fi

if [[ "$result" == "FAIL" ]]; then
    echo "  Failed to retrieve config from $inst"
    exit 1
fi

echo "$result" > "$backup_file"
echo "  Saved to $backup_file ($(wc -c < "$backup_file") bytes)"
