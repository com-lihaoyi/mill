#!/usr/bin/env bash

set -eux

mill -i all __.publishLocal release

mv out/release/dest/mill ~/mill-release
