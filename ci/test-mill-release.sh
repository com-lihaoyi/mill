#!/usr/bin/env sh

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Build Mill
ci/publish-local.sh

# Clean up
git stash -a -m "preserve mill-release" -- target/mill-release
git stash -u
git stash -a
git stash pop "$(git stash list | grep "preserve mill-release" | head -n1 | sed -E 's/([^:]+):.*/\1/')"

rm -rf ~/.mill/ammonite

# Patch local build
ci/patch-mill-bootstrap.sh

MILL_RELEASE="$(pwd)/target/mill-release"

# Run tests
MILL_TEST_RELEASE="$MILL_RELEASE" "$MILL_RELEASE" -i integration.test "mill.integration.forked.{AcyclicTests,UpickleTests,PlayJsonTests}"

MILL_TEST_RELEASE="$MILL_RELEASE" "$MILL_RELEASE" -i integration.test "mill.integration.forked.CaffeineTests"
