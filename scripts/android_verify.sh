#!/usr/bin/env bash
set -euo pipefail
api_level="${1:?API level required}"
collect_images() {
    result=$?
    mkdir -p screenshots
    adb pull /sdcard/Pictures/LifeAtlas screenshots/instrumented >/dev/null 2>&1 || true
    exit "$result"
}
trap collect_images EXIT
adb shell settings put secure show_ime_with_hard_keyboard 1
if [ "$api_level" = "31" ]; then
    adb shell wm density 160
    adb shell wm size 320x900
    adb shell settings put system font_scale 1.5
elif [ "$api_level" = "36" ]; then
    adb shell wm density 160
    adb shell wm size 840x1100
fi
if [ "$api_level" = "35" ]; then
    ./gradlew :app:connectedStandaloneDebugAndroidTest --stacktrace
else
    ./gradlew :app:connectedStandaloneDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.lifeatlas.app.UiTest,com.lifeatlas.app.ThemeTest,com.lifeatlas.app.CaptureTest --stacktrace
fi
bash scripts/screenshots.sh
