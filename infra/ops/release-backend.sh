#!/usr/bin/env bash
# Sa Mac-a: backend sa svim testovima (Docker mora da radi), pa testiran jar
# ide u infra/release/backend.jar, odakle ga server pakuje u Docker sliku.
# Dodatni argumenti idu Maven-u, npr. -o bez interneta.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd -P)"

cd "$root/pametna-kupovina-backend"
./mvnw clean verify "$@"
mkdir -p "$root/infra/release"
cp target/backend-0.0.1-SNAPSHOT.jar "$root/infra/release/backend.jar"
shasum -a 256 "$root/infra/release/backend.jar"
