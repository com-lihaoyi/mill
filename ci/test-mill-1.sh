#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# Run tests
mill integration.test "mill.integration.local.{JawnTests,BetterFilesTests,UpickleTests}"
