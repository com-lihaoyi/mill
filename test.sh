#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build & run tests using SBT
sbt core/test scalaplugin/test scalajsplugin/test bin/test:assembly

# Build Mill using SBT
bin/target/mill devAssembly

# Second build & run tests using Mill
out/devAssembly/dest Core.test
out/devAssembly/dest ScalaPlugin.test
out/devAssembly/dest ScalaJSPlugin.test
out/devAssembly/dest devAssembly