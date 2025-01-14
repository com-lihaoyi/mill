#!/usr/bin/env bash

set -eux

echo "running push-autofix.sh"
cat .autofix-repo
cat .autofix-branch
# Prepare ssh-key for git actions
echo $REPO_DEPLOY_KEY | base64 --decode > deploy_key
chmod 600 deploy_key

# Prepare git user
git config user.email "haoyi.sg+travis@gmail.com"
git config user.name "Mill GitHub Bot"

cat .autofix-repo
cat .autofix-branch
# skip git hooks
git push -i deploy_key --no-verify git@github.com:com-lihaoyi/mill.git HEAD:$(cat .autofix-branch)