#!/usr/bin/env bash

set -eux

mill-release -i --color false all __.publishLocal release

mv out/release/dest/mill ~/bin/mill-release
