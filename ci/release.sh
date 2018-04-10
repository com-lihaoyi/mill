#!/usr/bin/env bash

set -eux

echo $GPG_PRIVATE_KEY_B64 | base64 --decode > gpg_key

gpg --import gpg_key

rm gpg_key
mill dev.launcher

out/dev/launcher/dest/run mill.scalalib.PublishModule/publishAll \
    lihaoyi:$SONATYPE_PASSWORD \
    $GPG_PASSWORD \
    __.publishArtifacts \
    --release \
    true


out/dev/launcher/dest/run  uploadToGithub $GITHUB_ACCESS_TOKEN
