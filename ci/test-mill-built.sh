#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

ci/publish-local.sh

# Build Mill using SBT
target/bin/mill dev.assembly

# Second build & run tests using Mill


out/dev/assembly/dest/out.jar all {clientserver,main,scalalib,scalajslib}.test
out/dev/assembly/dest/out.jar integration.test "mill.integration.forked.{AmmoniteTests,BetterFilesTests}"
out/dev/assembly/dest/out.jar dev.assembly
