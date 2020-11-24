#!/usr/bin/env python

import os, sys, subprocess
is_master_commit = (
    os.environ["GITHUB_REPOSITORY"] == "lihaoyi/mill" and
    (os.environ["GITHUB_REF"].endswith("/master")
)

if is_master_commit:
    subprocess.check_call(sys.argv[1:])
