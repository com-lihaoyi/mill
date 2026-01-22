#!/usr/bin/env sh

set -eux

# Starting from scratch...
git stash -u
git stash -a

# Build Mill
./mill -i dist.raw.installLocal

# Clean up
git stash -a -m "preserve mill-release" -- ./mill-assembly.jar
git stash -u
git stash -a
git stash pop "$(git stash list | grep "preserve mill-release" | head -n1 | sed -E 's/([^:]+):.*/\1/')"

# Prepare local build
ci/patch-mill-bootstrap.sh

# Start clean to rule out cache invalidation issues
rm -rf out


# Run tests
./mill-assembly.jar -i "example.scalalib.basic[3-simple].packaged.daemon.testForked"

# Make sure the REPL in the scratch folder works
cd scratch
echo "Seq.tabulate(1000)(identity).sum" | ../mill-assembly.jar -i repl | grep 499500