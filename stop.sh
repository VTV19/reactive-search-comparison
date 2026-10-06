#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
docker compose -p java-search-release down
