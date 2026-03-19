# Hetzner Cloud Plugin for Jenkins - Build, Test & Deploy
# Patched version with retention bug fixes (Percona)

version := "103.percona.2"
image := "maven:3.9-eclipse-temurin-17"
container := "hetzner-build"
m2_volume := "hetzner-m2-cache"
instances := "rel ps80 psmdb pxc pxb pg ps57 pmm cloud ps3"

# Build plugin .hpi (skipping tests for speed)
build:
    #!/usr/bin/env bash
    set -euo pipefail
    docker volume create {{m2_volume}} >/dev/null 2>&1 || true
    docker rm -f {{container}} 2>/dev/null || true
    docker create --name {{container}} -w /plugin \
        -v {{m2_volume}}:/root/.m2/repository \
        {{image}} \
        mvn clean package -DskipTests -Dchangelist={{version}}
    docker cp "$(pwd)/." {{container}}:/plugin
    docker start -a {{container}}
    docker cp {{container}}:/plugin/target/hetzner-cloud.hpi ./hetzner-cloud-{{version}}.hpi
    docker rm {{container}}
    ls -lh ./hetzner-cloud-{{version}}.hpi

# Build and run tests
test:
    #!/usr/bin/env bash
    set -euo pipefail
    docker volume create {{m2_volume}} >/dev/null 2>&1 || true
    docker rm -f {{container}} 2>/dev/null || true
    docker create --name {{container}} -w /plugin \
        -v {{m2_volume}}:/root/.m2/repository \
        {{image}} \
        mvn clean verify -Dchangelist={{version}}
    docker cp "$(pwd)/." {{container}}:/plugin
    docker start -a {{container}}
    docker rm {{container}}

# Deploy to a single instance (upload + pin + smart restart)
deploy inst:
    ./scripts/deploy.sh {{inst}} {{version}}

# Deploy to all 10 instances
deploy-all:
    #!/usr/bin/env bash
    set -euo pipefail
    for inst in {{instances}}; do
        ./scripts/deploy.sh "$inst" {{version}}
    done

# Check plugin version and executor status across all instances
check:
    ./scripts/check.sh "{{instances}}"

# Verify plugin on a single instance (create temp job, run, check, delete)
verify inst:
    ./scripts/verify.sh {{inst}}

# Verify plugin on all instances
verify-all:
    ./scripts/verify.sh {{instances}}

# Clean build artifacts and cache
clean:
    rm -f hetzner-cloud-*.hpi
    docker rm -f {{container}} 2>/dev/null || true

# Nuke Maven cache volume (forces full re-download)
clean-cache:
    docker volume rm {{m2_volume}} 2>/dev/null || true
