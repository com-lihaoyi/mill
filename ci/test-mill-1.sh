#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# Run tests
./mill integration.test "mill.integration.local.JawnTests"
./mill integration.test "mill.integration.local.BetterFilesTests"
./mill integration.test "mill.integration.local.UpickleTests"
