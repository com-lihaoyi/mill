#!/usr/bin/env python

import os, sys, subprocess
is_master_commit = (
    os.environ["TRAVIS_PULL_REQUEST"] == "false" and
    (os.environ["TRAVIS_BRANCH"] == "master" or os.environ["TRAVIS_TAG"] != "")
)

if is_master_commit:
    subprocess.check_call(sys.argv[1:])
