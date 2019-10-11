#!/usr/bin/env bash

set -eux

./mill -i all __.publishLocal assembly

./mill -i show main.publishVersion

cp out/assembly/dest/mill ~/mill-release
