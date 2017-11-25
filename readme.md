
# ![Mill Logo](docs/logo.svg) Mill

Your shiny new Scala build tool!

## How to build and test

Run unit test suite:

```bash
sbt core/test
```

Build a standalone executable jar:

```bash
sbt scalaplugin/test:assembly
```

Now you can re-build this very same project using the build.sc file, e.g. re-run
core unit tests

e.g.:
```bash
./scalaplugin/target/mill run Core.compile
./scalaplugin/target/mill run Core.test.compile
./scalaplugin/target/mill run Core.test
./scalaplugin/target/mill run ScalaPlugin.assembly
```

there is already a `watch` option that looks for changes on files, e.g.:

```bash
./scalaplugin/target/mill --watch run Core.compile
```

output will be generated into a the `./out` folder.

Lastly, you can generate IntelliJ Scala project files using Mill via

```bash
./scalaplugin/target/mill idea
```

Allowing you to import a Mill project into Intellij without using SBT

### build.sc

Into a `build.sc` file you can define separate `Module`s (e.g. `ScalaModule`).
Within each `Module` you can define 3 type of task:

- `Target`: take no argument, output is cached and should be serializable; run
  from `bash` (e.g. `def foo = T{...}`)
- `Command`: take serializable arguments, output is not cached; run from `bash`
  (arguments with `scopt`) (e.g. `def foo = T.command{...}`)
- `Task`: take arguments, output is not cached; do not run from `bash` (e.g.
  `def foo = T.task{...}` )

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

The syntax for running targets from the command line `mill run Foo.bar.baz` is
the same as referencing a target in Scala code, `Foo.bar.baz`

Everything that you can run from the command line lives in an object hierarchy
in your `build.sc` file. Different parts of the hierarchy can have different
`Target`s available: just add a new `def foo = T{...}` somewhere and you'll be
able to run it.

Cross builds, using the `Cross` data structure, are just another kind of node in
the object hierarchy. The only difference is syntax: from the command line you'd
run something via `mill run Core.cross[a].printIt` while from code you use
`Core.cross(List("a")).printIt` due to different restrictions in Scala/Bash
syntax.

### Caching by default

Every `Target` in a build, defined by `def foo = T{...}`, is cached by default.
Currently this is done using a `foo.mill.json` file in the `out/` folder. The
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
`Foo.bar.baz` has a fixed position in the build hierarchy, and thus a fixed
position on disk `out/foo/bar/baz.mill.json`. When the old process dies and a
new process starts, there will be a new instance of `Target` with the same
implementation code and same position in the build hierarchy: this new `Target`
can then load the `out/foo/bar/baz.mill.json` file and pick up where the
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
of Scala, and have it define it’s dependency graph/hierarchy by returning
values, rather than by calling global-side-effecting APIs I've had trouble
trying to teach people how-to-bazel at work, and am pretty sure we can make
something that's easier to use

### Scala.Rx

Mill's "direct-style" applicative syntax is inspired by my old
[Scala.Rx](https://github.com/lihaoyi/scala.rx) project. While there are
differences (Mill captures the dependency graph lexically using Macros, Scala.Rx
captures it at runtime, they are pretty similar.

The end-goal is the same: to write code in a "direct style" and have it
automatically "lifted" into a dependency graph, which you can introspect and use
for incremental updates at runtime.

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

- Sustain it's own development, without needing SBT
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

Each of these are relatively simple projects. Satisfying all of their
requirements (codegen, building, testing, publishing, etc.) and being happy with
the Mill code necessary to do so (both in their build code, as well as Mill's
own implementation). Meeting that baseline is the minimum we should support
before advertising Mill to the general community