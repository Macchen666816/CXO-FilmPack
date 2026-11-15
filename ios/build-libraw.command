#!/bin/bash
# Optimised, static LibRaw (CDDL-1.0) for device + Apple Silicon simulator.
set -euo pipefail
cd "$(dirname "$0")"
LOG="$PWD/build-libraw.log"
exec > >(tee "$LOG") 2>&1
trap 'status=$?; echo "== exit=$status"; echo "已写入 build-libraw.log"; exit "$status"' EXIT
CACHE="$PWD/native/build/cache"
if [ -f local.cache-dir ] && mkdir -p "$(head -1 local.cache-dir)/cxo-libraw" 2>/dev/null; then
  CACHE="$(head -1 local.cache-dir)/cxo-libraw"
fi
CONFIGURATION="${CONFIGURATION:-Release}"
DEBUG_FLAG=-DDEBUG=0
if [ "$CONFIGURATION" = Debug ]; then DEBUG_FLAG=-DDEBUG=1; fi
# LibRaw 0.22.3 is shared with the Android build; it is not copied into ios/.
SRC="$(cd .. && pwd)/app/src/main/cpp/vendor/libraw"
OUT="$PWD/native/build"
[ -f "$SRC/libraw/libraw.h" ] || { echo '缺少 app/src/main/cpp/vendor/libraw (LibRaw 0.22.3)'; exit 1; }
mkdir -p "$CACHE" "$OUT"
echo "== $(date)"
echo "== CACHE: $CACHE"
xcodebuild -version
# A content-addressed cache includes every header, source, flags and SDK/compiler version.
KEY=$({ find "$SRC/libraw" "$SRC/internal" "$SRC/src" -type f -print | LC_ALL=C sort | while IFS= read -r f; do shasum -a 256 "$f"; done
        echo "configuration=$CONFIGURATION"
        shasum -a 256 native/cxo_raw.h native/cxo_raw.cpp build-libraw.command
        xcrun clang++ --version; xcrun --sdk iphoneos --show-sdk-version; xcrun --sdk iphonesimulator --show-sdk-version
      } | shasum -a 256 | cut -d' ' -f1)
WORK="$CACHE/$KEY"
mkdir -p "$WORK/headers"
cp native/cxo_raw.h "$WORK/headers/"
cat > "$WORK/headers/module.modulemap" <<'MODULE'
module CxoRaw {
  header "cxo_raw.h"
  export *
  link "z"
  link "c++"
}
MODULE
for SDK in iphoneos iphonesimulator; do
  if [ "$SDK" = iphoneos ]; then TARGET=arm64-apple-ios17.0; else TARGET=arm64-apple-ios17.0-simulator; fi
  DIR="$WORK/$SDK"
  mkdir -p "$DIR"
  SDKROOT_PATH=$(xcrun --sdk "$SDK" --show-sdk-path)
  while IFS= read -r FILE; do
    OBJ="$DIR/$(echo "$FILE" | shasum -a 256 | cut -d' ' -f1).o"
    if [ ! -f "$OBJ" ]; then
      echo "== $SDK: ${FILE#$SRC/}"
      xcrun --sdk "$SDK" clang++ -target "$TARGET" -arch arm64 -isysroot "$SDKROOT_PATH" \
        -O3 -std=c++17 "$DEBUG_FLAG" -fvisibility=hidden -DLIBRAW_NODLL -DLIBRAW_USE_AUTOPTR -DUSE_ZLIB \
        -I"$SRC" -I"$PWD/native" -c "$FILE" -o "$OBJ.tmp"
      mv "$OBJ.tmp" "$OBJ"
    fi
  done < <(find "$SRC/src" -name '*.cpp' ! -name '*_ph.cpp' | LC_ALL=C sort; echo "$PWD/native/cxo_raw.cpp")
  xcrun --sdk "$SDK" libtool -static -o "$DIR/libCxoRaw.a" "$DIR/"*.o
 done
# Create to a temporary path, preserving the last working framework if packaging fails.
TEMP="$OUT/CxoRaw-new.xcframework"
rm -rf "$TEMP"
xcodebuild -create-xcframework \
  -library "$WORK/iphoneos/libCxoRaw.a" -headers "$WORK/headers" \
  -library "$WORK/iphonesimulator/libCxoRaw.a" -headers "$WORK/headers" \
  -output "$TEMP"
rm -rf "$OUT/CxoRaw.xcframework"
mv "$TEMP" "$OUT/CxoRaw.xcframework"
echo '== CxoRaw.xcframework ready (iOS arm64 + simulator arm64, -O3)'
