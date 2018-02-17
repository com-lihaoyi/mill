#!/usr/bin/env bash

sbt bin/test:assembly

echo $GPG_PRIVATE_KEY_B64 | base64 --decode > gpg_key

gpg --import gpg_key

rm gpg_key
target/bin/mill mill.scalalib.PublishModule/publishAll \
    lihaoyi:$SONATYPE_PASSWORD \
    $GPG_PASSWORD \
    __.publishArtifacts \
    --release
    true \


target/bin/mill uploadToGithub $GITHUB_ACCESS_TOKEN
