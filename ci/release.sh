#!/usr/bin/env bash

set -eux

sbt bin/test:assembly

target/bin/mill releaseCI \
    $GITHUB_ACCESS_TOKEN \
    lihaoyi:$SONATYPE_PASSWORD \
    $GPG_PASSWORD \
    $GPG_PRIVATE_KEY_B64
