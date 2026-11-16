#!/usr/bin/env bash
# Build libtranscribe_jni.so for Android from transcribe.cpp source.
#
# Usage:
#   ./scripts/build_transcribe_cpp_android.sh
#   ./scripts/build_transcribe_cpp_android.sh --abis arm64-v8a,x86_64
#   TRANSCRIBE_SOURCE_DIR=/path/to/transcribe.cpp ./scripts/build_transcribe_cpp_android.sh
#
# The resulting stripped libraries are written to:
#   app/src/main/jniLibs/<abi>/libtranscribe_jni.so
#
# These files are intentionally gitignored. They are produced locally or by a
# CI native-build job, not committed into the main app repository.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_CPP_DIR="$ROOT_DIR/app/src/main/cpp"
JNI_OUT_ROOT="$ROOT_DIR/app/src/main/jniLibs"
DEFAULT_ABIS="arm64-v8a,x86_64"
TRANSCRIBE_REPO_URL="${TRANSCRIBE_REPO_URL:-https://github.com/handy-computer/transcribe.cpp.git}"
TRANSCRIBE_REPO_REF="${TRANSCRIBE_REPO_REF:-v0.2.1}"

# Normalize Windows/Git-Bash paths for shell existence checks.
if command -v cygpath >/dev/null 2>&1; then
    if [[ -n "${ANDROID_HOME:-}" ]]; then
        ANDROID_HOME="$(cygpath -u "$ANDROID_HOME")"
    fi
    if [[ -n "${ANDROID_NDK:-}" ]]; then
        ANDROID_NDK="$(cygpath -u "$ANDROID_NDK")"
    fi
    if [[ -n "${TRANSCRIBE_SOURCE_DIR:-}" ]]; then
        TRANSCRIBE_SOURCE_DIR="$(cygpath -u "$TRANSCRIBE_SOURCE_DIR")"
    fi
fi

ABIS="$DEFAULT_ABIS"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --abis)
            ABIS="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 2
            ;;
    esac
done

# Resolve Android NDK.
if [[ -z "${ANDROID_NDK:-}" ]]; then
    if [[ -d "${ANDROID_HOME:-}/ndk/27.1.12297006" ]]; then
        ANDROID_NDK="${ANDROID_HOME}/ndk/27.1.12297006"
    elif [[ -d "$HOME/AppData/Local/Android/android-sdk/ndk/27.1.12297006" ]]; then
        ANDROID_NDK="$HOME/AppData/Local/Android/android-sdk/ndk/27.1.12297006"
    else
        echo "Android NDK not found. Set ANDROID_NDK." >&2
        exit 1
    fi
fi
echo "Using NDK: $ANDROID_NDK"

# Resolve transcribe.cpp source.
if [[ -z "${TRANSCRIBE_SOURCE_DIR:-}" ]]; then
    if [[ -d "$ROOT_DIR/../transcribe-cpp-src" ]]; then
        TRANSCRIBE_SOURCE_DIR="$ROOT_DIR/../transcribe-cpp-src"
    else
        echo "TRANSCRIBE_SOURCE_DIR not set, cloning into $ROOT_DIR/third_party/transcribe-cpp"
        TRANSCRIBE_SOURCE_DIR="$ROOT_DIR/third_party/transcribe-cpp"
        mkdir -p "$ROOT_DIR/third_party"
        if [[ ! -d "$TRANSCRIBE_SOURCE_DIR/.git" ]]; then
            echo "Cloning transcribe.cpp ref: $TRANSCRIBE_REPO_REF"
            git clone --depth 1 --branch "$TRANSCRIBE_REPO_REF" "$TRANSCRIBE_REPO_URL" "$TRANSCRIBE_SOURCE_DIR"
        fi
    fi
fi
echo "Using transcribe.cpp source: $TRANSCRIBE_SOURCE_DIR"

# Resolve CMake and Ninja.
# Prefer the Android SDK's CMake 3.22.1; fall back to CMAKE_BIN if it is new enough.
if [[ -n "${CMAKE_BIN:-}" && "$("${CMAKE_BIN}" --version 2>/dev/null | head -n1 || true)" == *"3.2"* ]]; then
    : # user-provided CMake
elif [[ -x "${ANDROID_HOME:-}/cmake/3.22.1/bin/cmake" ]]; then
    CMAKE_BIN="$ANDROID_HOME/cmake/3.22.1/bin/cmake"
elif [[ -x "$HOME/AppData/Local/Android/android-sdk/cmake/3.22.1/bin/cmake" ]]; then
    CMAKE_BIN="$HOME/AppData/Local/Android/android-sdk/cmake/3.22.1/bin/cmake"
elif command -v cmake >/dev/null 2>&1 && "$(command -v cmake)" --version 2>/dev/null | head -n1 | grep -q '3\.2'; then
    CMAKE_BIN="$(command -v cmake)"
else
    echo "cmake >= 3.22 not found. Set CMAKE_BIN." >&2
    exit 1
fi
echo "Using cmake: $CMAKE_BIN"

