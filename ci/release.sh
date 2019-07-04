#!/usr/bin/env bash

set -eux

echo $GPG_PRIVATE_KEY_B64 | base64 --decode > gpg_key

gpg --import gpg_key

rm gpg_key

./mill mill.scalalib.PublishModule/publishAll \
    --sonatypeCreds lihaoyi:$SONATYPE_PASSWORD \
    --gpgPassphrase $GPG_PASSWORD \
    --publishArtifacts __.publishArtifacts \
    --readTimeout 120000 \
    --release true \
    --signed true

./mill uploadToGithub $GITHUB_ACCESS_TOKEN
