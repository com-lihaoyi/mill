#!/usr/bin/env sh

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Build Mill
./mill -i installLocal

# Clean up
git stash -a -m "preserve mill-release" -- target/mill-release
git stash -u
git stash -a
git stash pop "$(git stash list | grep "preserve mill-release" | head -n1 | sed -E 's/([^:]+):.*/\1/')"

# Prepare local build
ci/prepare-mill-bootstrap.sh

# Run tests
target/mill-release -i "__.compile"
target/mill-release -i "{main,scalalib}.__.test"
target/mill-release -i "example.basic[1-simple-scala].server.test"
