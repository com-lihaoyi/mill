
# ![Mill Logo](docs/logo.svg) Mill [![Build Status][travis-badge]][travis-link] [![Gitter Chat][gitter-badge]][gitter-link] [![Patreon][patreon-badge]][patreon-link]

[travis-badge]: https://travis-ci.org/lihaoyi/mill.svg
[travis-link]: https://travis-ci.org/lihaoyi/mill
[gitter-badge]: https://badges.gitter.im/Join%20Chat.svg
[gitter-link]: https://gitter.im/lihaoyi/mill?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge
[patreon-badge]: https://img.shields.io/badge/patreon-sponsor-ff69b4.svg
[patreon-link]: https://www.patreon.com/lihaoyi

Your shiny new Scala build tool!

## How to build and test

Run unit test suite:

```bash
sbt core/test
```

Build a standalone executable jar:

```bash
sbt bin/test:assembly
```

Now you can re-build this very same project using the build.sc file, e.g. re-run
core unit tests

e.g.:
```bash
./target/bin/mill core.compile
./target/bin/mill core.test.compile
./target/bin/mill core.test
./target/bin/mill scalalib.assembly
```

There is already a `watch` option that looks for changes on files, e.g.:

```bash
./target/bin/mill --watch core.compile
```

You can get Mill to show the JSON-structured output for a particular `Target` or
`Command` using the `--show` flag:

```bash
./target/bin/mill --show core.scalaVersion
./target/bin/mill --show core.compile
./target/bin/mill --show core.assemblyClasspath
./target/bin/mill --show core.test
```

Output will be generated into a the `./out` folder.

If you are repeatedly testing Mill manually by running it against the `build.sc`
file in the repository root, you can skip the assembly process and directly run
it via:

```bash
sbt "~bin/test:run core.test"
sbt "~bin/test:run"
```

Lastly, you can generate IntelliJ Scala project files using Mill via

```bash
./target/bin/mill idea
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
mill --all core.test scalalib.test 
```

**Note**: don't forget to put `--all` flag when you run multiple commands, otherwise the only first command will be run, and subsequent commands will be passed as arguments to the first one.

* Run multiple commands with arguments:
```bash
mill --all bridges[2.11.11].publish bridges[2.12.4].publish -- --credentials foo --gpgPassphrase bar 
```

Here `--credentials foo --gpgPassphrase bar` arguments will be passed to both `bridges[2.11.11].publish` and `bridges[2.12.4].publish` command. 

**Note**: arguments list should be separated with `--` from command list.


Sometimes it is tedious to write multiple targets when you want to run same target in multiple modules, or multiple targets in one module.
Here brace expansion from bash(or another shell that support brace expansion) comes to rescue. It allows you to make some "shortcuts" for multiple commands.

* Run same targets in multiple modules with brace expansion:
```bash
mill --all {core,scalalib,scalajslib,integration}.test
```

will run `test` target in `core`, `scalalib`, `scalajslib` and `integration` modules.

* Run multiple targets in one module with brace expansion:
```bash
mill --all scalalib.{compile,test}
```

will run `compile` and `test` targets in `scalalib` module.

* Run multiple targets in multiple modules:
```bash
mill --show --all {core,scalalib}.{scalaVersion,scalaBinaryVersion}
```

will run `scalaVersion` and `scalaBinaryVersion` targets in both `core` and `scalalib` modules. 

* Run targets in different cross build modules
```bash
mill --all bridges[{2.11.11,2.12.4}].publish --  --credentials foo --gpgPassphrase bar
```

will run `publish` command in both `brides[2.11.11]` and `bridges[2.12.4]` modules

**Note**: When you run multiple targets with `--all` flag, they are not guaranteed to run in that exact order. 
Mill will build task evaluation graph and run targets in correct order.

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
    .docsJar()
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
    .docsJar()
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
- `out/core/test/compile/`
- `out/core/test/forkTest/`
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
  `Target`/`Command`. The return-value can also be retrieved via `mill --show
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
out/devAssembly/dest devAssembly
```

Eventually, as Mill stabilizes, we will get rid of the SBT build entirely and
rely on previous versions of Mill to build itself.

### Troubleshooting

In case of troubles with caching and/or incremental compilation, you can always
restart from scratch removing the `out` directory:

```bash
rm -rf out/
```

## Mill Design Principles

A lot of mills design principles are intended to fix SBT's flaws, as described
in http://www.lihaoyi.com/post/SowhatswrongwithSBT.html. Before working on Mill,
read through that post to understand where it is coming from!

