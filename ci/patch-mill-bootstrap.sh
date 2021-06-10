#!/usr/bin/env sh
# Apply a patch, if present, before bootstrapping

set -eux

# Patch local build
if [ -f ci/mill-bootstrap.patch ] ; then
  patch -p1 < ci/mill-bootstrap.patch
fi
