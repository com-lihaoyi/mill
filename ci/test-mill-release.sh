#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build using SBT
sbt bin/test:assembly

# Build Mill using SBT
target/bin/mill all __.publishLocal releaseAssembly

mv out/releaseAssembly/dest/out.jar ~/mill-release

git clean -xdf

# Second build & run tests using Mill

~/mill-release all {main,scalalib,scalajslib}.test
~/mill-release integration.test mill.integration.forked.AmmoniteTests
~/mill-release integration.test "mill.integration.forked.{AcyclicTests,BetterFilesTests,JawnTests,UpickleTests}"
~/mill-release devAssembly
