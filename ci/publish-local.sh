#!/usr/bin/env bash

set -eux

./mill -i all __.publishLocal assembly

cp out/assembly/dest/mill ~/mill-release
