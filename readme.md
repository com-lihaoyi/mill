
# ![Mill Logo](docs/logo.svg) Mill [![Build Status][travis-badge]][travis-link] [![Build (Windows)][appveyor-badge]][appveyor-link] [![Gitter Chat][gitter-badge]][gitter-link] [![Patreon][patreon-badge]][patreon-link]

[appveyor-badge]: https://ci.appveyor.com/api/projects/status/github/lihaoyi/mill
[appveyor-link]: https://ci.appveyor.com/project/lihaoyi/mill
[travis-badge]: https://travis-ci.org/lihaoyi/mill.svg
[travis-link]: https://travis-ci.org/lihaoyi/mill
[gitter-badge]: https://badges.gitter.im/Join%20Chat.svg
[gitter-link]: https://gitter.im/lihaoyi/mill?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge
[patreon-badge]: https://img.shields.io/badge/patreon-sponsor-ff69b4.svg
[patreon-link]: https://www.patreon.com/lihaoyi

Your shiny new Scala build tool! Confused by SBT? Frustrated by Maven? Perplexed
by Gradle? Give Mill a try!

If you want to use Mill in your own projects, check out our documentation:

- [Documentation](http://www.lihaoyi.com/mill/)

If you use Mill and like it, you will probably enjoy the following book by the Author:

- [*Hands-on Scala Programming*](https://www.handsonscala.com/)

*Hands-on Scala* has a *Chapter 10: Static Build Pipelines* dedicated to Mill,
but rest of the book introduces other libraries and tools in a similar style.
*Hands-on Scala* is a great way to level up your skills in Scala in general 
and Mill in particular

The remainder of this readme is developer-documentation targeted at people who
wish to work on Mill's own codebase. The developer docs assume you have read
through the user-facing documentation linked above. It's also worth spending a
few minutes reading the following blog posts to get a sense of Mill's design &
motivation:

- [So, what's wrong with SBT?](http://www.lihaoyi.com/post/SowhatswrongwithSBT.html)
- [Build Tools as Pure Functional Programs](http://www.lihaoyi.com/post/BuildToolsasPureFunctionalPrograms.html)
- [Mill: Better Scala Builds](http://www.lihaoyi.com/post/MillBetterScalaBuilds.html)

## How to build and test

Mill is built using Mill. To begin, first download & install Mill as described
in the documentation above. As Mill is under active development, stable releases
may not be able to build the current development branch of Mill. It is
recommended to install the latest unstable release manually.

### IntelliJ Setup

If you are using IntelliJ IDEA to edit Mill's Scala code, you can create the
IntelliJ project files via:

```bash
./mill mill.scalalib.GenIdea/idea
```

### Automated Tests

To run test suites:

```bash
./mill main.test
./mill scalalib.test
./mill scalajslib.test
./mill integration.test
```

### Manual Testing

To manually test Mill on a small build, you can use the `scratch` folder:

```bash
./mill -i dev.run scratch -w resolve _
```

This runs the task `resolve _` with your current checkout of Mill on the trivial build defined in
`scratch/build.sc`. You can modify that build file to add additional modules,
files, etc. and see how it behaves.

More generally, you can use:

```bash
./mill -i dev.run [target-dir] [...args]
```

To create run your current checkout of Mill in the given `target-dir` with the
given `args`. This is useful e.g. to test a modified version of Mill on some
other project's Mill build.

You can also create a launcher-script to let you run the current checkout of
Mill without the bootstrap Mill process present:

```bash
./mill dev.launcher
```

This creates the `out/dev/launcher/dest/run` launcher script, which you can then
use to run your current checkout of Mill where-ever you'd like. Note that this
script relies on the compiled code already present in the Mill `out/` folder,
and thus isn't suitable for testing on Mill's own Mill build since you would be
over-writing the compiled code at the same time as the launcher script is using
it.

You can also run your current checkout of Mill on the build in your `scratch/`
folder without the bootstrap Mill process being present via:

```bash
./mill dev.launcher && (cd scratch && ../out/dev/launcher/dest/run -w show thingy)
```

### Bootstrapping: Building Mill with your current checkout of Mill

To test bootstrapping of Mill's own Mill build using a version of Mill built
from your checkout, you can run

```bash
ci/publish-local.sh
```

This creates a standalone assembly at `~/mill-release` you can use, which
references jars published locally in your `~/.ivy2/local` cache. You can then
use this standalone assembly to build & re-build your current Mill checkout
without worrying about stomping over compiled code that the assembly is using.

### Troubleshooting

In case of troubles with caching and/or incremental compilation, you can always
restart from scratch removing the `out` directory:

```bash
os.remove.all -rf out/
```

## Project Layout

The Mill project is organized roughly as follows:

### Core modules that are included in the main assembly

- `core`, `main`, `main.client`, `scalalib`, `scalajslib`.

These are general lightweight and dependency-free: mostly configuration & wiring
of a Mill build and without the heavy lifting.

Heavy lifting is delegated to the worker modules (described below), which the
core modules resolve from Maven Central (or from the local filesystem in
dev) and load into isolated classloaders.

### Worker modules that are resolved from Maven Central

- `scalalib.worker`, `scalajslib.worker[0.6]`, `scalajslib.worker[1.0]`

These modules are where the heavy-lifting happens, and include heavy
dependencies like the Scala compiler, Scala.js optimizer, etc.. Rather than
being bundled in the main assembly & classpath, these are resolved separately
from Maven Central (or from the local filesystem in dev) and kept in isolated
classloaders.

This allows a single Mill build to use multiple versions of e.g. the Scala.js
optimizer without classpath conflicts.

### Contrib modules

- `contrib/bloop/`, `contrib/flyway/`, `contrib/scoverage/`, etc.

These are modules that help integrate Mill with the wide variety of different
tools and utilities available in the JVM ecosystem.

These modules are not as stringently reviewed as the main Mill core/worker codebase,
and are primarily maintained by their individual contributors. These are maintained
as part of the primary Mill Github repo for easy testing/updating as the core Mill
APIs evolve, ensuring that they are always tested and passing against the 
corresponding version of Mill.

## Changelog

### master

*For details refer to
[milestone after 0.8.0](https://github.com/lihaoyi/mill/milestone/44?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.8.0...master).*

### 0.8.0 - 2020-07-20

* Bump external dependencies: uPickle 1.2.0, Ammonite 2.2.0, etc.
* Use default coursier repos (#931)
* Work around relative paths issue on windows (#936)
* Support Scala.js versions >1.0.0 (#934)


*For details refer to
[milestone 0.8.0](https://github.com/lihaoyi/mill/milestone/43?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.7.4...0.8.0).*

### 0.7.4 - 2020-07-03

* new command line options `--repl` and `--no-server`, deprecated `--interactive` option
* Support for Scala.js 1.1
* Fixed missing source maps for Scala.js 1.0 and 1.1
* Improved BSP contrib module

*For details refer to
[milestone 0.7.4](https://github.com/lihaoyi/mill/milestone/42?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.7.3...0.7.4).*

### 0.7.3

*For details refer to
[milestone 0.7.3](https://github.com/lihaoyi/mill/milestone/41?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.7.2...0.7.3).*

### 0.7.2 - 2020-05-19

*For details refer to
[milestone 0.7.2](https://github.com/lihaoyi/mill/milestone/40?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.7.1...0.7.2).*

### 0.7.1 - 2020-05-17

*For details refer to
[milestone 0.7.1](https://github.com/lihaoyi/mill/milestone/39?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.7.0...0.7.1).*

### 0.7.0 - 2020-05-15

- Greatly improved parallel builds via `-j <n>`/`--jobs <n>`, with better scheduling
  and utilization of multiple cores
- `build.sc` files now uses Scala 2.13.2
- Avoid duplicate target resolution with `mill resolve __`
- Add ability to pass GPG arguments to publish via `--gpgArgs`
- `-w`/`--watch` now works for `T.source` targets

*For details refer to
[milestone 0.7.0](https://github.com/lihaoyi/mill/milestone/37?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.6.3...master).*


### 0.6.3 - 2020-05-10

- Finished incomplete support to publish extra artifacts to IVY repositories (`publishLocal`)
- Improved Sonatype uploads
- `GenIdea`: improvements for shared source dirs and skipped modules
- `ScoverageModule`: Some refactorings to allow better customization 
- More robust classpath handling under Windows

*For details refer to
[milestone 0.6.3](https://github.com/lihaoyi/mill/milestone/38?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.6.2...0.6.3).*


### 0.6.2 - 2020-04-22

- Mill can now execute targets in parallel. 
  This is experimental and need to be enabled with `--jobs <n>` option.
- `PublishModule`: new `publishM2Local` to publish into local Maven repositories
- `PublishModule`: enhanced `publishLocal` to specify to ivy repository location
- Windows: Fixed windows launcher and more robust classpath handling
- `ScalaNativeModule`: improved compiling and linking support
- new contrib module `VersionFile`
- `Dependency`: improved dependency update checker and expose results for programmatic use
- ǹew contrib module `Bintray`
- ǹew contrib module `Artifactory`
- fixed testCached support in various modules
- `GenIdea`: improvements, esp. related to source jars

*For details refer to
[milestone 0.6.2](https://github.com/lihaoyi/mill/milestone/36?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.6.1...0.6.2).*


### 0.6.1 - 2020-02-24

- Bugfix: Mill now no longer leaks open files (version bump to uPickle 1.0.0)
- New `--version` option
- Added Support for Scala.js 1.0.0+
- Added Support for Scala Native 0.4.0-M2
- `JavaModule`: Enhanced `ivyDepsTree` to optionally include compile-time and runtime-time dependencies
- `JavaModule`: `allSourceFiles` no longer include Scala sources
- `JavaModule`: assembly supports configurable separator when merging resources
- `ScoverageModule`: respect `unmanagedClasspath`, added console reporter
- `ScalaPBModule`: added more configuration options 
- Bloop: Fixed inconsistent working directory when executing tests via bloop (forces `-Duser.dir` when generating bloop config)

*For details refer to
[milestone 0.6.1](https://github.com/lihaoyi/mill/milestone/35?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.6.0...0.6.1).*

### 0.6.0 - 2020-01-20

- Support for METALS 0.8.0 in VSCode

*For details refer to
[milestone 0.6.0](https://github.com/lihaoyi/mill/milestone/34?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.5.9...0.6.0).*

### 0.5.9 - 2020-01-14

- Bump library versions again
- Alias `T.ctx.*` functions to `T.*`: `T.dest`, `T.log`, etc.
- Bump Mill's client-connect-to-server timeout, to reduce flakiness when the
  server is taking a moment to start up

*For details refer to
the [list of commits](https://github.com/lihaoyi/mill/compare/0.5.7...0.5.9).*

**Verison 0.5.8 has some binary compatibility issues in requests-scala/geny and should not be used.**

### 0.5.7 - 2019-12-28

- Bump library versions: Ammonite 2.0.1, uPickle 0.9.6, Scalatags 0.8.3, OS-Lib
  0.6.2, Requests 0.4.7, Geny 0.4.2
  
*For details refer to
[milestone 0.5.7](https://github.com/lihaoyi/mill/milestone/33?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.5.5...0.5.7).*

### 0.5.5 / 0.5.6 - 2019-12-20

*(we skipped version 0.5.4 as we had some publishing issues)*
  
- Bump library versions: Ammonite 1.9.2, uPickle 0.9.0, Scalatags 0.8.2, OS-Lib
   0.5.0, Requests 0.3.0, Geny 0.2.0, uTest 0.7.1
- Fixed a long standing issue that output of sub-processes are only shown when `-i` option was used.
  Now, you will always seen output of sub-process.
- Mill now properly restarts it's server after it's version has changed
- `PublishModule`: added ability to publish into non-staging repositories
- `ScalaPBModule`: added extra include path option

*For details refer to
[milestone 0.5.5](https://github.com/lihaoyi/mill/milestone/32?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.5.3...0.5.5).*


### 0.5.3 - 2019-12-07

- `GenIdea/idea`: improved support for generated sources and use/download sources in more cases 
- ScalaJS: improvements and support for ScalaJS 0.6.29+ and 1.0.1.RC1
- Introduced new `CoursierModule` to use dependency management independent from a compiler
- `ScoverageModule`: better handling of report directories
- `ScalaPBModule`: more configuration options
- various other fixes and improvements
 
*For details refer to
[milestone 0.5.3](https://github.com/lihaoyi/mill/milestone/31?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.5.2...0.5.3).*


### 0.5.2 - 2019-10-17

- `TestModule`: new `testCached`target, which only re-runs tests after relevant changes
- `TestModule.test`: fixed issue when stacktraces have no filename info
- `Dependency/updates`: fixed issue with reading stale dependencies
- `GenIdea/idea`: no longer shared output directories between mill and IntelliJ IDEA
- support for Dotty >= 0.18.1
- Fixed backwards compatibility of mill wrapper script
- Mill now support the Build Server Protocol 2.0 (BSP) and can act as a build server
- bloop: removed semanticDB dependency
- Documentation updates
 
*For details refer to
[milestone 0.5.2](https://github.com/lihaoyi/mill/milestone/30?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.5.1...0.5.2).*


### 0.5.1 - 2019-09-05

- GenIdea: Bug fixes
- GenIdea: Support for module specific extensions (Facets) and additional config files
- Add ability to define JAR manifests
- Dotty support: Updates and support for binary compiler bridges
- Ivy: improved API to create optional dependendies
- Interpolate `$MILL_VERSION` in ivy imports
- Zinc: Fixed logger output
- Scoverage: Upgrade to Scoverage 1.4.0
- Flyway: Upgrade to Flyway 6.0.1
- Bloop: Updated semanticDB version to 4.2.2
- Documentation updates
- Improved robustness in release/deployment process 

*For details refer to 
[milestone 0.5.1](https://github.com/lihaoyi/mill/milestone/29?closed=1)
and the [list of commits](https://github.com/lihaoyi/mill/compare/0.5.0...0.5.1).*


### 0.5.0

- Mill now supports a `./mill`
  [bootstrap script](http://www.lihaoyi.com/mill/#bootstrap-scripts-linuxos-x-only),
  allowing a project to pin the version of Mill it requires, as well as letting
  contributors use `./mill ...` to begin development without needing to install
  Mill beforehand.

- Support for a `.mill-version` file or `MILL_VERSION` environment variable for
  [Overriding Mill Versions](http://www.lihaoyi.com/mill/#overriding-mill-versions)

- Fix scoverage: inherit repositories from outer project
  [#645](https://github.com/lihaoyi/mill/pull/645)

### 0.4.2

- Improvements to IntelliJ project generation [#616](https://github.com/lihaoyi/mill/pull/616)

- Allow configuration of Scala.js' JsEnv [#628](https://github.com/lihaoyi/mill/pull/628)

### 0.4.1

- Fixes for scala native test suites without test frameworks [#627](https://github.com/lihaoyi/mill/issues/627)

- Fix publication of artifacts by increasing sonatype timeouts

- Bug fixes for Scoverage integration [#623](https://github.com/lihaoyi/mill/issues/623)

### 0.4.0

- Publish `compileIvyDeps` as provided scope
  ([535](https://github.com/lihaoyi/mill/issues/535))

- Added contrib modules to integrate
  [Bloop](http://www.lihaoyi.com/mill/page/contrib-modules.html#bloop),
  [Flyway](http://www.lihaoyi.com/mill/page/contrib-modules.html#flyway),
  [Play Framework](http://www.lihaoyi.com/mill/page/contrib-modules.html#play-framework),
  [Scoverage](http://www.lihaoyi.com/mill/page/contrib-modules.html#scoverage)

- Allow configuration of GPG key names when publishing
  ([530](https://github.com/lihaoyi/mill/pull/530))

- Bump Ammonite version to 1.6.7, making
  [Requests-Scala](https://github.com/lihaoyi/requests-scala) available to use
  in your `build.sc`

- Support for Scala 2.13.0-RC2

- ScalaFmt support now uses the version specified in `.scalafmt.conf`

### 0.3.6

- Started to splitting out mill.api from mill.core

- Avoid unnecessary dependency downloading by providing fetches per cache policy

- Added detailed dependency download progress to the progress ticker

- Fixed internal code generator to support large projects

- Zinc worker: compiler bridge can be either pre-compiled or on-demand-compiled

- Zinc worker: configurable scala library/compiler jar discovery

- Zinc worker: configurable compiler cache supporting parallelism

- Version bumps: ammonite 1.6.0, scala 2.12.8, zinc 1.2.5

- Mill now by default fails fast, so in case a build tasks fails, it exits immediately

- Added new `-k`/`--keep-going` commandline option to disable fail fast behaviour and continue build as long as possible in case of a failure

### 0.3.5

- Bump uPickle to 0.7.1

### 0.3.4

- Mill is now bundled with [OS-Lib](https://github.com/lihaoyi/os-lib),
  providing a simpler way of dealing with filesystem APIs and subprocesses

### 0.3.3

- Added new `debug` method to context logger, to log additional debug info into the
  task specific output dir (`out/<task>/log`)

- Added `--debug` option to enable debug output to STDERR

- Fix `ScalaModule#docJar` task when Scala minor versions differ [475](https://github.com/lihaoyi/mill/issues/475)

### 0.3.2

- Automatically detect main class to make `ScalaModule#assembly` self-executable

### 0.3.0

- Bump Ammonite to 1.3.2, Fastparse to 2.0.4

- Sped up `ScalaModule#docJar` task by about 10x, greatly speeding up publishing

- Add a flag `JavaModule#skipIdea` you can override to disable Intellij project
  generation [#458](https://github.com/lihaoyi/mill/pull/458)

- Allow sub-domains when publishing [#441](https://github.com/lihaoyi/mill/pull/441)

### 0.2.8

- `mill inspect` now displays out the doc-comment documentation for a task.

- Avoid shutdown hook failures in tests [#422](https://github.com/lihaoyi/mill/pull/422)

- Ignore unreadable output files rather than crashing [#423](https://github.com/lihaoyi/mill/pull/423)

- Don't compile hidden files [#428](https://github.com/lihaoyi/mill/pull/428)

### 0.2.7

- Add `visualizePlan` command

- Basic build-info plugin in `mill-contrib-buildinfo`

- ScalaPB integration in `mill-contrib-scalapblib`

- Fixes for Twirl support, now in `mill-contrib-twirllib`

- Support for building Dotty projects
  [#397](https://github.com/lihaoyi/mill/pull/397)

- Allow customization of `run`/`runBackground` working directory via
  `forkWorkingDir`

- Reduced executable size, improved incremental compilation in
  [#414](https://github.com/lihaoyi/mill/pull/414)

### 0.2.6

- Improve incremental compilation to work with transitive module dependencies

- Speed up hot compilation performance by properly re-using classloaders

- Speed up compilation time of `build.sc` files by removing duplicate macro
  generated routing code

### 0.2.5

- Add `.runBackground` and `.runMainBackground` commands, to run something in
  the background without waiting for it to return. The process will keep running
  until it exits normally, or until the same `.runBackground` command is run a
  second time to spawn a new version of the process. Can be used with `-w` for
  auto-reloading of long-running servers.

- [Scala-Native support](http://www.lihaoyi.com/mill/page/common-project-layouts.html#scala-native-modules).
  Try it out!

- Add `--disable-ticker` to reduce spam in CI

- Fix propagation of `--color` flag

### 0.2.4

- Fix resolution of `scala-{library,compiler,reflect}` in case of conflict

- Allow configuration of `JavaModule` and `ScalafmtModule` scala workers

- Allow hyphens in module and task names

- Fix publishing of ScalaJS modules to properly handle upstream ScalaJS dependencies

### 0.2.3

- Added the [mill show visualize](http://www.lihaoyi.com/mill/#visualize)
  command, making it easy to visualize the relationships between various tasks
  and modules in your Mill build.

- Improve Intellij support ([351](https://github.com/lihaoyi/mill/pull/351)):
  better jump-to-definition for third-party libraries, no longer stomping over
  manual configuration, and better handling of `import $ivy` in your build file.

- Support for un-signed publishing and cases where your GPG key has no
  passphrase ([346](https://github.com/lihaoyi/mill/pull/346))

- Basic support for Twirl, Play Framework's templating language
  ([271](https://github.com/lihaoyi/mill/pull/271))

- Better performance for streaming large amounts of stdout from Mill's daemon
  process.

- Allow configuration of append/exclude rules in `ScalaModule#assembly`
  ([309](https://github.com/lihaoyi/mill/pull/309))

### 0.2.2

- Preserve caches when transitioning between `-i`/`--interactive` and the
  fast client/server mode ([329](https://github.com/lihaoyi/mill/issues/329))

- Keep Mill daemon running if you Ctrl-C during `-w`/`--watch` mode
  ([327](https://github.com/lihaoyi/mill/issues/327))

- Allow `mill version` to run without a build file
  ([328](https://github.com/lihaoyi/mill/issues/328))

- Make `docJar` (and thus publishing) robust against scratch files in the source
  directories ([334](https://github.com/lihaoyi/mill/issues/334)) and work with
  Scala compiler options ([336](https://github.com/lihaoyi/mill/issues/336))

- Allow passing Ammonite command-line options to the `foo.repl` command
  ([333](https://github.com/lihaoyi/mill/pull/333))

- Add `mill clean` ([315](https://github.com/lihaoyi/mill/pull/315)) to easily
  delete the Mill build caches for specific targets

- Improve IntelliJ integration of `MavenModule`s/`SbtModule`s' test folders
  ([298](https://github.com/lihaoyi/mill/pull/298))

- Avoid showing useless stack traces when `foo.test` result-reporting fails or
  `foo.run` fails

- ScalaFmt support ([308](https://github.com/lihaoyi/mill/pull/308))

- Allow `ScalaModule#generatedSources` to allow single files (previous you could
  only pass in directories)

### 0.2.0

- Universal (combined batch/sh) script generation for launcher, assembly, and
  release ([#264](https://github.com/lihaoyi/mill/issues/264))

- Windows client/server improvements ([#262](https://github.com/lihaoyi/mill/issues/262))

- Windows repl support (note: MSYS2 subsystem/shell will be supported when jline3
  v3.6.3 is released)
  
- Fixed Java 9 support

- Remove need for running `publishAll` using `--interactive` when on OSX and
  your GPG key has a passphrase

- First-class support for `JavaModule`s

- Properly pass compiler plugins to Scaladoc ([#282](https://github.com/lihaoyi/mill/issues/282))

- Support for ivy version-pinning via `ivy"...".forceVersion()`

- Support for ivy excludes via `ivy"...".exclude()` ([#254](https://github.com/lihaoyi/mill/pull/254))

- Make `ivyDepsTree` properly handle transitive dependencies ([#226](https://github.com/lihaoyi/mill/issues/226))

- Fix handling of `runtime`-scoped ivy dependencies ([#173](https://github.com/lihaoyi/mill/issues/173))

- Make environment variables available to Mill builds ([#257](https://github.com/lihaoyi/mill/issues/257))

- Support ScalaCheck test runner ([#286](https://github.com/lihaoyi/mill/issues/286))

- Support for using Typelevel Scala ([#275](https://github.com/lihaoyi/mill/issues/275))

- If a module depends on multiple submodules with different versions of an
  ivy dependency, only one version is resolved ([#273](https://github.com/lihaoyi/mill/issues/273))



### 0.1.7
 
- Support for non-interactive (client/server) mode on Windows.

- More fixes for Java 9

- Bumped the Mill daemon timeout from 1 minute to 5 minutes of inactivity before
  it shuts down.

- Avoid leaking Node.js subprocesses when running `ScalaJSModule` tests

- Passing command-line arguments with spaces in them to tests no longer parses
  wrongly

- `ScalaModule#repositories`, `scalacPluginIvyDeps`, `scalacOptions`,
  `javacOptions` are now automatically propagated to `Tests` modules

- `ScalaJSModule` linking errors no longer show a useless stack trace

- `ScalaModule#docJar` now properly uses the compileClasspath rather than
  runClasspath

- Bumped underlying Ammonite version to [1.1.0](http://ammonite.io/#1.1.0),
  which provides the improved Windows and Java 9 support

### 0.1.6

- Fixes for non-interactive (client/server) mode on Java 9

- Windows batch (.bat) generation for launcher, assembly, and release

### 0.1.5

- Introduced the `mill plan foo.bar` command, which shows you what the execution
  plan of running the `foo.bar` task looks like without actually evaluating it.

- Mill now generates an `out/mill-profile.json` file containing task-timings, to
  make it easier to see where your mill evaluation time is going
  
- Introduced `ScalaModule#ivyDepsTree` command to show dependencies tree
  
- Rename `describe` to `inspect` for consistency with SBT

- `mill resolve` now prints results sorted alphabetically

- Node.js configuration can be customised with `ScalaJSModule#nodeJSConfig`

- Scala.js `fullOpt` now uses Google Closure Compiler after generating the optimized Javascript output

- Scala.js now supports `NoModule` and `CommonJSModule` module kinds

- Include `compileIvyDeps` when generating IntelliJ projects

- Fixed invalid POM generation

- Support for Java 9 (and 10)

- Fixes for Windows support

- Fixed test classes discovery by skipping interfaces

- Include "optional" artifacts in dependency resolution if they exist

- `out/{module_name}` now added as a content root in generated IntelliJ project

### 0.1.4

- Speed up Mill client initialization by another 50-100ms

- Speed up incremental `assembly`s in the common case where upstream
  dependencies do not change.

- Make `ScalaJSModule#run` work with main-method discovery

- Make `ScalaWorkerModule` user-defineable, so you can use your own custom
  coursier resolvers when resolving Mill's own jars

- Simplify definitions of `SCM` strings

- Make the build REPL explicitly require `-i`/`--interactive` to run

- Log a message when Mill is initializing the Zinc compiler interface

### 0.1.3

- Greatly reduced the overhead of evaluating Mill tasks, with a warm
  already-cached `mill dev.launcher` now taking ~450ms instead of ~1000ms

- Mill now saves compiled build files in `~/.mill/ammonite`, which is
  configurable via the `--home` CLI arg.

- Fixed linking of multi-module Scala.js projects

### 0.1.2

- Mill now keeps a long-lived work-daemon around in between commands; this
  should improve performance of things like `compile` which benefit from the
  warm JVM. You can use `-i`/`--interactive` for interactive consoles/REPLs and
  for running commands without the daemon

- Implemented the `ScalaModule#launcher` target for easily creating command-line
  launchers you can run outside of Mill

- `ScalaModule#docJar` no longer fails if you don't have `scala-compiler` on
  classpath

- Support for multiple `testFrameworks` in a test module.

### 0.1.1

- Fixes for `foo.console`
- Enable Ammonite REPL integration via `foo.repl`

### 0.1.0

- First public release
