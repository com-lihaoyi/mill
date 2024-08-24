#!/usr/bin/env sh

set -eux

# Build Mill
./mill -i dev.assembly

EXAMPLE=example/scalalib/builds/9-realistic

rm -rf $EXAMPLE/out

test ! -d $EXAMPLE/out/foo/3.3.3/compile.dest
test ! -f $EXAMPLE/out/bar/2.13.8/assembly.dest/out.jar

(cd $EXAMPLE && ../../../out/dev/assembly.dest/mill -i "foo[3.3.3].run")

test -d $EXAMPLE/out/foo/3.3.3/compile.dest

(cd $EXAMPLE && ../../../out/dev/assembly.dest/mill show "bar[2.13.8].assembly")

test -f $EXAMPLE/out/bar/2.13.8/assembly.dest/out.jar
