#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

mill contrib.testng.publishLocal # Needed for CaffeineTests
# Run tests
mill integration.test "mill.integration.local.{AcyclicTests,AmmoniteTests,DocAnnotationsTests}"
