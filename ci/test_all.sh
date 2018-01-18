#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build & run tests using SBT
sbt core/test scalalib/test scalajslib/test integration/test bin/test:assembly

# Run tests using Mill built using SBT
target/bin/mill --all {core,scalalib,scalajslib,integration}.test devAssembly

# Second build & run tests using Mill
out/devAssembly/dest --all {core,scalalib,scalajslib,integration}.test devAssembly