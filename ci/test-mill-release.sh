#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

ci/publish-local.sh

git clean -xdf

rm -fR ~/.mill

# Second build & run tests using Mill

~/mill-release -i integration.test "mill.integration.forked.{AcyclicTests,UpickleTests}"
