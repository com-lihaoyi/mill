#!/usr/bin/env bash

set -eux

echo "running push-autofix.sh"
cat .autofix-repo
cat .autofix-branch
# Prepare ssh-key for git actions
echo $REPO_DEPLOY_KEY | base64 --decode > deploy_key

eval "$(ssh-agent -s)"
chmod 600 deploy_key
ssh-add deploy_key
rm deploy_key

# Prepare git user
git config user.email "haoyi.sg+travis@gmail.com"
git config user.name "Mill GitHub Bot"

cat .autofix-repo
cat .autofix-branch
# skip git hooks
git push --no-verify $(cat .autofix-repo) HEAD:$(cat .autofix-branch)