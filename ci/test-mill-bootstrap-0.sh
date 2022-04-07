#!/usr/bin/env sh

set -eux

# Starting from scratch...
git stash -u
git stash -a

# First build
./mill -i -j 0 installLocal --binFile target/mill-1

# Clean up
git stash -a -m "preserve mill-1" -- target/mill-1
git stash -u
git stash -a
git stash pop "$(git stash list | grep "preserve mill-1" | head -n1 | sed -E 's/([^:]+):.*/\1/')"

rm -rf ~/.mill/ammonite

# Differentiate first and second builds
git config user.name "Your Name"
echo "Build 2" > info.txt && git add info.txt && git commit -m "Add info.txt"

# Patch local build
ci/patch-mill-bootstrap.sh

# Second build
target/mill-1 -i -j 0 installLocal --binFile target/mill-2

# Clean up
git stash -a -m "preserve mill-2" -- target/mill-2
git stash -u
git stash -a
git stash pop "$(git stash list | grep "preserve mill-2" | head -n1 | sed -E 's/([^:]+):.*/\1/')"

rm -rf ~/.mill/ammonite

# Patch local build
ci/patch-mill-bootstrap.sh

# Use second build to run tests using Mill
target/mill-2 -i "{main,scalalib,scalajslib,scalanativelib,bsp}.__.test"
