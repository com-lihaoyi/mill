#!/usr/bin/env bash

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Build Mill
ci/publish-local.sh

# Clean up
git stash -u
git stash -a

rm -rf ~/.mill

# Run tests
~/mill-release -i integration.test "mill.integration.forked.{AcyclicTests,UpickleTests,PlayJsonTests}"

~/mill-release -i integration.test "mill.integration.forked.CaffeineTests"
