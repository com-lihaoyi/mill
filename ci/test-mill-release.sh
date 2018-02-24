#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

ci/publish-local.sh

git clean -xdf

# Second build & run tests using Mill

~/mill-release all {clientserver,main,scalalib,scalajslib}.test
~/mill-release integration.test "mill.integration.forked.{AcyclicTests,JawnTests,UpickleTests}"
~/mill-release dev.assembly
