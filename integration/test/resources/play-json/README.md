## Play JSON build

This is a port of sbt build for [Play JSON](https://github.com/playframework/play-json) library. It has roughly the same features as sbt version, for instance it cross-builds Play JSON under 4 major scala versions both js and JVM. It cam compile sources, run tests, publish artifacts to Sonatype, run benchmarks, reformat code, add license headers, use mima to diagnose binary compatibility issues.

The main build file is [build.sc](/build.sc), other `.sc` files in root directory provide additional "plugin"-like features.

### Example commands

Compile play json for JVM on scala 2.12.4: 
```bash
mill playJsonJvm[2.12.4].compile
```

Run test on all modules:
```bash
mill __.test
```

Run benchmarks on scala 2.12.4:
```bash
mill benchmarks[2.12.4].runJmh
```

### CI

Example CI configuration is in [.travis.yml](/.travis.yml) . It does the same thing as [original one](https://github.com/playframework/play-json/blob/master/.travis.yml) . You can check status of Play JSON built with this `.travis.yml` file [here](travis-ci.org/rockjam/play-json)

### Release

To make release run `./release.sh`. Don't forget to export `SONATYPE_CREDENTIALS` and `GPG_PASSPHRASE` env variables.
