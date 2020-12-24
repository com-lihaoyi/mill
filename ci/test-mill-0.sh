#!/usr/bin/env bash

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Run tests

./mill -i all {main.__,scalalib,scalajslib,scalanativelib,bsp,contrib._}.test
