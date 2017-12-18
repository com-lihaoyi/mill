#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build & run tests using SBT
sbt core/test scalaplugin/test bin/test:assembly

# Second build & run tests using Mill built using SBT
bin/target/mill run Core.test
bin/target/mill run ScalaPlugin.test
bin/target/mill run assembly

# Third build & run tests using Mill built using Mill
out/assembly run Core.test
out/assembly run ScalaPlugin.test
out/assembly run assembly