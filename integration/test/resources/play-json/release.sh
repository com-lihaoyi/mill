mill release.clean
mill __.test
mill release.setReleaseVersion
mill mill.scalalib.PublishModule/publishAll \
    $SONATYPE_CREDENTIALS \
    $GPG_PASSPHRASE \
    __.publishArtifacts \
    --release \
    true \
mill release.setNextVersion
