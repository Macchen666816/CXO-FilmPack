#!/bin/bash
# Double-click in Finder. Builds the shared Kotlin/Native framework and writes build.log next to this file.
cd "$(dirname "$0")" || exit 1
LOG="$PWD/build.log"

# Optional: put a directory path (e.g. an external SSD) in local.cache-dir to keep heavy caches there.
CACHE=""
if [ -f local.cache-dir ]; then
  CACHE="$(head -1 local.cache-dir)"
  mkdir -p "$CACHE" 2>/dev/null || CACHE=""
fi
[ -n "$CACHE" ] && export KONAN_DATA_DIR="$CACHE/konan" GRADLE_USER_HOME="$CACHE/gradle"

jmajor() { "$1/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+)(\.([0-9]+))?.*/\1/;s/^1$/8/'; }
pick_jdk() {
  local c
  for c in "$(/usr/libexec/java_home -v 17+ 2>/dev/null)" \
           /Library/Java/JavaVirtualMachines/*/Contents/Home \
           "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
           "/Applications/Android Studio Preview.app/Contents/jbr/Contents/Home" \
           /opt/homebrew/opt/openjdk*/libexec/openjdk.jdk/Contents/Home \
           "$PWD/.jdk17/Contents/Home"; do
    [ -x "$c/bin/java" ] || continue
    [ "$(jmajor "$c")" -ge 17 ] 2>/dev/null && { echo "$c"; return; }
  done
}
JDK="$(pick_jdk)"
if [ -z "$JDK" ]; then
  echo "未找到 JDK 17+,正在下载到本目录 .jdk17 …"
  mkdir -p .jdk17 && curl -fL "https://api.adoptium.net/v3/binary/latest/17/ga/mac/aarch64/jdk/hotspot/normal/eclipse" -o .jdk17.tgz \
    && tar -xzf .jdk17.tgz -C .jdk17 --strip-components=1 && rm -f .jdk17.tgz
  JDK="$(pick_jdk)"
fi
[ -n "$JDK" ] && export JAVA_HOME="$JDK" && export PATH="$JDK/bin:$PATH"

{
  echo "== $(date)"
  echo "== CACHE: ${CACHE:-internal}"
  echo "== JAVA_HOME: ${JAVA_HOME:-none}"
  echo "== JAVA: $(java -version 2>&1 | head -1)"
  echo "== Xcode: $(xcodebuild -version 2>&1 | head -1)"
  # Uses the repository's Gradle wrapper; this directory is its own Gradle build.
  ../gradlew -p "$PWD" :shared:assembleCxoSharedXCFramework --console=plain --no-daemon
  echo "== exit=$?"
} 2>&1 | tee "$LOG"
echo; echo "已写入 build.log,可以关闭此窗口。"
