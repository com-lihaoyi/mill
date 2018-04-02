#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# Build Mill
ci/publish-local.sh

# Clean up
git clean -xdf

# Run tests
~/mill-release -i integration.test "mill.integration.forked.{AcyclicTests,UpickleTests,PlayJsonTests}"
