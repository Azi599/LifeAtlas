#!/usr/bin/env bash
set -euo pipefail
for attempt in 1 2 3; do
    if ./gradlew --version; then exit 0; fi
    if [ "$attempt" = 3 ]; then exit 1; fi
    sleep "$((attempt * 5))"
done