### Dependency graph first

Mill's most important abstraction is the dependency graph of `Task`s.
Constructed using the `T{...}` `T.task{...}` `T.command{...}` syntax, these
track the dependencies between steps of a build, so those steps can be executed
in the correct order, queried, or parallelized.

While Mill provides helpers like `ScalaModule` and other things you can use to
quickly instantiate a bunch of related tasks (resolve dependencies, find
sources, compile, package into jar, ...) these are secondary. When Mill
executes, the dependency graph is what matters: any other mode of organization
(hierarchies, modules, inheritence, etc.) is only important to create this
dependency graph of `Task`s.

### Builds are hierarchical

The syntax for running targets from the command line `mill Foo.bar.baz` is
the same as referencing a target in Scala code, `Foo.bar.baz`

Everything that you can run from the command line lives in an object hierarchy
in your `build.sc` file. Different parts of the hierarchy can have different
`Target`s available: just add a new `def foo = T{...}` somewhere and you'll be
able to run it.

Cross builds, using the `Cross` data structure, are just another kind of node in
the object hierarchy. The only difference is syntax: from the command line you'd
run something via `mill core.cross[a].printIt` while from code you use
`core.cross("a").printIt` due to different restrictions in Scala/Bash syntax.

### Caching by default

Every `Target` in a build, defined by `def foo = T{...}`, is cached by default.
Currently this is done using a `foo/meta.json` file in the `out/` folder. The
`Target` is also provided a `foo/` path on the filesystem dedicated to it, for
it to store output files etc.

This happens whether you want it to or not. Every `Target` is cached, not just
the "slow" ones like `compile` or `assembly`.

Caching is keyed on the `.hashCode` of the returned value. For `Target`s
returning the contents of a file/folder on disk, they return `PathRef` instances
whose hashcode is based on the hash of the disk contents. Serialization of the
returned values is tentatively done using uPickle.

### Short-lived build processes

The Mill build process is meant to be run over and over, not only as a
long-lived daemon/console. That means we must minimize the startup time of the
process, and that a new process must be able to re-construct the in-memory data
structures where a previous process left off, in order to continue the build.

Re-construction is done via the hierarchical nature of the build: each `Target`
`foo.bar.baz` has a fixed position in the build hierarchy, and thus a fixed
position on disk `out/foo/bar/baz/meta.json`. When the old process dies and a
new process starts, there will be a new instance of `Target` with the same
implementation code and same position in the build hierarchy: this new `Target`
can then load the `out/foo/bar/baz/meta.json` file and pick up where the
previous process left off.

Minimizing startup time means aggressive caching, as well as minimizing the
total amount of bytecode used: Mill's current 1-2s startup time is dominated by
JVM classloading. In future, we may have a long lived console or
nailgun/drip-based server/client models to speed up interactive usage, but we
should always keep "cold" startup as fast as possible.

### Static dependency graph and Applicative tasks

`Task`s are *Applicative*, not *Monadic*. There is `.map`, `.zip`, but no
`.flatMap` operation. That means that we can know the structure of the entire
dependency graph before we start executing `Task`s. This lets us perform all
sorts of useful operations on the graph before running it:

- Given a Target the user wants to run, pre-compute and display what targets
  will be evaluated ("dry run"), without running them

- Automatically parallelize different parts of the dependency graph that do not
  depend on each other, perhaps even distributing it to different worker
  machines like Bazel/Pants can

- Visualize the dependency graph easily, e.g. by dumping to a DOT file

- Query the graph, e.g. "why does this thing depend on that other thing?"

- Avoid running tasks "halfway": if a Target's upstream Targets fail, we can
  skip the Target completely rather than running halfway and then bailing out
  with an exception

In order to avoid making people using `.map` and `.zip` all over the place when
defining their `Task`s, we use the `T{...}`/`T.task{...}`/`T.command{...}`
macros which allow you to use `Task#apply()` within the block to "extract" a
value.

```scala
def test() = T.command{
  TestRunner.apply(
   "mill.UTestFramework",
   runDepClasspath().map(_.path) :+ compile().path,
   Seq(compile().path)
  
}
```

This is roughly to the following:

```scala
def test() = T.command{ T.zipMap(runDepClasspath, compile, compile){ 
  (runDepClasspath1, compile2, compile3) =>
  TestRunner.apply(
    "mill.UTestFramework",
    runDepClasspath1.map(_.path) :+ compile2.path,
    Seq(compile3.path)
  )
}
```

