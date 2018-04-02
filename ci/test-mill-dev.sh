#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# Build Mill
mill -i dev.assembly

rm -rf ~/.mill

# Second build & run tests
out/dev/assembly/dest/mill -i all {main,scalalib,scalajslib,twirllib}.test

