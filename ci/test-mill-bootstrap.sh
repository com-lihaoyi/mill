#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build
mill -i all __.publishLocal release
mv out/release/dest/mill ~/mill-1

# Clean up
git clean -xdf

rm -rf ~/.mill

# Differentiate first and second builds
echo "Build 2" > info.txt && git add info.txt && git commit -m "Add info.txt"

# Second build
~/mill-1 -i all __.publishLocal release
mv out/release/dest/mill ~/mill-2

# Clean up
git clean -xdf

rm -rf ~/.mill

# Use second build to run tests using Mill
~/mill-2 -i all {main,scalalib,scalajslib,contrib.twirllib,contrib.playlib,contrib.scalapblib}.test
