#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build & run tests using SBT
sbt bin/test:assembly

# Build Mill using SBT
target/bin/mill devAssembly

# Second build & run tests using Mill
out/devAssembly/dest --all {core,scalalib,scalajslib,integration}.test devAssembly
