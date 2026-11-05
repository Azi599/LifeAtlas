#!/usr/bin/env bash
set -euo pipefail
mkdir -p screenshots
adb shell wm density 160
for width in 320 360 393 432; do
  for scale in 1.0 1.5; do
    adb shell wm size "${width}x900"
    adb shell settings put system font_scale "$scale"
    adb shell am force-stop com.lifeatlas.app.newhost
    adb shell am start -n com.lifeatlas.app.newhost/com.lifeatlas.app.MainActivity
    sleep 3
    adb exec-out screencap -p > "screenshots/workspace-${width}-${scale}.png"
  done
done
adb shell settings put system font_scale 1.0
adb shell wm size reset
adb shell wm density reset
