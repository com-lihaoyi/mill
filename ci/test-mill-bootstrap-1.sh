#!/usr/bin/env sh

set -eux

# Starting from scratch...
git stash -u
git stash -a

# First build
./mill -i "__.publishLocal" + assembly
cp out/assembly.dest/mill target/mill-1

# Clean up
git stash -u
git stash -a

rm -rf ~/.mill/ammonite

# Differentiate first and second builds
git config user.name "Your Name"
echo "Build 2" > info.txt && git add info.txt && git commit -m "Add info.txt"

# Patch local build
ci/patch-mill-bootstrap.sh

# Second build
target/mill-1 -i "__.publishLocal" + assembly
cp out/assembly.dest/mill target/mill-2

# Clean up
git stash -u
git stash -a

rm -rf ~/.mill/ammonite

# Patch local build
ci/patch-mill-bootstrap.sh

# Use second build to run tests using Mill
target/mill-2 -i "contrib.__.test"
