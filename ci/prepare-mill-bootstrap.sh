#!/usr/bin/env sh
# Apply a patch, if present, before bootstrapping

set -eux

./mill contrib.buildinfo.publishLocal
./mill contrib.vcsversion.publishLocal
./mill contrib.mima.publishLocal

# Patch local build
ci/patch-mill-bootstrap.sh


