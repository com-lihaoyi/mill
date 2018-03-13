#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# Run tests using Mill built using SBT
mill all {clientserver,main,scalalib,scalajslib}.test
