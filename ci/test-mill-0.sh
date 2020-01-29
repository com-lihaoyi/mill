#!/usr/bin/env bash

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Run tests

./mill -i all {main,scalalib,scalajslib,contrib.twirllib,contrib.playlib,main.client,contrib.scalapblib,contrib.flyway,contrib.scoverage}.test
