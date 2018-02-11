#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

sbt bin/test:assembly

# Run tests using Mill built using SBT
target/bin/mill all {main,scalalib,scalajslib}.test
target/bin/mill integration.test mill.integration.local.AmmoniteTests
target/bin/mill integration.test "mill.integration.local.{AcyclicTests,BetterFilesTests,JawnTests,UpickleTests}"
target/bin/mill devAssembly
