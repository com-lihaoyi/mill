#!/usr/bin/env bash

# This is the launcher script of scalafmt-native-image (https://github.com/VirtusLab/scalafmt-native-image).
# This script downloads and runs scalafmt-native-image version set by SCALAFMT_VERSION below.
#
# Download the latest version of this script at https://github.com/VirtusLab/scalafmt-native-image/raw/main/scalafmt.sh

set -eu

DEFAULT_SCALAFMT_VERSION="3.8.3"

SCALAFMT_VERSION=""
if test -e .scalafmt.conf; then
  SCALAFMT_VERSION="$(cat .scalafmt.conf | grep '^version\s*=\s*"[0-9.A-Z-]*"$' | sed 's/^version[ \t]*=[ \t]*"\([0-9.A-Z-]*\)"$/\1/g')"
fi

if [ "$SCALAFMT_VERSION" == "" ]; then
  SCALAFMT_VERSION="$DEFAULT_SCALAFMT_VERSION"
  if test -e .scalafmt.conf; then
    echo "Warning: no scalafmt version found in .scalafmt.conf, using $SCALAFMT_VERSION" 1>&2
  else
    echo "Warning: no .scalafmt.conf found, using scalafmt version $SCALAFMT_VERSION" 1>&2
  fi
fi

GH_ORG="VirtusLab"
GH_NAME="scalafmt-native-image"

TAG="v$SCALAFMT_VERSION"

if [ "$(expr substr $(uname -s) 1 5 2>/dev/null)" == "Linux" ]; then
  arch=$(uname -m)
  if [[ "$arch" == "aarch64" ]] || [[ "$arch" == "x86_64" ]]; then
    SCALAFMT_URL="https://github.com/$GH_ORG/$GH_NAME/releases/download/$TAG/scalafmt-${arch}-pc-linux.gz"
  else
    echo "scalafmt-native-image is not supported on $arch" 1>&2
    exit 2
  fi
  CACHE_BASE="$HOME/.cache/coursier/v1"
elif [ "$(uname)" == "Darwin" ]; then
  arch=$(uname -m)
  CACHE_BASE="$HOME/Library/Caches/Coursier/v1"
  if [[ "$arch" == "x86_64" ]]; then
    SCALAFMT_URL="https://github.com/$GH_ORG/$GH_NAME/releases/download/$TAG/scalafmt-x86_64-apple-darwin.gz"
  elif [[ "$arch" == "arm64" ]]; then
    SCALAFMT_URL="https://github.com/$GH_ORG/$GH_NAME/releases/download/$TAG/scalafmt-aarch64-apple-darwin.gz"
  else
    echo "scalafmt-native-image is not supported on $arch" 1>&2
    exit 2
  fi
else
  echo "This standalone scalafmt-native-image launcher is supported only in Linux and macOS." 1>&2
  exit 1
fi

CACHE_DEST="$CACHE_BASE/$(echo "$SCALAFMT_URL" | sed 's@://@/@')"
SCALAFMT_BIN_PATH=${CACHE_DEST%.gz}

if [ ! -f "$CACHE_DEST" ]; then
  mkdir -p "$(dirname "$CACHE_DEST")"
  TMP_DEST="$CACHE_DEST.tmp-setup"
  echo "Downloading $SCALAFMT_URL" 1>&2
  curl -fLo "$TMP_DEST" "$SCALAFMT_URL"
  mv "$TMP_DEST" "$CACHE_DEST"
fi

if [ ! -f "$SCALAFMT_BIN_PATH" ]; then
  gunzip -k "$CACHE_DEST"
fi

if [ ! -x "$SCALAFMT_BIN_PATH" ]; then
  chmod +x "$SCALAFMT_BIN_PATH"
fi

exec "$SCALAFMT_BIN_PATH" "$@"
