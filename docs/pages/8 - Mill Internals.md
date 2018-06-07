
## Mill Design Principles

A lot of Mill's design principles are intended to fix SBT's flaws, as described
in the blog post
[What's wrong with SBT](http://www.lihaoyi.com/post/SowhatswrongwithSBT.html),
building on the best ideas from tools like [CBT](https://github.com/cvogt/cbt)
and [Bazel](https://bazel.build/), and the ideas from my blog post
[Build Tools as
Pure Functional Programs](http://www.lihaoyi.com/post/BuildToolsasPureFunctionalPrograms.html).
Before working on Mill, read through that post to understand where it is coming
from!

### Dependency graph first

Mill's most important abstraction is the dependency graph of `Task`s.
Constructed using the `T {...}` `T.task {...}` `T.command {...}` syntax, these
track the dependencies between steps of a build, so those steps can be executed
in the correct order, queried, or parallelized.

While Mill provides helpers like `ScalaModule` and other things you can use to
quickly instantiate a bunch of related tasks (resolve dependencies, find
sources, compile, package into jar, ...) these are secondary. When Mill
executes, the dependency graph is what matters: any other mode of organization
(hierarchies, modules, inheritance, etc.) is only important to create this
dependency graph of `Task`s.

### Builds are hierarchical

The syntax for running targets from the command line `mill Foo.bar.baz` is
the same as referencing a target in Scala code, `Foo.bar.baz`

Everything that you can run from the command line lives in an object hierarchy
in your `build.sc` file. Different parts of the hierarchy can have different
`Target`s available: just add a new `def foo = T {...}` somewhere and you'll be
able to run it.

Cross builds, using the `Cross` data structure, are just another kind of node in
the object hierarchy. The only difference is syntax: from the command line you'd
run something via `mill core.cross[a].printIt` while from code you use
`core.cross("a").printIt` due to different restrictions in Scala/Bash syntax.

### Caching by default

Every `Target` in a build, defined by `def foo = T {...}`, is cached by default.
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
defining their `Task`s, we use the `T {...}`/`T.task {...}`/`T.command {...}`
macros which allow you to use `Task#apply()` within the block to "extract" a
value.

```scala
def test() = T.command {
  TestRunner.apply(
   "mill.UTestFramework",
   runDepClasspath().map(_.path) :+ compile().path,
   Seq(compile().path)
  
}
```

This is roughly equivalent to the following:

```scala
def test() = T.command { T.zipMap(runDepClasspath, compile, compile) { 
  (runDepClasspath1, compile2, compile3) =>
  TestRunner.apply(
    "mill.UTestFramework",
    runDepClasspath1.map(_.path) :+ compile2.path,
    Seq(compile3.path)
  )
}
```

This is similar to SBT's `:=`/`.value` macros, or `scala-async`'s
`async`/`await`. Like those, the `T {...}` macro should let users program most of
their code in a "direct" style and have it "automatically" lifted into a graph
of `Task`s.

## How Mill aims for Simple

Why should you expect that the Mill build tool can achieve simple, easy &
flexible, where other build tools in the past have failed?

Build tools inherently encompass a huge number of different concepts:

- What "Tasks" depends on what?
- How do I define my own tasks?
- Where do source files come from?
- What needs to run in what order to do what I want?
- What can be parallelized and what can't?
- How do tasks pass data to each other? What data do they pass?
- What tasks are cached? Where?
- How are tasks run from the command line?
- How do you deal with the repetition inherent in a build? (e.g. compile, run &
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

- Source files default to a folder in `core/test/`, `core/test/src/`

- Be runnable from the command-line via `mill core.test.compile`

- Be referenced programmatically (from other `Target`s) via `core.test.compile`

From the position of any `Target` within the object hierarchy, you immediately
know how to run it, find its output files, find any caches, or refer to it from
other `Target`s. You know up-front where the `Target`'s data "lives" on disk, and
are sure that it will never clash with any other `Target`'s data.

## The Call Graph

The Scala call graph of "which target references which other target" is core to
how Mill operates. This graph is reified via the `T {...}` macro to make it
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
  T {...}`

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

With Mill, you can take the Scala call graph, wrap everything in the `T {...}`
macro, and get a `Target`-dependency graph that matches exactly the call-graph
you already had:

```scala
val b = T { ... }
val c = T { ... }
val d = T { ... }
val a = T { f(b(), c(), d()) }
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
  inheritance

- Modifying the default `Target`s within a project, making use of the old value
  to compute the new value, is simply `override`ing them and using `super.foo()`

- Required configuration parameters within a `project` are `abstract` members

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
single-build-hierarchy, where every Target has an on-disk-cache/output-folder
according to their position in the hierarchy, comes from Bazel.

Bazel is a bit odd in its own right. The underlying data model is good
(hierarchy + cached dependency graph) but getting there is hell. It (like SBT) is
also a 3-layer interpretation model, but layers 1 & 2 are almost exactly the
same: mutable python which performs global side effects (layer 3 is the same
dependency-graph evaluator as SBT/mill).

You end up having to deal with a non-trivial python codebase where everything
happens via:

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
captures it at runtime), they are pretty similar.

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
