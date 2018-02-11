#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build & run tests using SBT
sbt core/test main/test scalalib/test scalajslib/test
sbt "integration/test-only -- mill.integration.local"
sbt bin/test:assembly
