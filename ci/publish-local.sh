#!/usr/bin/env bash

set -eux

# First build using SBT
sbt bin/test:assembly

# Build Mill using SBT
target/bin/mill all __.publishLocal release

mv out/release/dest/out.jar ~/mill-release
