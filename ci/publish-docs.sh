#!/usr/bin/env bash

set -eu

# Build the pages
./mill -i docs.antora.githubPages

# Prepare ssh-key for git actions
echo $REPO_DEPLOY_KEY | base64 --decode > deploy_key

eval "$(ssh-agent -s)"
chmod 600 deploy_key
ssh-add deploy_key
rm deploy_key

# Prepare git user
git config --global user.email "haoyi.sg+travis@gmail.com"
git config --global user.name "Mill GitHub Bot"

PAGES_REPO=gh-pages

# checkout gh-pages
git worktree add gh-pages origin/gh-pages

# we want to keep history, so we prepare a new commit
rm -r ${PAGES_REPO}/*

touch ${PAGES_REPO}/.nojekyll
(cd $PAGES_REPO && git add .nojekyll)

cp -r out/docs/antora/githubPages/dest/site/* ${PAGES_REPO}/
(cd $PAGES_REPO && git add *)

(cd $PAGES_REPO && git commit -m "Updated github pages from commit ${GITHUB_SHA}")

(cd $PAGES_REPO && git push)
