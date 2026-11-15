#!/bin/bash
# Double-click in Finder. Compiles the iOS app for the simulator (no signing needed) and writes build-ios.log.
cd "$(dirname "$0")" || exit 1
LOG="$PWD/build-ios.log"

# Optional: put a directory path (e.g. an external SSD) in local.cache-dir to keep heavy caches there.
CACHE=""
if [ -f local.cache-dir ]; then
  CACHE="$(head -1 local.cache-dir)"
  mkdir -p "$CACHE" 2>/dev/null || CACHE=""
fi
[ -n "$CACHE" ] && export KONAN_DATA_DIR="$CACHE/konan" GRADLE_USER_HOME="$CACHE/gradle"
{
  echo "== $(date)"
  echo "== CACHE: ${CACHE:-internal}"
  echo "== Xcode: $(xcodebuild -version 2>&1 | head -1)"
  xcodebuild -project App/CXOFilmPack.xcodeproj -scheme CXOFilmPack \
    -destination 'generic/platform=iOS Simulator' -configuration Debug \
    -derivedDataPath "${CACHE:-$PWD/build}/ios-dd" CODE_SIGNING_ALLOWED=NO build 2>&1 \
    | grep -E "error:|warning: .*(Swift|CxoShared)|BUILD|ld:|Undefined|\*\* " | head -200
  echo "== exit=${PIPESTATUS[0]}"
} 2>&1 | tee "$LOG"
echo; echo "已写入 build-ios.log,可以关闭此窗口。"
