#!/usr/bin/env sh

set -eux

EXAMPLE=example/scalalib/builds/4-realistic

rm -rf $EXAMPLE/out

test ! -d $EXAMPLE/out/foo/3.3.3/compile.dest
test ! -f $EXAMPLE/out/bar/2.13.8/assembly.dest/out.jar

./mill -i dist.run $EXAMPLE -i "foo[3.3.3].run"

test -d $EXAMPLE/out/foo/3.3.3/compile.dest

./mill -i dist.run $EXAMPLE show "bar[2.13.8].assembly"

test -f $EXAMPLE/out/bar/2.13.8/assembly.dest/out.jar

./mill -i dist.run $EXAMPLE shutdown