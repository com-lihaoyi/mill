
# ![Mill Logo](docs/logo.svg) Mill [![Build Status][travis-badge]][travis-link] [![Gitter Chat][gitter-badge]][gitter-link] [![Patreon][patreon-badge]][patreon-link]

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

The remainder of this readme is targeted at people who wish to work on Mill's
own codebase.

## How to build and test

Run unit test suite:

```bash
sbt main/test
mill main.test
```

Build a standalone executable jar:

```bash
sbt bin/test:assembly
mill devAssembly
```

Now you can re-build this very same project using the build.sc file, e.g. re-run
core unit tests

e.g.:
```bash
./target/bin/mill core.compile

./out/devAssembly/dest/mill core.compile
./out/devAssembly/dest/mill main.test.compile
./out/devAssembly/dest/mill main.test
./out/devAssembly/dest/mill scalalib.assembly
```

There is already a `watch` option that looks for changes on files, e.g.:

```bash
./target/bin/mill --watch core.compile
./out/devAssembly/dest/mill --watch core.compile
```

You can get Mill to show the JSON-structured output for a particular `Target` or
`Command` using the `show` flag:

```bash
./out/devAssembly/dest/mill show core.scalaVersion
./out/devAssembly/dest/mill show core.compile
./out/devAssembly/dest/mill show core.assemblyClasspath
./out/devAssembly/dest/mill show main.test
```

Output will be generated into a the `./out` folder.

If you are repeatedly testing Mill manually by running it against the `build.sc`
file in the repository root, you can skip the assembly process and directly run
it via:

```bash
sbt "~bin/test:run main.test"
sbt "~bin/test:run"
mill --watch dev . main.test
mill --watch dev .
```

You can also test out your current Mill code with one of the hello-world example
repos via:

```bash
mill dev docs/example-1 foo.run
```

Lastly, you can generate IntelliJ Scala project files using Mill via

```bash
./target/bin/mill mill.scalalib.GenIdeaModule/idea
```

Allowing you to import a Mill project into Intellij without using SBT

### Command line

There is a number of ways to run targets and commands via command line:

* Run single target:
```bash
mill core.compile
```

* Run single command with arguments:
```bash
mill bridges[2.12.4].publish --credentials foo --gpgPassphrase bar
```

* Run multiple targets:
```bash
mill all main.test scalalib.test 
```

**Note**: don't forget to put `--all` flag when you run multiple commands, otherwise the only first command will be run, and subsequent commands will be passed as arguments to the first one.

* Run multiple commands with arguments:
```bash
mill all bridges[2.11.11].publish bridges[2.12.4].publish -- --credentials foo --gpgPassphrase bar 
```

Here `--credentials foo --gpgPassphrase bar` arguments will be passed to both `bridges[2.11.11].publish` and `bridges[2.12.4].publish` command. 

**Note**: arguments list should be separated with `--` from command list.


Sometimes it is tedious to write multiple targets when you want to run same target in multiple modules, or multiple targets in one module.
Here brace expansion from bash(or another shell that support brace expansion) comes to rescue. It allows you to make some "shortcuts" for multiple commands.

* Run same targets in multiple modules with brace expansion:
```bash
mill all {core,scalalib,scalajslib,integration}.test
```

will run `test` target in `core`, `scalalib`, `scalajslib` and `integration` modules.

* Run multiple targets in one module with brace expansion:
```bash
mill all scalalib.{compile,test}
```

will run `compile` and `test` targets in `scalalib` module.

* Run multiple targets in multiple modules:
```bash
mill all {core,scalalib}.{scalaVersion,scalaBinaryVersion}
```

will run `scalaVersion` and `scalaBinaryVersion` targets in both `core` and `scalalib` modules. 

* Run targets in different cross build modules

```bash
mill all bridges[{2.11.11,2.12.4}].publish --  --credentials foo --gpgPassphrase bar
```

will run `publish` command in both `brides[2.11.11]` and `bridges[2.12.4]` modules

You can also use the `_` wildcard and `__` recursive-wildcard to run groups of
tasks:

```bash
# Run the `test` command of all top-level modules
mill all _.test

# Run the `test` command of all modules, top-level or nested
mill all __.test

# Run `compile` in every cross-module of `bridges`
mill all bridges[_].compile
```

**Note**: When you run multiple targets with `--all` flag, they are not
guaranteed to run in that exact order. Mill will build task evaluation graph and
run targets in correct order.

### REPL

Mill provides a build REPL, which lets you explore the build interactively and
run `Target`s from Scala code:

