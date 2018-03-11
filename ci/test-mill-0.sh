#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# Run tests using Mill built using SBT
mill -i all {clientserver,main,scalalib,scalajslib}.test
