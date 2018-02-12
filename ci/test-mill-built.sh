#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

ci/publish-local.sh

# Build Mill using SBT
target/bin/mill devAssembly

# Second build & run tests using Mill

out/devAssembly/dest/out.jar all {main,scalalib,scalajslib}.test devAssembly
out/devAssembly/dest/out.jar integration.test "mill.integration.forked.{AmmoniteTests,BetterFilesTests}"
out/devAssembly/dest/out.jar devAssembly
