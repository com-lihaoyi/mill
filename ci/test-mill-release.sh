#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# Build Mill
ci/publish-local.sh

# Clean up
git clean -xdf

rm -rf ~/.mill

# Run tests
~/mill-release -i integration.test "mill.integration.forked.{AcyclicTests,UpickleTests,PlayJsonTests}"

~/mill-release -i integration.test "mill.integration.local.CaffeineTests"