# Hetzner Cloud Plugin for Jenkins - Build & Test
# Patched version with retention bug fixes (Percona)

version := "103.percona.1"
image := "maven:3.9-eclipse-temurin-17"
container := "hetzner-build"
m2_volume := "hetzner-m2-cache"

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

# Clean build artifacts and cache
clean:
    rm -f hetzner-cloud-*.hpi
    docker rm -f {{container}} 2>/dev/null || true

# Nuke Maven cache volume (forces full re-download)
clean-cache:
    docker volume rm {{m2_volume}} 2>/dev/null || true
