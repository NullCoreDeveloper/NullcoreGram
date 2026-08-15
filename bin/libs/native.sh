#!/bin/bash

source "bin/init/env.sh"

OUT=TMessagesProj/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib
DIR=TMessagesProj/src/main/libs

export COMPILE_NATIVE=1
./gradlew TMessagesProj:stripReleaseDebugSymbols || exit 1

function install() {
  local ABI="$1"
  local SO_FILE=$(find TMessagesProj/build/intermediates/stripped_native_libs -name "libtmessages*.so" | grep "/$ABI/" | head -n 1)

  if [ -z "$SO_FILE" ] || [ ! -f "$SO_FILE" ]; then
    echo ">> Skip $ABI (not found in stripped_native_libs)"
    # We exit with an error here so the CI workflow actually fails instead of silently generating a broken APK
    exit 1
  fi
  rm -rf $DIR/$ABI
  mkdir -p $DIR/$ABI
  cp "$SO_FILE" $DIR/$ABI/
  echo ">> Install $DIR/$ABI/$(basename "$SO_FILE")"
}

install armeabi-v7a
install arm64-v8a
