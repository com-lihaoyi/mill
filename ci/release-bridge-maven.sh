#!/usr/bin/env bash

set -eu

echo $SONATYPE_PGP_SECRET | base64 --decode > gpg_key

gpg --import  --no-tty --batch --yes gpg_key

rm gpg_key

# Build all artifacts
./mill -i __.publishArtifacts

export MILL_BUILD_COMPILER_BRIDGES=true

# Publish all artifacts
./mill -i \
    mill.scalalib.PublishModule/publishAll \
    --sonatypeCreds $SONATYPE_USERNAME:$SONATYPE_PASSWORD \
    --gpgArgs --passphrase=$SONATYPE_PGP_PASSWORD,--no-tty,--pinentry-mode,loopback,--batch,--yes,-a,-b \
    --publishArtifacts bridges.__.publishArtifacts \
    --readTimeout  3600000 \
    --awaitTimeout 3600000 \
    --release true \
    --signed  true
