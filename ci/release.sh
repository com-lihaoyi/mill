#!/usr/bin/env bash

set -eux

echo $GPG_PRIVATE_KEY_B64 | base64 --decode > gpg_key

gpg --import gpg_key

rm gpg_key
mill release

~/mill-release mill.scalalib.PublishModule/publishAll \
    lihaoyi:$SONATYPE_PASSWORD \
    $GPG_PASSWORD \
    __.publishArtifacts \
    --release \
    true


~/mill-release uploadToGithub $GITHUB_ACCESS_TOKEN
