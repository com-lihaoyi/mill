#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

sbt bin/test:assembly

# Run tests using Mill built using SBT
target/bin/mill --all {core,scalalib,scalajslib,integration}.test devAssembly
