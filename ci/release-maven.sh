#!/usr/bin/env bash

set -eu

./mill -i installLocal

./target/mill-release -i mill.scalalib.PublishModule/publishAll