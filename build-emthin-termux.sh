#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
EMTHIN_ANDROID_DIR="${EMTHIN_ANDROID_DIR:-/home/leo/Projects/emthin/emthin-android}"
ANDROID_SDK_SOURCE="${ANDROID_SDK_SOURCE:-/nix/store/mqbyw2bjnk4nzli9cs8i406dkvxxgv6h-androidsdk/libexec/android-sdk}"
LOCAL_SDK="$SCRIPT_DIR/.local-sdk"

BOOTSTRAP_URL="https://github.com/termux/termux-packages/releases/download/bootstrap-2026.02.12-r1%2Bapt.android-7/bootstrap-aarch64.zip"
BOOTSTRAP_SHA256="ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3"
BOOTSTRAP_ZIP="$SCRIPT_DIR/app/src/main/cpp/bootstrap-aarch64.zip"

mkdir -p "$LOCAL_SDK/platforms" "$LOCAL_SDK/build-tools" "$LOCAL_SDK/licenses"
rm -rf "$LOCAL_SDK/platforms/android-37" "$LOCAL_SDK/platforms/android-37.0"
printf '24333f8a63b6825ea9c5514f83c2829b004d1fee\n' > "$LOCAL_SDK/licenses/android-sdk-license"

if [ ! -f "$BOOTSTRAP_ZIP" ] || ! printf '%s  %s\n' "$BOOTSTRAP_SHA256" "$BOOTSTRAP_ZIP" | sha256sum -c - >/dev/null 2>&1; then
    echo "==> Downloading Termux aarch64 bootstrap..."
    curl -fsSL -L -o "$BOOTSTRAP_ZIP" "$BOOTSTRAP_URL"
    printf '%s  %s\n' "$BOOTSTRAP_SHA256" "$BOOTSTRAP_ZIP" | sha256sum -c -
fi

TOOLCHAIN="$EMTHIN_ANDROID_DIR/.toolchain/bin"
CLANG="$TOOLCHAIN/clang"
CLANGXX="$TOOLCHAIN/clang++"
if [ ! -x "$CLANG" ] || [ ! -x "$TOOLCHAIN/aapt2" ]; then
    echo "Missing box64 toolchain under $TOOLCHAIN; run $EMTHIN_ANDROID_DIR/scripts/setup-box64-toolchain.sh first." >&2
    exit 1
fi

echo "==> Building Termux & Wayland native binaries (arm64-v8a)..."
mkdir -p \
    "$SCRIPT_DIR/app/src/main/jniLibs/arm64-v8a" \
    "$SCRIPT_DIR/terminal-emulator/src/main/jniLibs/arm64-v8a" \
    "$SCRIPT_DIR/termux-shared/src/main/jniLibs/arm64-v8a" \
    "$SCRIPT_DIR/emthin-core/src/main/jniLibs/arm64-v8a"

"$CLANG" --target=aarch64-linux-android28 -shared -fPIC -O2 \
    -I"$SCRIPT_DIR/app/src/main/cpp" \
    -Wl,-soname,libtermux-bootstrap.so \
    -o "$SCRIPT_DIR/app/src/main/jniLibs/arm64-v8a/libtermux-bootstrap.so" \
    "$SCRIPT_DIR/app/src/main/cpp/termux-bootstrap.c" \
    "$SCRIPT_DIR/app/src/main/cpp/termux-bootstrap-zip.S"

"$CLANG" --target=aarch64-linux-android28 -fPIE -pie -O2 -std=c11 \
    -o "$SCRIPT_DIR/app/src/main/jniLibs/arm64-v8a/libemthin_wl_demo.so" \
    "$SCRIPT_DIR/app/src/main/cpp/emthin-wl-demo.c"

"$CLANG" --target=aarch64-linux-android28 -shared -fPIC -O2 -std=c11 \
    -Wl,-soname,libtermux.so \
    -o "$SCRIPT_DIR/terminal-emulator/src/main/jniLibs/arm64-v8a/libtermux.so" \
    "$SCRIPT_DIR/terminal-emulator/src/main/jni/termux.c"

"$CLANGXX" --target=aarch64-linux-android28 -shared -fPIC -O2 -std=c++17 \
    -static-libstdc++ -llog \
    -Wl,-soname,liblocal-socket.so \
    -o "$SCRIPT_DIR/termux-shared/src/main/jniLibs/arm64-v8a/liblocal-socket.so" \
    "$SCRIPT_DIR/termux-shared/src/main/cpp/local-socket.cpp"

if [ "${SKIP_RUST_BUILD:-0}" != "1" ]; then
    echo "==> Building emthin Rust core (libemthin_pad.so)..."
    (
        cd "$EMTHIN_ANDROID_DIR"
        export PATH="/nix/store/6gj4dzcv6gaxh5raqyrq7jmy605qaj3z-gcc-wrapper-16.2.0/bin:/nix/store/7d3a3frkcwb4ljxa901bdqcbdg4z83mi-zig-0.16.0/bin:/usr/bin:$PATH"
        ANDROID_ABI=arm64-v8a bash ./build-apk.sh
    )
fi

cp -f "$EMTHIN_ANDROID_DIR/target/aarch64-linux-android/release/libemthin_pad.so" \
    "$SCRIPT_DIR/emthin-core/src/main/jniLibs/arm64-v8a/libemthin_pad.so"

echo "==> Assembling :app:assembleDebug via Gradle..."
cd "$SCRIPT_DIR"
if [ -z "${JAVA_HOME:-}" ]; then
    if [ -x "/nix/store/a0n7s1s6qmlhibw401mqd2jhvi2nac40-openjdk-21.0.13.0.2+1/bin/javac" ]; then
        export JAVA_HOME="/nix/store/a0n7s1s6qmlhibw401mqd2jhvi2nac40-openjdk-21.0.13.0.2+1"
    elif [ -x "/nix/store/5kpcn71kdvhf0cwkc2sgy1y4w3h3d6r5-openjdk-17.0.20.1+1/bin/javac" ]; then
        export JAVA_HOME="/nix/store/5kpcn71kdvhf0cwkc2sgy1y4w3h3d6r5-openjdk-17.0.20.1+1"
    elif command -v javac >/dev/null 2>&1; then
        export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    fi
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
export ANDROID_HOME="$LOCAL_SDK"
export ANDROID_SDK_ROOT="$LOCAL_SDK"
export EMTHIN_PREBUILT_NATIVE=1

./gradlew --no-daemon \
    -Pandroid.aapt2FromMavenOverride="$TOOLCHAIN/aapt2" \
    :app:assembleDebug

APK_PATH="$SCRIPT_DIR/app/build/outputs/apk/debug/termux-app_apt-android-7-debug_universal.apk"
if [ ! -f "$APK_PATH" ]; then
    APK_PATH="$(find "$SCRIPT_DIR/app/build/outputs/apk" -name '*.apk' | head -n1)"
fi
echo "==> Built APK: $APK_PATH"
