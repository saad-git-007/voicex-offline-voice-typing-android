#!/usr/bin/env bash
# Fetches VoiceX's native build dependencies into third_party/ at the commits VoiceX 1.0.0 was built with.
#
#   scripts/fetch-deps.sh                 Vulkan/SPIR-V/OpenCL headers + the Khronos OpenCL ICD loader (built for arm64)
#   scripts/fetch-deps.sh --hexagon       also the Hexagon SDK 6.7 Community Edition (~3 GB, NPU backend)
#   scripts/fetch-deps.sh --link DIR      symlink an existing third_party/ checkout instead (e.g. ../android/third_party)
#
# The submodule (app/src/main/cpp/transcribe.cpp) is fetched with `git submodule update --init`.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tp="$root/third_party"
mkdir -p "$tp"
git -C "$root" submodule update --init

if [ "${1:-}" = "--link" ]; then
  src="$(readlink -f "$2")"
  for d in Vulkan-Headers SPIRV-Headers OpenCL-Headers opencl-android hexagon; do
    [ -e "$src/$d" ] && ln -sfn "$src/$d" "$tp/$d" && echo "linked $d -> $src/$d"
  done
  exit 0
fi

clone() { # name url commit
  if [ ! -d "$tp/$1/.git" ]; then git clone --filter=blob:none "$2" "$tp/$1"; fi
  git -C "$tp/$1" fetch --quiet origin "$3" 2>/dev/null || true
  git -C "$tp/$1" checkout --quiet "$3"
  echo "$1 @ $3"
}
clone Vulkan-Headers    https://github.com/KhronosGroup/Vulkan-Headers    73814d006638aa00bd553215b054f0f02e457e22
clone SPIRV-Headers     https://github.com/KhronosGroup/SPIRV-Headers     fbf86836a9b1966586faf7be7bb8a6440e156d62
clone OpenCL-Headers    https://github.com/KhronosGroup/OpenCL-Headers    82f60316cc142e7b21dc1d8983b26de18858fbd9
clone OpenCL-ICD-Loader https://github.com/KhronosGroup/OpenCL-ICD-Loader 007c8e892f52e9b9f288830cbce30ab2f97760d0

# OpenCL: link-time ICD loader only (excluded from the APK; the phone's vendor libOpenCL.so is used at run time)
ndk="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/30.0.16248370}"
cmake -S "$tp/OpenCL-ICD-Loader" -B "$tp/opencl-android/build" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=28 \
  -DCMAKE_BUILD_TYPE=Release -DOPENCL_ICD_LOADER_HEADERS_DIR="$tp/OpenCL-Headers" -DOPENCL_ICD_LOADER_BUILD_SHARED_LIBS=ON >/dev/null
cmake --build "$tp/opencl-android/build" --target OpenCL -j
mkdir -p "$tp/opencl-android/lib" && cp "$tp/opencl-android/build/libOpenCL.so" "$tp/opencl-android/lib/"
echo "OpenCL ICD loader built"

if [ "${1:-}" = "--hexagon" ]; then
  mkdir -p "$tp/hexagon"
  gh release download v6.7.0.0 -R snapdragon-toolchain/hexagon-sdk -p 'hexagon-sdk-v6.7.0.0-amd64-lnx.tar.xz' -D "$tp/hexagon" --clobber
  tar xJf "$tp/hexagon/hexagon-sdk-v6.7.0.0-amd64-lnx.tar.xz" -C "$tp/hexagon"
  rm -f "$tp/hexagon/hexagon-sdk-v6.7.0.0-amd64-lnx.tar.xz"
  [ -d "$tp/hexagon/6.7.0.0" ] || echo "WARNING: expected $tp/hexagon/6.7.0.0; move the extracted SDK folder there"
fi
