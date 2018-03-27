#!/usr/bin/env bash

mill release.clean
mill __.test
mill release.setReleaseVersion
mill mill.scalalib.PublishModule/publishAll \
    --sonatypeCreds $SONATYPE_CREDENTIALS \
    --gpgPassphrase $GPG_PASSPHRASE \
    --publishArtifacts __.publishArtifacts \
    --release true
mill release.setNextVersion
