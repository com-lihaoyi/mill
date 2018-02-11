#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build using SBT
sbt bin/test:assembly

# Build Mill using SBT
target/bin/mill devAssembly

# Second build & run tests using Mill

out/devAssembly/dest/out.jar all {main,scalalib,scalajslib}.test devAssembly
out/devAssembly/dest/out.jar integration.test mill.integration.local.AmmoniteTests
out/devAssembly/dest/out.jar integration.test "mill.integration.local.{AcyclicTests,BetterFilesTests,JawnTests,UpickleTests}"
out/devAssembly/dest/out.jar devAssembly
