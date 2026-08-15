#!/bin/bash

source "bin/init/env.sh"

OUT=TMessagesProj/build/intermediates/merged_native_libs/release/mergeReleaseNativeLibs/out/lib
DIR=TMessagesProj/src/main/libs

export COMPILE_NATIVE=1
./gradlew TMessagesProj:stripReleaseDebugSymbols || exit 1

function install() {
  local ABI="$1"
  local SO_FILE=$(find TMessagesProj/build/intermediates/cxx -name "libtmessages*.so" | grep "/$ABI/" | head -n 1)

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
  
  local STRIP_CMD=$(find $ANDROID_HOME/ndk -name "llvm-strip" | head -n 1)
  if [ -n "$STRIP_CMD" ] && [ -x "$STRIP_CMD" ]; then
    $STRIP_CMD "$SO_FILE" -o "$DIR/$ABI/$(basename "$SO_FILE")"
    echo ">> Installed and stripped $DIR/$ABI/$(basename "$SO_FILE")"
  else
    cp "$SO_FILE" $DIR/$ABI/
    echo ">> Installed unstripped $DIR/$ABI/$(basename "$SO_FILE")"
  fi
}

install armeabi-v7a
install arm64-v8a