NINJA_BIN="${NINJA_BIN:-ninja}"
if ! command -v "$NINJA_BIN" >/dev/null 2>&1; then
    if [[ -x "${ANDROID_HOME:-}/cmake/3.22.1/bin/ninja" ]]; then
        NINJA_BIN="$ANDROID_HOME/cmake/3.22.1/bin/ninja"
    elif [[ -x "$HOME/AppData/Local/Android/android-sdk/cmake/3.22.1/bin/ninja" ]]; then
        NINJA_BIN="$HOME/AppData/Local/Android/android-sdk/cmake/3.22.1/bin/ninja"
    else
        echo "ninja not found. Set NINJA_BIN." >&2
        exit 1
    fi
fi
echo "Using ninja: $NINJA_BIN"

# Convert to a CMake-friendly path on Windows (C:/...), leave as-is on Linux/macOS.
if command -v cygpath >/dev/null 2>&1; then
    cmake_path() { cygpath -m "$1"; }
else
    cmake_path() { printf '%s' "$1"; }
fi

TOOLCHAIN="$(cmake_path "$ANDROID_NDK/build/cmake/android.toolchain.cmake")"
TRANSCRIBE_SRC_CMAKE="$(cmake_path "$TRANSCRIBE_SOURCE_DIR")"
APP_CPP_CMAKE="$(cmake_path "$APP_CPP_DIR")"
NINJA_CMAKE="$(cmake_path "$NINJA_BIN")"

mkdir -p "$JNI_OUT_ROOT"

IFS=',' read -ra ABI_LIST <<< "$ABIS"
for abi in "${ABI_LIST[@]}"; do
    echo "=================================================="
    echo "Building transcribe.cpp static library for $abi"
    echo "=================================================="
    transcribe_build_dir="$ROOT_DIR/build/native/transcribe-cpp-$abi"
    mkdir -p "$transcribe_build_dir"

    if [[ ! -f "$transcribe_build_dir/src/libtranscribe.a" ]]; then
        "$CMAKE_BIN" -S "$TRANSCRIBE_SRC_CMAKE" -B "$transcribe_build_dir" -G Ninja \
            -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
            -DANDROID_ABI="$abi" \
            -DANDROID_PLATFORM=android-27 \
            -DCMAKE_MAKE_PROGRAM="$NINJA_CMAKE" \
            -DCMAKE_BUILD_TYPE=Release \
            -DTRANSCRIBE_BUILD_SHARED=OFF \
            -DTRANSCRIBE_USE_SYSTEM_BLAS=OFF \
            -DTRANSCRIBE_USE_OPENMP=OFF \
            -DTRANSCRIBE_METAL=OFF \
            -DTRANSCRIBE_VULKAN=OFF \
            -DTRANSCRIBE_BUILD_TESTS=OFF \
            -DTRANSCRIBE_BUILD_TOOLS=OFF \
            -DTRANSCRIBE_BUILD_EXAMPLES=OFF
        "$CMAKE_BIN" --build "$transcribe_build_dir" --config Release -j"${JOBS:-4}"
    else
        echo "transcribe.cpp static library already present: $transcribe_build_dir"
    fi

    echo "=================================================="
    echo "Building libtranscribe_jni.so for $abi"
    echo "=================================================="
    jni_build_dir="$ROOT_DIR/build/native/transcribe-jni-$abi"
    rm -rf "$jni_build_dir"
    mkdir -p "$jni_build_dir"
    "$CMAKE_BIN" -S "$APP_CPP_CMAKE" -B "$jni_build_dir" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$abi" \
        -DANDROID_PLATFORM=android-27 \
        -DCMAKE_MAKE_PROGRAM="$NINJA_CMAKE" \
        -DTRANSCRIBE_SOURCE_DIR="$TRANSCRIBE_SRC_CMAKE" \
        -DTRANSCRIBE_BUILD_DIR="$(cmake_path "$transcribe_build_dir")"
    "$CMAKE_BIN" --build "$jni_build_dir" --config Release -j"${JOBS:-4}"

    out_dir="$JNI_OUT_ROOT/$abi"
    mkdir -p "$out_dir"
    cp "$jni_build_dir/libtranscribe_jni.so" "$out_dir/libtranscribe_jni.so"

    # Strip debug symbols for a production-sized APK.
    HOST_OS="$(uname -s)"
    case "$HOST_OS" in
        MINGW*|MSYS*|CYGWIN*) HOST_TRIPLE="windows-x86_64" ;;
        *) HOST_TRIPLE="$(printf '%s' "$HOST_OS" | tr '[:upper:]' '[:lower:]')-x86_64" ;;
    esac
    STRIP_BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$HOST_TRIPLE/bin/llvm-strip"
    if [[ ! -x "$STRIP_BIN" ]]; then
        STRIP_BIN="$(find "$ANDROID_NDK/toolchains/llvm/prebuilt" -name llvm-strip -type f | head -n1 || true)"
    fi
    if [[ -n "${STRIP_BIN:-}" && -x "$STRIP_BIN" ]]; then
        "$STRIP_BIN" "$out_dir/libtranscribe_jni.so" || true
    fi
    ls -lh "$out_dir/libtranscribe_jni.so"
done

echo "Done. Native libraries are in $JNI_OUT_ROOT (gitignored)."
