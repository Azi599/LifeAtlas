#!/usr/bin/env bash
set -euo pipefail
api_level="${1:?API level required}"
adb shell settings put secure show_ime_with_hard_keyboard 1
if [ "$api_level" = "35" ]; then
    ./gradlew :app:connectedStandaloneDebugAndroidTest --stacktrace
else
    ./gradlew :app:connectedStandaloneDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lifeatlas.app.UiTest,com.lifeatlas.app.ThemeTest --stacktrace
fi
bash scripts/screenshots.sh
