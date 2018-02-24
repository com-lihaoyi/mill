#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

sbt bin/test:assembly

# Run tests using Mill built using SBT
target/bin/mill clientserver.test
#target/bin/mill all {clientserver,main,scalalib,scalajslib}.test
#target/bin/mill integration.test "mill.integration.local.{AcyclicTests,JawnTests,UpickleTests}"
#target/bin/mill dev.assembly
