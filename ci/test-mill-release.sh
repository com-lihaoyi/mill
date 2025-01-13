#!/usr/bin/env sh

set -eux

# Build Mill
./mill -i dist.installLocal

EXAMPLE=example/scalalib/basic/6-realistic

rm -rf $EXAMPLE/out

test ! -d $EXAMPLE/out/foo/3.3.3/compile.dest
test ! -f $EXAMPLE/out/bar/2.13.8/assembly.dest/out.jar

(cd $EXAMPLE && ../../../../mill-assembly.jar -i "foo[3.3.3].run")

test -d $EXAMPLE/out/foo/3.3.3/compile.dest

(cd $EXAMPLE && ../../../../mill-assembly.jar show "bar[2.13.8].assembly")

test -f $EXAMPLE/out/bar/2.13.8/assembly.dest/out.jar

(cd $EXAMPLE && ../../../../mill-assembly.jar shutdown)