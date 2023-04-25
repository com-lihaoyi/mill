#!/usr/bin/env bash

set -eu

# Build the pages
./mill -i docs.githubPages

# Prepare ssh-key for git actions
echo $REPO_DEPLOY_KEY | base64 --decode > deploy_key

eval "$(ssh-agent -s)"
chmod 600 deploy_key
ssh-add deploy_key
rm deploy_key

# Prepare git user
git config user.email "haoyi.sg+travis@gmail.com"
git config user.name "Mill GitHub Bot"

PAGES_REPO=gh-pages

# checkout gh-pages
git worktree add -b gh-pages gh-pages origin/gh-pages

# we want to keep history, so we prepare a new commit
rm -r ${PAGES_REPO}/*
cp -r out/docs/githubPages.dest/site/* ${PAGES_REPO}/
touch ${PAGES_REPO}/.nojekyll

cd $PAGES_REPO

git add .nojekyll
git add *
git commit -m "Updated github pages from commit ${GITHUB_SHA}"
git push origin gh-pages:gh-pages
