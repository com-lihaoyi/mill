#!/usr/bin/env bash

set -eux

curl -L -o ~/bin/amm https://github.com/lihaoyi/Ammonite/releases/download/1.4.0/2.12-1.4.0 && chmod +x ~/bin/amm

cd docs

echo $GITHUB_DEPLOY_KEY | base64 --decode > deploy_key

eval "$(ssh-agent -s)"
chmod 600 deploy_key
ssh-add deploy_key
rm deploy_key


git config --global user.email "haoyi.sg+travis@gmail.com"
git config --global user.name "Ammonite Travis Bot"

amm build.sc --publish true