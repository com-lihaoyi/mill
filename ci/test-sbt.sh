#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build & run tests using SBT
sbt core/test scalalib/test scalajslib/test
sbt integration/test
sbt bin/test:assembly
