#!/usr/bin/env sh

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Build Mill
./mill -i dev.launcher

rm -rf ~/.mill/ammonite

# Prepare local build
ci/patch-mill-bootstrap.sh

# Second build & run tests
out/dev/launcher.dest/run -i "__.compile"
out/dev/launcher.dest/run -i "{main,scalalib}.__.test"
out/dev/launcher.dest/run -i "example.basic[1-simple-scala].server.test"
