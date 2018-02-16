#!/usr/bin/env bash

set -eux

curl -L -o ~/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/1.0.0/2.11-1.0.0 && chmod +x ~/bin/amm; fi

cd docs

amm build.sc --publish true