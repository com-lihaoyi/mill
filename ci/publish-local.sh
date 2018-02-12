#!/usr/bin/env bash

set -eux

# First build using SBT
sbt bin/test:assembly

# Build Mill using SBT
target/bin/mill all __.publishLocal releaseAssembly

mv out/releaseAssembly/dest/out.jar ~/mill-release
