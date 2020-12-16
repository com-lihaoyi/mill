#!/usr/bin/env bash

set -eu

echo $SONATYPE_PGP_SECRET | base64 --decode > gpg_key

gpg --import  --no-tty --batch --yes gpg_key

rm gpg_key

# Build Mill
./mill -i dev.assembly

rm -rf ~/.mill

out/dev/assembly/dest/mill mill.scalalib.PublishModule/publishAll \
    --sonatypeCreds $SONATYPE_DEPLOY_USER:$SONATYPE_DEPLOY_PASSWORD \
    --gpgArgs --passphrase=$SONATYPE_PGP_PASSWORD \
    --gpgArgs --no-tty \
    --gpgArgs --pinentry-mode \
    --gpgArgs loopback \
    --gpgArgs --batch \
    --gpgArgs --yes \
    --gpgArgs -a \
    --gpgArgs -b \
    --publishArtifacts __.publishArtifacts \
    --readTimeout 600000 \
    --awaitTimeout 600000 \
    --release true \
    --signed true
