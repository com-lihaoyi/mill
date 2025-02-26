#!/bin/sh

set -e

# Put millw on the path, so we can call 'mill' from the command line.
curl -L -o /usr/local/bin/mill https://raw.githubusercontent.com/lefou/millw/0.4.11/millw && chmod +x /usr/local/bin/mill

# Install Coursier - can call cs from command line
curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > /usr/local/bin/cs && chmod +x /usr/local/bin/cs

# Install metals - should prevent dependancy downloads on container start
cs install metals