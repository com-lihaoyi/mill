#!/usr/bin/env sh

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Build Mill
./mill -i dev.assembly

rm -rf ~/.mill/ammonite

# Patch local build
ci/patch-mill-bootstrap.sh

# Second build & run tests
out/dev/assembly.dest/mill -i -j 0 main.test.compile

out/dev/assembly.dest/mill -i "{main,scalalib,scalajslib,scalanativelib,bsp,contrib.twirllib,contrib.scalapblib}.test"
out/dev/assembly.dest/mill -i "example.basic[1-hello-world].server.test"
