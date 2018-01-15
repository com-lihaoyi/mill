#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf


sbt bin/test:assembly
# Run tests using
target/bin/mill core.test
target/bin/mill scalalib.test
target/bin/mill scalajslib.test
target/bin/mill integration.test
target/bin/mill devAssembly
