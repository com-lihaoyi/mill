#!/usr/bin/env python

from subprocess import check_call
import tempfile
import os, base64
check_call(["sbt", "bin/test:assembly"])
is_master_commit = (
    os.environ["TRAVIS_PULL_REQUEST"] == "false" and
    (os.environ["TRAVIS_BRANCH"] == "master" or os.environ["TRAVIS_TAG"] != "")
)

_, tmp = tempfile.mkstemp()

with open(tmp, "w") as f:
    f.write(base64.b64decode(os.environ["GPG_PRIVATE_KEY_B64"]))

check_call(["gpg", "--import", tmp])

check_call([
    "target/bin/mill",
    "mill.scalalib.PublishModule/publishAll",
    "lihaoyi:" + os.environ["SONATYPE_PASSWORD"],
    os.environ["GPG_PASSWORD"],
    "__.publishArtifacts"
])
