#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build using SBT
sbt bin/test:assembly

# Build Mill using SBT
target/bin/mill devAssembly

# Second build & run tests using Mill

out/devAssembly/dest/out.jar all {core,scalalib,scalajslib}.test devAssembly
out/devAssembly/dest/out.jar integration.test mill.integration.AmmoniteTests
out/devAssembly/dest/out.jar integration.test "mill.integration.{AcyclicTests,BetterFilesTests,JawnTests}"
out/devAssembly/dest/out.jar devAssembly
