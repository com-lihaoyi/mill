#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# Run tests
mill -i all {main,scalalib,scalajslib,twirllib,main.client}.test
