#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 ef3d0c3e
# SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-ViewpointLinux-exception
# Builds the add-on and lays out the mod folder in dist/ViewpointLinux.
#
#   ./build.sh          build
#   ./build.sh sign     build, then sign the jar for ZombieBuddy (.zbs) through ssh-agent
#   ./build.sh test     build, then run both standalone tests (no game needed)
#   ./build.sh workshop build, sign, and stage the Workshop upload in ~/Zomboid/Workshop/ViewpointLinux
#
# Needs a JDK 25+ (Viewpoint 0.1.7 is compiled for Java 25, the game's runtime), g++, and the
# game, Viewpoint and ZombieBuddy installed through Steam.
set -euo pipefail
cd "$(dirname "$0")"
STEAM="${STEAM:-$HOME/.steam/steam/steamapps}"
GAME="$STEAM/common/ProjectZomboid/projectzomboid"
WS="$STEAM/workshop/content/108600"
VIEWPOINT="${VIEWPOINT:-$WS/3809306528/mods/Viewpoint/42/media/java/client/Viewpoint.jar}"
ZB="${ZB:-$WS/3619862853/mods/ZombieBuddy/libs/ZombieBuddy.jar}"
CP="$GAME/projectzomboid.jar:$VIEWPOINT:$ZB"
JAR=dist/ViewpointLinux/42/media/java/client/ViewpointLinux.jar
# javac must read Viewpoint's Java 25 classes; the add-on itself still targets Java 17.
JDK="${JDK:-/usr/lib/jvm/java-25-openjdk-amd64}"
JAVAC="$JDK/bin/javac"
[[ -x "$JAVAC" ]] || JAVAC=javac
"$JAVAC" -version 2>&1 | grep -qE 'javac (2[5-9]|[3-9][0-9])' || { echo "need javac 25 or newer (set JDK=...); found: $("$JAVAC" -version 2>&1)" >&2; exit 1; }

native/fetch-deps.sh
rm -rf build dist

# The NGX wrapper: NVIDIA's static NGX library and the C++ runtime linked in, only libc
# left as a dependency, only the vpl_* functions exported.
mkdir -p build/classes/vplinux/native
g++ -std=c++17 -O2 -fPIC -shared -fvisibility=hidden -Wall -Wextra \
  -isystem third_party/dlss/include -isystem third_party/vulkan-headers/include \
  native/vplinux_ngx.cpp third_party/dlss/lib/libnvsdk_ngx.a \
  -o build/classes/vplinux/native/libvplinux_ngx.so \
  -ldl -static-libstdc++ -static-libgcc -Wl,--no-undefined -Wl,--exclude-libs,ALL -Wl,-s

"$JAVAC" --release 17 -Xlint:all -cp "$CP" -d build/classes $(find src -name '*.java')
mkdir -p "$(dirname "$JAR")"
jar --create --date=2000-01-01T00:00:02Z --file "$JAR" -C build/classes .
cp -r mod/common dist/ViewpointLinux/
cp third_party/dlss/LICENSE.txt dist/ViewpointLinux/common/NVIDIA-DLSS-LICENSE.txt
cp LICENSE LICENSE-EXCEPTION NOTICE dist/ViewpointLinux/common/
echo "built dist/ViewpointLinux ($(sha256sum "$JAR" | cut -c1-16)…)"

if [[ "${1:-}" == "sign" || "${1:-}" == "workshop" ]]; then
  # Signs with the ssh-ed25519 key through ssh-agent; the private key file is never read.
  STEAM_ID="${STEAM_ID:-76561198121246651}"
  SIGN_KEY="${SIGN_KEY:-$HOME/.ssh/id_ed25519.pub}"
  AGENT="${SSH_AUTH_SOCK:-}"
  if [[ -z "$AGENT" || ! -S "$AGENT" ]]; then AGENT="/run/user/$(id -u)/openssh_agent"; fi
  java tools/ZbsSign.java "$JAR" "$STEAM_ID" "$SIGN_KEY" "$AGENT"
  java -cp "$ZB" tools/ZbsVerify.java "$JAR" "$SIGN_KEY"
fi

if [[ "${1:-}" == "test" ]]; then
  "$JAVAC" --release 17 -cp "$JAR:$CP" -d build/test $(find test -name '*.java')
  # DLSS's runtime library, where Viewpoint's download would put it.
  DLSS_SO=libnvidia-ngx-dlss.so.310.9.1
  mkdir -p build/test-natives
  [[ -f third_party/dlss/runtime/$DLSS_SO ]] || { mkdir -p third_party/dlss/runtime; curl -sSfL -o third_party/dlss/runtime/$DLSS_SO \
    "https://raw.githubusercontent.com/NVIDIA/DLSS/v310.9.1/lib/Linux_x86_64/rel/$DLSS_SO"; }
  echo "7561f16cab74e2b7ccf791bf44bb9cc43abb44bcf78f33c5663520ad4e857e02  third_party/dlss/runtime/$DLSS_SO" | sha256sum --quiet -c
  cp third_party/dlss/runtime/$DLSS_SO build/test-natives/
  JAVA="${JAVA:-$GAME/jre64/bin/java}"
  for TEST in LinuxInteropTest LinuxDlssTest; do
    echo "== $TEST"
    "$JAVA" -Djdk.attach.allowAttachSelf=true --enable-native-access=ALL-UNNAMED -Djava.library.path="$GAME/natives" \
      -Dviewpoint.natives="$PWD/build/test-natives" \
      -cp "build/test:$JAR:$CP" viewpoint.upscale.$TEST
  done
fi

if [[ "${1:-}" == "workshop" ]]; then
  # The layout the game's uploader wants: workshop.txt, preview.png, Contents/mods/<mod>/.
  # The game writes the item's id= into workshop.txt after the first upload; keep it.
  W="${WORKSHOP_DIR:-$HOME/Zomboid/Workshop}/ViewpointLinux"
  ID_LINE=$(grep -s '^id=' "$W/workshop.txt" || true)
  rm -rf "$W/Contents"
  mkdir -p "$W/Contents/mods"
  cp -r dist/ViewpointLinux "$W/Contents/mods/"
  # GPL: ship the complete source with the binaries. Reproducible tarball of every file git
  # tracks or would track (the .gitignore keeps NVIDIA's SDK and build outputs out).
  git ls-files -co --exclude-standard -z | sort -z | tar --null -T - --mtime=@0 --owner=0 --group=0 --numeric-owner \
    --transform 's|^|ViewpointLinux/|' -cf - | gzip -n > "$W/Contents/mods/ViewpointLinux/common/ViewpointLinux-source.tar.gz"
  cp workshop/preview.png "$W/"
  { head -n1 workshop/workshop.txt; [[ -n "$ID_LINE" ]] && echo "$ID_LINE"; tail -n +2 workshop/workshop.txt; } > "$W/workshop.txt"
  # The uploader refuses these anywhere under Contents/ (the native library is inside the jar).
  BAD=$(find "$W/Contents" -type f \( -name '*.exe' -o -name '*.dll' -o -name '*.bat' -o -name '*.app' -o -name '*.dylib' \
    -o -name '*.sh' -o -name '*.so' -o -name '*.zip' \) ! -iname '*pyramid.zip')
  [[ -z "$BAD" ]] || { echo "files the Workshop uploader refuses: $BAD" >&2; exit 1; }
  echo "staged $W${ID_LINE:+ ($ID_LINE)}"
fi
