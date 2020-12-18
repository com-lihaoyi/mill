#!/usr/bin/env bash

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Run tests

./mill -i all {main.__,scalalib,scalajslib,scalanativelib,bsp,contrib.buildinfo,contrib.codeartifact,contrib.flyway,contrib.playlib,contrib.proguard,contrib.scalapblib,contrib.scoverage,contrib.twirllib,contrib.versionfile,contrib.testng}.test
