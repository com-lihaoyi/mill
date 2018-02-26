#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# Run tests using Mill built using SBT
mill integration.test "mill.integration.local.{JawnTests,BetterFilesTests,UpickleTests}"
