#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
if [ "$#" -gt 0 ]; then
    OBSFIT_VAULT_PATH="$1" ./gradlew :core:test
else
    ./gradlew :core:test
fi
