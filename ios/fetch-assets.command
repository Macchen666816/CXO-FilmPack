#!/bin/bash
# Double-click in Finder. Copies the film data, RAW calibrations and grain pack out of the official
# Android release APK into ios/Assets (these files are too large for Git and are not in the repository).
# Download the APK from https://github.com/Macchen666816/CXO-FilmPack/releases/latest first, then either
# drop it next to this script or pass its path: ./fetch-assets.command /path/to/app.apk
cd "$(dirname "$0")" || exit 1
APK="$1"
if [ -z "$APK" ]; then
  APK=$(ls -t ./*.apk "$HOME"/Downloads/*.apk 2>/dev/null | head -1)
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "没有找到 APK。请先从 Releases 下载安卓安装包，放到 ios/ 目录或「下载」文件夹后再运行。"
  exit 1
fi
echo "== APK: $APK"
TMP=$(mktemp -d)
unzip -q "$APK" 'assets/*' -d "$TMP" || { echo "解压失败"; rm -rf "$TMP"; exit 1; }
for need in films.json films raw/cameras.json native-grain8-v1.cxograinpack; do
  [ -e "$TMP/assets/$need" ] || { echo "APK 里缺少 assets/$need，可能版本不对"; rm -rf "$TMP"; exit 1; }
done
rm -rf Assets && mv "$TMP/assets" Assets && rm -rf "$TMP"
echo "== Assets 已就绪：$(du -sh Assets | cut -f1)"
