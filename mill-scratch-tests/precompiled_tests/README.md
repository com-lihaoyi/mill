# Precompiled Tests

## Setup

First, publish the custom module JAR locally:

```
cd custom_module
../mill publishLocal
```

Then compile and run from this directory:

```
cd precompiled_tests
../mill bar.compile
../mill foo.run
../mill bar.run
```
