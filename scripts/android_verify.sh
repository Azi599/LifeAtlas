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
./gradlew :app:connectedStandaloneDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.notAnnotation=androidx.test.filters.LargeTest --stacktrace
bash scripts/screenshots.sh
if [ "$api_level" = "35" ]; then
    mkdir -p screenshots/functional-test-results
    cp -a app/build/outputs/androidTest-results/. screenshots/functional-test-results/
    ./gradlew :app:connectedStandaloneDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.annotation=androidx.test.filters.LargeTest --stacktrace
fi
