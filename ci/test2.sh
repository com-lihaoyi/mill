#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf


sbt bin/test:assembly
# Run tests using
target/bin/mill --all {core,scalalib,scalajslib,integration}.test devAssembly
