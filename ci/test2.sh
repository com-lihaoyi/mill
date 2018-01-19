#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

sbt bin/test:assembly

# Run tests using Mill built using SBT
target/bin/mill --all {core,scalalib,scalajslib}.test devAssembly
target/bin/mill integration.test mill.integration.AmmoniteTests
target/bin/mill integration.test "mill.integration.{AcyclicTests,BetterFilesTests,JawnTests}"
target/bin/mill devAssembly
