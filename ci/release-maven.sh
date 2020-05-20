#!/usr/bin/env bash

set -eux

echo $GPG_PRIVATE_KEY_B64 | base64 --decode > gpg_key

gpg --import gpg_key

rm gpg_key

# Build Mill
./mill -i dev.assembly

rm -rf ~/.mill

out/dev/assembly/dest/mill mill.scalalib.PublishModule/publishAll \
    --sonatypeCreds lihaoyi:$SONATYPE_PASSWORD \
    --gpgArgs --passphrase=$GPG_PASSWORD,--batch,--yes,-a,-b \
    --publishArtifacts __.publishArtifacts \
    --readTimeout 600000 \
    --release true \
    --signed true
