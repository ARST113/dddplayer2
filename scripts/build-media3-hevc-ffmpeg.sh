#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/.media3-hevc-ffmpeg"
MEDIA="$WORK/media"
DONOR="$WORK/donor"
OUT="$ROOT/.justplus-upstream/app/libs/lib-decoder-ffmpeg-release.aar"

MEDIA_TAG="1.11.0-beta01"
DONOR_COMMIT="57346bbf36a7456e99008298cf55ce16011401db"

rm -rf "$WORK"
mkdir -p "$WORK"

echo "==> Clone Media3 $MEDIA_TAG"
git clone --depth 1 --branch "$MEDIA_TAG" https://github.com/androidx/media.git "$MEDIA"

echo "==> Fetch tested FFmpeg video renderer implementation"
git init -q "$DONOR"
git -C "$DONOR" remote add origin https://github.com/rabbitknight/media.git
git -C "$DONOR" fetch -q --depth 1 origin "$DONOR_COMMIT"
git -C "$DONOR" checkout -q FETCH_HEAD

MOD="libraries/decoder_ffmpeg"
cp "$DONOR/$MOD/src/main/java/androidx/media3/decoder/ffmpeg/ExperimentalFfmpegVideoRenderer.java" \
   "$MEDIA/$MOD/src/main/java/androidx/media3/decoder/ffmpeg/ExperimentalFfmpegVideoRenderer.java"
cp "$DONOR/$MOD/src/main/java/androidx/media3/decoder/ffmpeg/ExperimentalFfmpegVideoDecoder.java" \
   "$MEDIA/$MOD/src/main/java/androidx/media3/decoder/ffmpeg/ExperimentalFfmpegVideoDecoder.java"
cp "$DONOR/$MOD/src/main/jni/ffmpeg_jni.cc" "$MEDIA/$MOD/src/main/jni/ffmpeg_jni.cc"
cp "$DONOR/$MOD/src/main/jni/CMakeLists.txt" "$MEDIA/$MOD/src/main/jni/CMakeLists.txt"

JNI="$MEDIA/$MOD/src/main/jni"
NDK_VERSION="27.0.12077973"
NDK_PATH="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/ndk/$NDK_VERSION"
if [[ ! -d "$NDK_PATH" ]]; then
  printf "y\n" | "${ANDROID_SDK_ROOT:-${ANDROID_HOME}}/cmdline-tools/latest/bin/sdkmanager" "ndk;$NDK_VERSION" >/dev/null
fi
HOST_PLATFORM="linux-x86_64"
API=24
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/$HOST_PLATFORM/bin"

echo "==> Build FFmpeg 6.0 arm64 with audio + HEVC software decoders"
git clone -q --depth 1 --branch release/6.0 https://github.com/FFmpeg/FFmpeg.git "$JNI/ffmpeg"
pushd "$JNI/ffmpeg" >/dev/null
./configure \
  --target-os=android \
  --enable-cross-compile \
  --cross-prefix="$TOOLCHAIN/aarch64-linux-android${API}-" \
  --arch=aarch64 \
  --cpu=armv8-a \
  --cc="$TOOLCHAIN/aarch64-linux-android${API}-clang" \
  --cxx="$TOOLCHAIN/aarch64-linux-android${API}-clang++" \
  --ar="$TOOLCHAIN/llvm-ar" \
  --nm="$TOOLCHAIN/llvm-nm" \
  --ranlib="$TOOLCHAIN/llvm-ranlib" \
  --strip="$TOOLCHAIN/llvm-strip" \
  --libdir="android-libs/arm64-v8a" \
  --target-os=android \
  --disable-static --enable-shared \
  --disable-doc --disable-programs --disable-avdevice \
  --disable-everything \
  --enable-avcodec --enable-avutil --enable-swresample --enable-swscale \
  --disable-postproc --disable-avfilter --disable-symver --disable-v4l2-m2m --disable-vulkan \
  --enable-decoder=vorbis --enable-decoder=opus --enable-decoder=flac --enable-decoder=alac \
  --enable-decoder=pcm_mulaw --enable-decoder=pcm_alaw --enable-decoder=mp3 \
  --enable-decoder=amrnb --enable-decoder=amrwb --enable-decoder=aac \
  --enable-decoder=ac3 --enable-decoder=eac3 --enable-decoder=dca \
  --enable-decoder=mlp --enable-decoder=truehd \
  --enable-decoder=hevc
make -j2
make install-libs
popd >/dev/null

echo "==> Build libyuv arm64"
git clone -q --depth 1 https://chromium.googlesource.com/libyuv/libyuv "$JNI/libyuv"
# NDK 27/Clang 18 can pass libyuv's C SME probe but cannot compile the C++
# __arm_new("za") implementation used by current rotate_sme.cc. SME is only
# an optimization; disable that object set and keep NEON/SVE paths.
sed -i 's/if (CAN_COMPILE_SME)/if (FALSE)/' "$JNI/libyuv/CMakeLists.txt"
mkdir -p "$JNI/libyuv/build-arm64-v8a"
pushd "$JNI/libyuv/build-arm64-v8a" >/dev/null
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_PATH/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-$API \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON \
  -DANDROID_STL=c++_shared \
  -DCAN_COMPILE_SME=FALSE \
  -DCMAKE_C_FLAGS="-DLIBYUV_DISABLE_SME" \
  -DCMAKE_CXX_FLAGS="-DLIBYUV_DISABLE_SME"
cmake --build . -j2
popd >/dev/null
mkdir -p "$JNI/libyuv/android-libs/arm64-v8a"
cp "$JNI/libyuv/build-arm64-v8a/libyuv.so" "$JNI/libyuv/android-libs/arm64-v8a/libyuv.so"

echo "==> Limit FFmpeg extension to the ABI actually built for this transition APK"
pushd "$MEDIA" >/dev/null
python3 - <<'PY'
from pathlib import Path
p = Path("libraries/decoder_ffmpeg/build.gradle.kts")
s = p.read_text()
if "abiFilters 'arm64-v8a'" not in s:
    marker = "android {\n"
    if marker not in s:
        raise SystemExit("decoder_ffmpeg android block not found")
    s = s.replace(marker, "android {\n  defaultConfig {\n    ndk { abiFilters += listOf(\"arm64-v8a\") }\n  }\n", 1)
p.write_text(s)
PY

echo "==> Build Media3 FFmpeg AAR against exact Just+ Media3 version"
chmod +x ./gradlew
./gradlew :lib-decoder-ffmpeg:assembleRelease --stacktrace
AAR="$(find libraries/decoder_ffmpeg -type f -name '*.aar' | grep -i release | head -n1)"
test -n "$AAR"
mkdir -p "$(dirname "$OUT")"
cp "$AAR" "$OUT"
popd >/dev/null

echo "HEVC-capable FFmpeg extension: $OUT"
