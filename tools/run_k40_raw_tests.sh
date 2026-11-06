#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
research_dir="/Users/chenyixuan/ReverseWork/DxOFilmPack"
device="${ANDROID_SERIAL:-b549157b}"
package="dev.cxo.filmpack.app"

adb_cmd=(adb -s "$device")
"${adb_cmd[@]}" wait-for-device
"${adb_cmd[@]}" install -r "$project_dir/app/build/outputs/apk/debug/app-debug.apk"
"${adb_cmd[@]}" install -r "$project_dir/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

"${adb_cmd[@]}" shell run-as "$package" mkdir -p cache/raw-fixtures
"${adb_cmd[@]}" shell run-as "$package" rm -f \
  cache/raw-fixtures/P1040758.RW2 \
  cache/raw-fixtures/P1107056.RW2 \
  cache/raw-fixtures/P1176256.RW2 \
  cache/raw-fixtures/P1176257.RW2 \
  cache/raw-fixtures/P1055542.RW2 \
  cache/raw-fixtures/P1066283.RW2 \
  cache/raw-fixtures/P1035087.RW2
for source in \
  "$research_dir/pic/室内暖光或混合光/P1040758.RW2" \
  "$research_dir/pic/带天空和阴影的高反差风光/P1107056.RW2" \
  "$research_dir/pic/带天空和阴影的高反差风光/P1176256.RW2" \
  "$research_dir/pic/带天空和阴影的高反差风光/P1176257.RW2" \
  "$research_dir/pic/肤色/P1055542.RW2" \
  "$research_dir/pic/日光下的中性物体与彩色物体/P1066283.RW2" \
  "$research_dir/pic/P1035087.RW2"; do
  name="$(basename "$source")"
  "${adb_cmd[@]}" push "$source" "/data/local/tmp/$name" >/dev/null
  "${adb_cmd[@]}" shell run-as "$package" cp "/data/local/tmp/$name" "cache/raw-fixtures/$name"
done

cd "$project_dir"
ANDROID_SERIAL="$device" ./gradlew :app:connectedDebugAndroidTest
