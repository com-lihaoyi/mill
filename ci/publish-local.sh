#!/usr/bin/env bash

set -eux

./mill -i all __.publishLocal executable

cp out/executable/dest/mill ~/mill-release