```scala
lihaoyi mill$ target/bin/mill
Loading...
Compiling (synthetic)/ammonite/predef/interpBridge.sc
Compiling (synthetic)/ammonite/predef/replBridge.sc
Compiling (synthetic)/ammonite/predef/DefaultPredef.sc
Compiling /Users/lihaoyi/Dropbox/Workspace/mill/build.sc
Compiling /Users/lihaoyi/Dropbox/Workspace/mill/out/run.sc
Compiling (synthetic)/ammonite/predef/CodePredef.sc

@ build
res0: build.type = build

@ build.
!=            core          scalalib   bridges       getClass      isInstanceOf  |>
==            MillModule    asInstanceOf  equals        hashCode      toString
@ build.core
res1: core = ammonite.predef.build#core:45
Children:
    .test
Commands:
    .console()()
    .run(args: String*)()
    .runMain(mainClass: String, args: String*)()
Targets:
    .allSources()
    .artifactId()
    .artifactName()
    .artifactScalaVersion()
    .assembly()
    .assemblyClasspath()
    .classpath()
    .compile()
    .compileDepClasspath()
    .compileIvyDeps()
    .compilerBridge()
    .crossFullScalaVersion()
    .depClasspath()
    .docJar()
    .externalCompileDepClasspath()
    .externalCompileDepSources()
...

@ core
res2: core.type = ammonite.predef.build#core:45
Children:
    .test
Commands:
    .console()()
    .run(args: String*)()
    .runMain(mainClass: String, args: String*)()
Targets:
    .allSources()
    .artifactId()
    .artifactName()
    .artifactScalaVersion()
    .assembly()
    .assemblyClasspath()
    .classpath()
    .compile()
    .compileDepClasspath()
    .compileIvyDeps()
    .compilerBridge()
    .crossFullScalaVersion()
    .depClasspath()
    .docJar()
    .externalCompileDepClasspath()
    .externalCompileDepSources()
...

@ core.scalaV
scalaVersion
@ core.scalaVersion
res3: mill.define.Target[String] = ammonite.predef.build#MillModule#scalaVersion:20
Inputs:

@ core.scalaVersion()
[1/1] core.scalaVersion
res4: String = "2.12.4"

@ core.ivyDeps()
Running core.ivyDeps
[1/1] core.ivyDeps
res5: Seq[mill.scalalib.Dep] = List(
  Scala(Dependency(Module("com.lihaoyi", "sourcecode", Map()),
   "0.1.4",
...

@ core.ivyDeps().foreach(println)
Scala(Dependency(com.lihaoyi:sourcecode,0.1.4,,Set(),Attributes(,),false,true))
Scala(Dependency(com.lihaoyi:pprint,0.5.3,,Set(),Attributes(,),false,true))
Point(Dependency(com.lihaoyi:ammonite,1.0.3,,Set(),Attributes(,),false,true))
Scala(Dependency(com.typesafe.play:play-json,2.6.6,,Set(),Attributes(,),false,true))
Scala(Dependency(org.scala-sbt:zinc,1.0.5,,Set(),Attributes(,),false,true))
Java(Dependency(org.scala-sbt:test-interface,1.0,,Set(),Attributes(,),false,true))

// run multiple tasks with `eval` function.
@ val (coreScala, bridge2106Scala) = eval(core.scalaVersion, bridges("2.10.6").scalaVersion)
coreScala: String = "2.12.4"
bridge2106Scala: String = "2.10.6"
```

### build.sc

Into a `build.sc` file you can define separate `Module`s (e.g. `ScalaModule`).
Within each `Module` you can define 3 type of task:

- `Target`: take no argument, output is cached and should be serializable; run
  from `bash` (e.g. `def foo = T{...}`)
- `Command`: take serializable arguments, output is not cached; run from `bash`
  (arguments with `scopt`) (e.g. `def foo = T.command{...}`)
- `Task`: take arguments, output is not cached; do not run from `bash` (e.g.
  `def foo = T.task{...}` )


### Structure of the `out/` folder

The `out/` folder contains all the generated files & metadata for your build. It
is structured with one folder per `Target`/`Command`, that is run, e.g.:

- `out/core/compile/`
- `out/main/test/compile/`
- `out/main/test/forkTest/`
- `out/scalalib/compile/`

Each folder currently contains the following files:

- `dest/`: a path for the `Task` to use either as a scratch space, or to place
  generated files that are returned using `PathRef`s. `Task`s should only output
  files within their given `dest/` folder (available as `T.ctx().dest`) to avoid
  conflicting with other `Task`s, but files within `dest/` can be named
  arbitrarily.

- `log`: the `stdout`/`stderr` of the `Task`. This is also streamed to the
  console during evaluation.

- `meta.json`: the cache-key and JSON-serialized return-value of the
  `Target`/`Command`. The return-value can also be retrieved via `mill show
  core.compile`. Binary blobs are typically not included in `meta.json`, and
  instead stored as separate binary files in `dest/` which are then referenced
  by `meta.json` via `PathRef`s

### Self Hosting

You can use SBT to build a Mill executable, which itself is able to build more
Mill executables that can you can use to run Mill commands:

```bash
git clean -xdf

# Build Mill executable using SBT
sbt bin/test:assembly 

# Build Mill executable using the Mill executable generated by SBT
target/bin/mill devAssembly 

# Build Mill executable using the Mill executable generated by Mill itself
out/devAssembly/dest/out.jar devAssembly
```

Eventually, as Mill stabilizes, we will get rid of the SBT build entirely and
rely on previous versions of Mill to build itself.

### Troubleshooting

In case of troubles with caching and/or incremental compilation, you can always
restart from scratch removing the `out` directory:

```bash
rm -rf out/
```


## Mill Goals and Roadmap

The end goal of the Mill project is to develop a new Scala build tool to replace
SBT. Mill should satisfy most of the current use cases for SBT's functionality,
but hopefully needing much fewer features and much less complexity to do so. We
take inspiration from SBT, Make, Bazel, and many other existing build tools and
libraries.

The immediate goal of Mill is to be feature-complete enough to:

- Sustain its own development, without needing SBT
- Start porting over existing open-source Scala library builds from SBT to Mill

https://github.com/lihaoyi/mill/issues/2 would kick off the process porting
`com.lihaoyi:acyclic`'s build to Mill, and from there we can flesh out the
missing features needed to port other builds: Scala.js support, Scala-Native
support, etc..

As the maintainer of many open-source libraries, all of the `com.lihaoyi`
libraries are fair game to be ported.

Once a fair number of libraries have been ported, Mill should be in good enough
shape to release to the public, and we can try getting more people in the
community-at-large on board trying out Mill. This should hopefully happen by the
end of 2017.

Until then, let's keep Mill private. If someone wants to poke their nose in and
see what's going on, we should expect them to contribute code!

## Changelog


### 0.1.1

- Fixes for `foo.console`
- Enable Ammonite REPL integration via `foo.repl`

### 0.1.0

- First public release