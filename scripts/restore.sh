#!/usr/bin/env bash
# Restore Hetzner cloud config to a Jenkins instance via Script Console.
# Reads from the backup file created by backup.sh.
# Usage: ./scripts/restore.sh <instance>
set -euo pipefail

inst="${1:?Usage: restore.sh <instance>}"
backup_file="/tmp/hetzner-backup-${inst}.xml"

if [[ ! -f "$backup_file" ]]; then
    echo "No backup found at $backup_file"
    echo "Run: just backup $inst"
    exit 1
fi

echo "=== Restoring $inst cloud config from $backup_file ==="

# Escape the XML for embedding in Groovy
xml_content=$(cat "$backup_file")

groovy_script=$(cat <<GROOVY
import jenkins.model.Jenkins

def xmlContent = '''${xml_content}'''
def xstream = Jenkins.XSTREAM2
def parts = xmlContent.split('<!-- cloud-separator -->')
def jenkins = Jenkins.instance

// Remove existing Hetzner clouds
jenkins.clouds.removeAll { it.class.name.contains('Hetzner') }

// Restore from backup
parts.each { part ->
    part = part.trim()
    if (part) {
        try {
            def cloud = xstream.fromXML(part)
            jenkins.clouds.add(cloud)
        } catch (Exception e) {
            println "WARN: Failed to restore cloud: \${e.message}"
        }
    }
}

jenkins.save()
println "RESTORED|\${jenkins.clouds.findAll { it.class.name.contains('Hetzner') }.size()} clouds"
GROOVY
)

result=$(jenkins admin -i "$inst" groovy -e "$groovy_script" 2>&1 \
    | python3 -c "
import json, sys
try:
    print(json.load(sys.stdin).get('message','FAIL'))
except:
    print('FAIL')
" 2>/dev/null || echo "FAIL")

echo "  $result"

if [[ "$result" == *"RESTORED"* ]]; then
    echo "  Config restored. Restart $inst to apply."
fi
