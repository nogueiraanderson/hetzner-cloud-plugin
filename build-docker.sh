#!/bin/bash
# Build hetzner-cloud-plugin inside Docker with Java 17 + Maven
# Produces: target/hetzner-cloud.hpi
set -e

cd /tmp/hetzner-cloud-plugin

docker run --rm \
  -v "$(pwd)":/plugin \
  -v "$HOME/.m2/repository:/root/.m2/repository" \
  -w /plugin \
  maven:3.9-eclipse-temurin-17 \
  mvn clean package -DskipTests -Dchangelist=103.percona.1
