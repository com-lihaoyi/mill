The CodeSig module implements a conservative call-graph analyzer at the JVM
bytecode level that generates hashes for methods and their dependencies and uses
them to invalidate Targets in a fine-grained manner in response to code changes.

See the PR description for a detailed explanation of how this works:

- https://github.com/com-lihaoyi/mill/pull/2417