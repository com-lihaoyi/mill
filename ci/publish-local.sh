#!/usr/bin/env sh

echo "ci/publish-local.sh is deprecated. Use <mill installLocal> instead." 1>&2

./mill -i installLocal
