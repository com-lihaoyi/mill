#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build & run tests using SBT
sbt core/test scalaplugin/test bin/test:assembly

# Second build & run tests using Mill built using SBT
bin/target/mill Core.test
bin/target/mill ScalaPlugin.test
bin/target/mill devAssembly

# Third build & run tests using Mill built using Mill
out/devAssembly Core.test
out/devAssembly ScalaPlugin.test
out/devAssembly devAssembly