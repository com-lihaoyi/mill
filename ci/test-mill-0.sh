#!/usr/bin/env bash

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Run tests

./mill -i __.checkFormat
./mill -i all {main,scalalib,scalajslib,scalanativelib,contrib.twirllib,contrib.playlib,main.client,contrib.scalapblib,contrib.flyway,contrib.scoverage}.test
