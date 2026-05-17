#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-/workspace/android-sdk}"
CMDLINE_VER="11076708"
TOOLS_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_VER}_latest.zip"

mkdir -p "${SDK_ROOT}/cmdline-tools"
if [ ! -x "${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" ]; then
  curl -L -o "${SDK_ROOT}/cmdline-tools/tools.zip" "${TOOLS_ZIP_URL}"
  unzip -q -o "${SDK_ROOT}/cmdline-tools/tools.zip" -d "${SDK_ROOT}/cmdline-tools"
  mkdir -p "${SDK_ROOT}/cmdline-tools/latest"
  mv "${SDK_ROOT}/cmdline-tools/cmdline-tools"/* "${SDK_ROOT}/cmdline-tools/latest/"
fi

yes | "${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" --sdk_root="${SDK_ROOT}" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" >/dev/null || true

cat > local.properties <<EOP
sdk.dir=${SDK_ROOT}
EOP

echo "Android SDK is ready at ${SDK_ROOT}"
echo "Use: ANDROID_HOME=${SDK_ROOT} ANDROID_SDK_ROOT=${SDK_ROOT} ./gradlew :app:assembleDebug"
