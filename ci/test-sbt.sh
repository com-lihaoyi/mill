#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build & run tests using SBT
sbt core/test clientserver/test main/test scalalib/test scalajslib/test
sbt "integration/test-only -- mill.integration.local.{AmmoniteTests,BetterFilesTests}"
sbt bin/test:assembly
