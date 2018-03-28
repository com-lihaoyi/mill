#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# Build Mill
mill -i dev.assembly

rm -fR ~/.mill

# Second build & run tests using Mill
out/dev/assembly/dest/mill -i all {clientserver,main,scalalib,scalajslib}.test

