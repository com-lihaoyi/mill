#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# Build Mill
mill -i dev.assembly

# Second build & run tests
out/dev/assembly/dest/mill -i all {clientserver,main,scalalib,scalajslib}.test

