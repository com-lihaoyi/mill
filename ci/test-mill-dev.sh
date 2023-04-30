#!/usr/bin/env sh

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Build Mill
./mill -i dev.launcher

EXAMPLE=example/scalabuilds/10-scala-realistic

test ! -d $EXAMPLE/out/foo/3.2.2/compile.dest
test ! -f $EXAMPLE/out/bar/2.13.8/assembly.dest/out.jar

./mill -i dev.run $EXAMPLE -i "foo[3.2.2].run"

test -d $EXAMPLE/out/foo/3.2.2/compile.dest/out.jar

./mill -i dev.run $EXAMPLE show "bar[2.13.8].assembly"

test -f $EXAMPLE/out/bar/2.13.8/assembly.dest/out.jar
