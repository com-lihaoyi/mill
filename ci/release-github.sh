#!/usr/bin/env bash

set -eux

# Build Mill
./mill -i dev.assembly

rm -rf ~/.mill

out/dev/assembly/dest/mill uploadToGithub $REPO_ACCESS_TOKEN
