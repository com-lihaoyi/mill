#!/usr/bin/env sh

set -eux

./mill -i "$@" __.publishLocal + assembly

./mill -i show main.publishVersion

mkdir -p target

cp out/assembly.dest/mill target/mill-release
