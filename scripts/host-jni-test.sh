#!/usr/bin/env bash
# JNI smoke test on the build machine (Linux x86_64): builds transcribe.cpp + app/src/main/cpp/voicex_jni.cpp for
# the host, loads it into the JVM unit-test runner and transcribes the bundled 5.4 s clip through the real JNI layer.
# If .models/ggml-large-v3-turbo-q5_0.bin (Whisper, used for Urdu) is present, the Urdu clip is transcribed too.
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

# same patches the Android build applies (app/src/main/cpp/CMakeLists.txt); skipped when already applied
for p in "$root"/patches/*.patch; do
  git -C "$root/app/src/main/cpp/transcribe.cpp" apply --reverse --check "$p" 2>/dev/null || git -C "$root/app/src/main/cpp/transcribe.cpp" apply "$p"
done
cmake -S "$root/app/src/main/cpp/transcribe.cpp" -B "$out/transcribe" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  -DTRANSCRIBE_BUILD_SHARED=ON -DTRANSCRIBE_BUILD_TESTS=OFF -DTRANSCRIBE_BUILD_EXAMPLES=OFF -DTRANSCRIBE_USE_SYSTEM_BLAS=OFF >/dev/null
cmake --build "$out/transcribe" -j"$(nproc)" --target transcribe
g++ -std=c++17 -O2 -fPIC -shared -Wall -Wextra \
  -I"$jdk/include" -I"$jdk/include/linux" -I"$root/app/src/main/cpp/transcribe.cpp/include" \
  "$root/app/src/main/cpp/voicex_jni.cpp" -L"$out/transcribe/src" -ltranscribe -Wl,-rpath,"$out/transcribe/src" \
  -o "$out/libvoicex_jni.so"
echo "host JNI library: $out/libvoicex_jni.so"

cd "$root"
ur_model="$root/.models/ggml-large-v3-turbo-q5_0.bin"
ur_arg=()
[ -f "$ur_model" ] && ur_arg=(-Dvoicex.urModel="$ur_model")
./gradlew :app:testDebugUnitTest --tests 'com.voicex.app.JniSmokeTest' -i \
  -Dvoicex.hostLib="$out/libvoicex_jni.so" -Dvoicex.model="$model" "${ur_arg[@]}" --console=plain --no-configuration-cache \
  | grep -E "JNI transcript|JNI Urdu|word errors|JniSmokeTest|BUILD|FAILED|skipped" || true
