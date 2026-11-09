#!/usr/bin/env bash
# JNI smoke test on the build machine (Linux x86_64): builds transcribe.cpp + app/src/main/cpp/voicex_jni.cpp for
# the host, loads it into the JVM unit-test runner and transcribes the bundled 5.4 s clip through the real JNI layer.
#
#   scripts/host-jni-test.sh [path/to/parakeet-*.gguf]     (default: .models/parakeet-unified-en-0.6b-Q5_K_M.gguf)
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
model="$(readlink -f "${1:-$root/.models/parakeet-unified-en-0.6b-Q5_K_M.gguf}")"
out="$root/app/build/host-jni"
cmake_bin="${ANDROID_HOME:-$HOME/Android/Sdk}/cmake/4.1.2/bin"
export PATH="$cmake_bin:$PATH"
jdk="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"

[ -f "$model" ] || { echo "model not found: $model (download it from huggingface.co/handy-computer/parakeet-unified-en-0.6b-gguf)"; exit 1; }

cmake -S "$root/app/src/main/cpp/transcribe.cpp" -B "$out/transcribe" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DTRANSCRIBE_BUILD_SHARED=ON -DTRANSCRIBE_BUILD_TESTS=OFF -DTRANSCRIBE_BUILD_EXAMPLES=OFF -DTRANSCRIBE_USE_SYSTEM_BLAS=OFF >/dev/null
cmake --build "$out/transcribe" -j"$(nproc)" --target transcribe
g++ -std=c++17 -O2 -fPIC -shared -Wall -Wextra \
  -I"$jdk/include" -I"$jdk/include/linux" -I"$root/app/src/main/cpp/transcribe.cpp/include" \
  "$root/app/src/main/cpp/voicex_jni.cpp" -L"$out/transcribe/src" -ltranscribe -Wl,-rpath,"$out/transcribe/src" \
  -o "$out/libvoicex_jni.so"
echo "host JNI library: $out/libvoicex_jni.so"

cd "$root"
./gradlew :app:testDebugUnitTest --tests 'com.voicex.app.JniSmokeTest' -i \
  -Dvoicex.hostLib="$out/libvoicex_jni.so" -Dvoicex.model="$model" --console=plain --no-configuration-cache \
  | grep -E "JNI transcript|JniSmokeTest|BUILD|FAILED|skipped" || true
