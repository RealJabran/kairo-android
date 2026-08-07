#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS_DIR="$PROJECT_DIR/.build-tools"
SDK_DIR="$PROJECT_DIR/.android-sdk"
OUTPUT_DIR="$PROJECT_DIR/output"
GRADLE_VERSION="9.1.0"
GRADLE_ZIP="$TOOLS_DIR/gradle-$GRADLE_VERSION-bin.zip"
GRADLE_HOME="$TOOLS_DIR/gradle-$GRADLE_VERSION"
JDK_ARCHIVE="$TOOLS_DIR/temurin-jdk-17.tar.gz"
JDK_HOME="$TOOLS_DIR/jdk-17.0.19+10"

mkdir -p "$TOOLS_DIR" "$SDK_DIR" "$OUTPUT_DIR"
mkdir -p "$SDK_DIR/licenses"
if [[ ! -s "$SDK_DIR/licenses/android-sdk-license" ]]; then
  printf '%s\n' 'd56f5187479451eabf01fb78af6dfcb131a6481e' '24333f8a63b6825ea9c5514f83c2829b004d1fee' > "$SDK_DIR/licenses/android-sdk-license"
fi

if [[ ! -x "$JDK_HOME/bin/javac" ]]; then
  if [[ ! -s "$JDK_ARCHIVE" ]]; then
    echo "Downloading the private Temurin JDK 17 build tool..."
    wget -c -O "$JDK_ARCHIVE" "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.19%2B10/OpenJDK17U-jdk_x64_linux_hotspot_17.0.19_10.tar.gz"
  fi
  tar -xzf "$JDK_ARCHIVE" -C "$TOOLS_DIR"
fi

export JAVA_HOME="$JDK_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  if [[ ! -s "$GRADLE_ZIP" ]]; then
    echo "Downloading Gradle $GRADLE_VERSION..."
    wget -c -O "$GRADLE_ZIP" "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  fi
  echo "Extracting Gradle..."
  unzip -q -o "$GRADLE_ZIP" -d "$TOOLS_DIR"
fi

printf 'sdk.dir=%s\n' "$SDK_DIR" > "$PROJECT_DIR/local.properties"

echo "Building Kairo..."
"$GRADLE_HOME/bin/gradle" --no-daemon -Pandroid.builder.sdkDownload=true :app:assembleDebug

cp "$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk" "$OUTPUT_DIR/Kairo-1.3.0-test.apk"
echo
echo "APK ready: $OUTPUT_DIR/Kairo-1.3.0-test.apk"
