#!/usr/bin/env bash

set -eux

echo $GPG_PRIVATE_KEY_B64 | base64 --decode > gpg_key

gpg --import gpg_key

rm gpg_key

# Build Mill
./mill -i dev.assembly

rm -rf ~/.mill

# We first deploy to Maven to ensure, all modules (worker, contrib) are available
out/dev/assembly/dest/mill mill.scalalib.PublishModule/publishAll \
    --sonatypeCreds lihaoyi:$SONATYPE_PASSWORD \
    --gpgPassphrase $GPG_PASSWORD \
    --publishArtifacts __.publishArtifacts \
    --readTimeout 600000 \
    --release true \
    --signed true

# We upload the assembly to GitHub only after we deployed to Maven
out/dev/assembly/dest/mill uploadToGithub $GITHUB_ACCESS_TOKEN