This is similar to SBT's `:=`/`.value` macros, or `scala-async`'s
`async`/`await`. Like those, the `T{...}` macro should let users program most of
their code in a "direct" style and have it "automatically" lifted into a graph
of `Task`s.

## How Mill aims for Simple

Why should you expect that the Mill build tool can achieve simple, easy &
flexible, where other build tools in the past have failed?

Build tools inherently encompass a huge number of different concepts:

- What "Tasks" depends on what?
- How do I define my own tasks?
- What needs to run in what order to do what I want?
- What can be parallelized and what can't?
- How do tasks pass data to each other? What data do they pass?
- What tasks are cached? Where?
- How are tasks run from the command line?
- How do you deal with the repetition inherent a build? (e.g. compile, run &
  test tasks for every "module")
- What is a "Module"? How do they relate to "Tasks"?
- How do you configure a Module to do something different?
- How are cross-builds (across different configurations) handled?

These are a lot of questions to answer, and we haven't even started talking
about the actually compiling/running any code yet! If each such facet of a build
was modelled separately, it's easy to have an explosion of different concepts
that would make a build tool hard to understand.

Before you continue, take a moment to think: how would you answer to each of
those questions using an existing build tool you are familiar with? Different
tools like [SBT](http://www.scala-sbt.org/),
[Fake](https://fake.build/legacy-index.html), [Gradle](https://gradle.org/) or
[Grunt](https://gruntjs.com/) have very different answers.

Mill aims to provide the answer to these questions using as few, as familiar
core concepts as possible. The entire Mill build is oriented around a few
concepts:

- The Object Hierarchy
- The Call Graph
- Instantiating Traits & Classes

These concepts are already familiar to anyone experienced in Scala (or any other
programming language...), but are enough to answer all of the complicated
build-related questions listed above.

## The Object Hierarchy

The module hierarchy is the graph of objects, starting from the root of the
`build.sc` file, that extend `mill.Module`. At the leaves of the hierarchy are
the `Target`s you can run.

A `Target`'s position in the module hierarchy tells you many things. For
example, a `Target` at position `core.test.compile` would:

- Cache output metadata at `out/core/test/compile/meta.json`

- Output files to the folder `out/core/test/compile/dest/`

- Be runnable from the command-line via `mill core.test.compile`

- Be referenced programmatically (from other `Target`s) via `core.test.compile`

From the position of any `Target` within the object hierarchy, you immediately
know how to run it, find its output files, find any caches, or refer to it from
other `Target`s. You know up-front where the `Target`'s data "lives" on disk, and
are sure that it will never clash with any other `Target`'s data.

## The Call Graph

The Scala call graph of "which target references which other target" is core to
how Mill operates. This graph is reified via the `T{...}` macro to make it
available to the Mill execution engine at runtime. The call graph tells you:

- Which `Target`s depend on which other `Target`s

- For a given `Target` to be built, what other `Target`s need to be run and in
  what order

- Which `Target`s can be evaluated in parallel

- What source files need to be watched when using `--watch` on a given target (by
  tracing the call graph up to the `Source`s)

- What a given `Target` makes available for other `Target`s to depend on (via
  its return value)

- Defining your own task that depends on others is as simple as `def foo =
  T{...}`

The call graph within your Scala code is essentially a data-flow graph: by
defining a snippet of code:

```scala
val b = ...
val c = ...
val d = ...
val a = f(b, c, d)
```

you are telling everyone that the value `a` depends on the values of `b` `c` and
`d`, processed by `f`. A build tool needs exactly the same data structure:
knowing what `Target` depends on what other `Target`s, and what processing it
does on its inputs!

With Mill, you can take the Scala call graph, wrap everything in the `T{...}`
macro, and get a `Target`-dependency graph that matches exactly the call-graph
you already had:

```scala
val b = T{ ... }
val c = T{ ... }
val d = T{ ... }
val a = T{ f(b(), c(), d()) }
```

Thus, if you are familiar with how data flows through a normal Scala program,
you already know how data flows through a Mill build! The Mill build evaluation
may be incremental, it may cache things, it may read and write from disk, but
the fundamental syntax, and the data-flow that syntax represents, is unchanged
from your normal Scala code.

## Instantiating Traits & Classes

Classes and traits are a common way of re-using common data structures in Scala:
if you have a bunch of fields which are related and you want to make multiple
copies of those fields, you put them in a class/trait and instantiate it over
and over.

In Mill, inheriting from traits is the primary way for re-using common parts of
a build:

- Scala "project"s with multiple related `Target`s within them, are just a
  `Trait` you instantiate

- Replacing the default `Target`s within a project, making them do new
  things or depend on new `Target`s, is simply `override`-ing them during
  inheritence.

- Modifying the default `Target`s within a project, making use of the old value
  to compute the new value, is simply `override`ing them and using `super.foo()`

- Required configuration parameters within a `project` are `abstract` members.

- Cross-builds are modelled as instantiating a (possibly anonymous) class
  multiple times, each instance with its own distinct set of `Target`s

In normal Scala, you bundle up common fields & functionality into a `class` you
can instantiate over and over, and you can override the things you want to
customize. Similarly, in Mill, you bundle up common parts of a build into
`trait`s you can instantiate over and over, and you can override the things you
want to customize. "Subprojects", "cross-builds", and many other concepts are
reduced to simply instantiating a `trait` over and over, with tweaks.

## Prior Work

### SBT

Mill is built as a substitute for SBT, whose problems are
[described here](http://www.lihaoyi.com/post/SowhatswrongwithSBT.html).
Nevertheless, Mill takes on some parts of SBT (builds written in Scala, Task
graph with an Applicative "idiom bracket" macro) where it makes sense.

### Bazel

Mill is largely inspired by [Bazel](https://bazel.build/). In particular, the
single-build-hierarchy, where every Target has an on-disk-cache/output-directory
according to their position in the hierarchy, comes from Bazel.

Bazel is a bit odd in it’s own right. the underlying data model is good
(hierarchy + cached dependency graph) but getting there is hell it (like SBT) is
also a 3-layer interpretation model, but layers 1 & 2 are almost exactly the
same: mutable python which performs global side effects (layer 3 is the same
dependency-graph evaluator as SBT/mill)

You end up having to deal with a non-trivial python codebase where everything
happens via

```python
do_something(name="blah")
```

or

```python
do_other_thing(dependencies=["blah"])

```
where `"blah"` is a global identifier that is often constructed programmatically
via string concatenation and passed around. This is quite challenging.

Having the two layers be “just python” is great since people know python, but I
think unnecessary two have two layers ("evaluating macros" and "evaluating rule
impls") that are almost exactly the same, and I think making them interact via
return values rather than via a global namespace of programmatically-constructed
strings would make it easier to follow.

With Mill, I’m trying to collapse Bazel’s Python layer 1 & 2 into just 1 layer
of Scala, and have it define its dependency graph/hierarchy by returning
values, rather than by calling global-side-effecting APIs. I've had trouble
trying to teach people how-to-bazel at work, and am pretty sure we can make
something that's easier to use.

### Scala.Rx

Mill's "direct-style" applicative syntax is inspired by my old
[Scala.Rx](https://github.com/lihaoyi/scala.rx) project. While there are
differences (Mill captures the dependency graph lexically using Macros, Scala.Rx
captures it at runtime, they are pretty similar.

The end-goal is the same: to write code in a "direct style" and have it
automatically "lifted" into a dependency graph, which you can introspect and use
for incremental updates at runtime.

Scala.Rx is itself build upon the 2010 paper
[Deprecating the Observer Pattern](https://infoscience.epfl.ch/record/148043/files/DeprecatingObserversTR2010.pdf).

### CBT

Mill looks a lot like [CBT](https://github.com/cvogt/cbt). The inheritance based
model for customizing `Module`s/`ScalaModule`s comes straight from there, as
does the "command line path matches Scala selector path" idea. Most other things
are different though: the reified dependency graph, the execution model, the
caching module all follow Bazel more than they do CBT


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

### Release Criteria

The final milestone before a public release, is for Mill to be able to fully
substitute SBT in developing the following projects:

- https://github.com/lihaoyi/mill: a single-scala-version, single-platform Scala
  project

- https://github.com/lihaoyi/acyclic: a Scala project cross-published against
  Scala {2.10, 2.11, 2.12}

- https://github.com/playframework/play-json: a Scala project with multiple
  submodules partially-cross-published against Scala {2.10, 2.11, 2.12} X {JVM,
  JS}

- https://github.com/lihaoyi/utest: a Scala project fully-cross-published
  against Scala {2.10, 2.11, 2.12} X {JVM, JS, Native}

- https://github.com/gitbucket/gitbucket: a standalone  *application* build 
  using Scala (v.s. all the libraries listed above) with a simple build

Each of these are relatively simple projects. Satisfying all of their
requirements (codegen, building, testing, publishing, etc.) and being happy with
the Mill code necessary to do so (both in their build code, as well as Mill's
own implementation). Meeting that baseline is the minimum we should support
before advertising Mill to the general community
