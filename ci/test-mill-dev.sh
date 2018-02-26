#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# Build Mill using SBT
mill dev.assembly

# Second build & run tests using Mill
out/dev/assembly/dest/mill -i all {clientserver,main,scalalib,scalajslib}.test

