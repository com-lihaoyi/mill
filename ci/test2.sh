#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf


sbt bin/test:assembly
# Run tests using
bin/target/mill core.test
bin/target/mill scalalib.test
bin/target/mill scalajslib.test
bin/target/mill integration.test
bin/target/mill devAssembly
