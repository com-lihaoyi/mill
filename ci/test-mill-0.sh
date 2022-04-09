#!/usr/bin/env bash

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Run tests

./mill -i -k "{main.__,scalalib,scalajslib,scalanativelib,testrunner,bsp,contrib._}.test"
