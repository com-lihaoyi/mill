#!/usr/bin/env bash

set -eux

echo $GPG_PRIVATE_KEY_B64 | base64 --decode > ~/gpg.key
gpg import ~/gpg.key

target/bin/mill releaseCI $GITHUB_ACCESS_TOKEN lihaoyi:$SONATYPE_PASSWORD $GPG_PASSWORD
