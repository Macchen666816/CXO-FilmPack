#!/bin/bash
# Double-click in Finder. Builds a Release, unsigned IPA for sideloading (SideStore / AltStore re-sign it)
# and writes CXOFilmPack.ipa next to this script. Log: build-ipa.log.
cd "$(dirname "$0")" || exit 1
LOG="$PWD/build-ipa.log"
# Optional: put a directory path (e.g. an external SSD) in local.cache-dir to keep heavy caches there.
CACHE=""
if [ -f local.cache-dir ]; then
  CACHE="$(head -1 local.cache-dir)"
  mkdir -p "$CACHE" 2>/dev/null || CACHE=""
fi
DD="${CACHE:-$PWD/build}/ipa-dd"
{
  echo "== $(date)"
  echo "== Xcode: $(xcodebuild -version 2>&1 | head -1)"
  xcodebuild -project App/CXOFilmPack.xcodeproj -scheme CXOFilmPack \
    -destination 'generic/platform=iOS' -configuration Release \
    -derivedDataPath "$DD" CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build 2>&1 \
    | grep -E "error:|BUILD|ld:|Undefined|\*\* " | head -200
  status=${PIPESTATUS[0]}
  echo "== xcodebuild exit=$status"
  APP="$DD/Build/Products/Release-iphoneos/CXOFilmPack.app"
  if [ "$status" = 0 ] && [ -d "$APP" ]; then
    WORK=$(mktemp -d)
    mkdir -p "$WORK/Payload" && cp -R "$APP" "$WORK/Payload/"
    rm -f CXOFilmPack.ipa
    (cd "$WORK" && /usr/bin/zip -qry "$OLDPWD/CXOFilmPack.ipa" Payload)
    rm -rf "$WORK"
    echo "== IPA: $PWD/CXOFilmPack.ipa ($(du -h CXOFilmPack.ipa | cut -f1))"
    echo "== exit=0"
  else
    echo "== exit=1"
  fi
} 2>&1 | tee "$LOG"
echo; echo "已写入 build-ipa.log,可以关闭此窗口。"
