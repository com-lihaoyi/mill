#!/usr/bin/env bash

set -eu

./mill -i installLocal

ci/patch-mill-bootstrap.sh

./target/mill-release -i mill.scalalib.PublishModule/