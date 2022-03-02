#!/usr/bin/env sh

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Build Mill
ci/publish-local.sh

# Clean up
git stash -u
git stash -a

rm -rf ~/.mill/ammonite

# Patch local build
ci/patch-mill-bootstrap.sh

MILL_RELEASE="$(pwd)/target/mill-release"

# Run tests
MILL_TEST_RELEASE="$MILL_RELEASE" "$MILL_RELEASE" -i integration.test "mill.integration.forked.{AcyclicTests,UpickleTests,PlayJsonTests}"

MILL_TEST_RELEASE="$MILL_RELEASE" "$MILL_RELEASE" -i integration.test "mill.integration.forked.CaffeineTests"
