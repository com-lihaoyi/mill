#!/usr/bin/env sh

set -eux

EXAMPLE=example/scalabuilds/10-scala-realistic

rm -rf $EXAMPLE/out

test ! -d $EXAMPLE/out/foo/3.2.2/compile.dest
test ! -f $EXAMPLE/out/bar/2.13.8/assembly.dest/out.jar

$MILL_RUNNER -i dev.run $EXAMPLE -i "foo[3.2.2].run"

test -d $EXAMPLE/out/foo/3.2.2/compile.dest

$MILL_RUNNER -i dev.run $EXAMPLE show "bar[2.13.8].assembly"

test -f $EXAMPLE/out/bar/2.13.8/assembly.dest/out.jar
