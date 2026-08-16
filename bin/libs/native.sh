#!/bin/bash

source "bin/init/env.sh"

OUT=TMessagesProj/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib
DIR=TMessagesProj/src/main/libs

export COMPILE_NATIVE=1
./gradlew TMessagesProj:stripReleaseDebugSymbols || exit 1

function install() {
  local ABI="$1"
  # Find the largest .so file to avoid picking 0-byte ninja marker files
  local SO_FILE=$(find TMessagesProj/build/intermediates/cxx -name "libtmessages*.so" -type f | grep "/$ABI/" | xargs -r ls -s | sort -n | tail -n 1 | awk '{print $2}')

  if [ -z "$SO_FILE" ] || [ ! -f "$SO_FILE" ]; then
    if [ -n "$NATIVE_TARGET" ] && [ "$NATIVE_TARGET" != "$ABI" ]; then
      echo ">> Skip $ABI (not the target for this run)"
      return 0
    fi
    echo ">> Skip $ABI (not found in cxx - THIS IS AN ERROR!)"
    exit 1
  fi
  rm -rf $DIR/$ABI
  mkdir -p $DIR/$ABI
  
  # Copy the binary directly. 
  # Android Gradle Plugin will automatically strip it during the assembleRelease task.
  cp "$SO_FILE" $DIR/$ABI/
  echo ">> Installed $DIR/$ABI/$(basename "$SO_FILE")"
  
  if [ ! -f "$DIR/$ABI/$(basename "$SO_FILE")" ]; then
    echo ">> FATAL: Failed to install $ABI binary!"
    exit 1
  fi
}

install armeabi-v7a
install arm64-v8a
