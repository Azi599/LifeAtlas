#!/usr/bin/env bash
set -euo pipefail
api="${1:?API level required}"
case "$api" in 31|35|36) ;; *) exit 2 ;; esac
for attempt in 1 2 3; do
    if sdkmanager "emulator" "platform-tools" "system-images;android-$api;google_apis;x86_64"; then exit 0; fi
    if [ "$attempt" = 3 ]; then exit 1; fi
    package="$ANDROID_HOME/system-images/android-$api/google_apis/x86_64"
    if [ ! -f "$package/package.xml" ]; then rm -rf "$package"; fi
    sleep "$((attempt * 5))"
done
