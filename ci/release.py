#!/usr/bin/env python

from subprocess import check_call
import os, base64
check_call(["sbt", "bin/test:assembly"])
is_master_commit = (
    os.environ["TRAVIS_PULL_REQUEST"] == "false" &&
    (os.environ["TRAVIS_BRANCH"] == "master" || os.environ["TRAVIS_TAG"] != "")
)

with open("~/gpg.key", "w") as f:
    f.write(base64.b64decode(os.environ["GPG_PRIVATE_KEY_B64"]))

check_call(["gpg", "--import", "~/gpg.key"])

check_call([
    "target/bin/mill",
    "mill.scalalib.PublishModule/publishAll",
    "lihaoyi:" + os.environ["SONATYPE_PASSWORD"],
    os.environ["GPG_PASSWORD"],
    "_.publishArtifacts"
])
