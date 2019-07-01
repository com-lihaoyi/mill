#!/usr/bin/env bash

set -eux

./mill -i all __.publishLocal executable

mv out/executable/dest/mill ~/mill-release
