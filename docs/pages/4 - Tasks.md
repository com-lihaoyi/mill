One of Mill's core abstractions is its *Task Graph*: this is how Mill defines,
orders and caches work it needs to do, and exists independently of any support
for building Scala.

The following is a simple self-contained example using Mill to compile Java:

```scala
, mill._

// sourceRoot -> allSources -> classFiles
//                                |
//                                v
//           resourceRoot ---->  jar

def sourceRoot = T.sources { os.pwd / 'src }

def resourceRoot = T.sources { os.pwd / 'resources }

def allSources = T { sourceRoot().flatMap(p => os.walk(p.path)).map(PathRef(_)) }

def classFiles = T { 
  os.makeDir.all(T.ctx().dest)
  
  %("javac", sources().map(_.path.toString()), "-d", T.ctx().dest)(wd = T.ctx().dest)
  PathRef(T.ctx().dest) 
}

def jar = T { Jvm.createJar(Loose.Agg(classFiles().path) ++ resourceRoot().map(_.path)) }

def run(mainClsName: String) = T.command {
  os.proc('java, "-cp", classFiles().path, mainClsName).call()
}
```


Here, we have two `T.sources`s, `sourceRoot` and `resourceRoot`, which act as the
roots of our task graph. `allSources` depends on `sourceRoot` by calling
`sourceRoot()` to extract its value, `classFiles` depends on `allSources` the
same way, and `jar` depends on both `classFiles` and `resourceRoot`.

Filesystem operations in Mill are done using the
[Ammonite-Ops](http://ammonite.io/#Ammonite-Ops) library.

The above build defines the following task graph:

```
sourceRoot -> allSources -> classFiles
                               |
                               v
          resourceRoot ---->  jar
```

When you first evaluate `jar` (e.g. via `mill jar` at the command line), it will
evaluate all the defined targets: `sourceRoot`, `allSources`, `classFiles`,
`resourceRoot` and `jar`.

Subsequent `mill jar`s will evaluate only as much as is necessary, depending on
what input sources changed:

- If the files in `sourceRoot` change, it will re-evaluate `allSources`,
  compiling to `classFiles`, and building the `jar`

- If the files in `resourceRoot` change, it will only re-evaluate `jar` and use
  the cached output of `allSources` and `classFiles`

Apart from the `foo()` call-sites which define what each targets depend on, the
code within each `T {...}` wrapper is arbitrary Scala code that can compute an
arbitrary result from its inputs.

## Different Kinds of Tasks

There are three primary kinds of *Tasks* that you should care about:

- [Targets](#targets), defined using `T {...}`
- [Sources](#sources), defined using `T.sources {...}`
- [Commands](#commands), defined using `T.command {...}`

### Targets

```scala
def allSources = T { os.walk(sourceRoot().path).map(PathRef(_)) }
```

`Target`s are defined using the `def foo = T {...}` syntax, and dependencies on
other targets are defined using `foo()` to extract the value from them. Apart
from the `foo()` calls, the `T {...}` block contains arbitrary code that does
some work and returns a result.

Each target, e.g. `classFiles`, is assigned a path on disk as scratch space & to
store its output files at `out/classFiles/dest/`, and its returned metadata is
automatically JSON-serialized and stored at `out/classFiles/meta.json`. The
return-value of targets has to be JSON-serializable via
[uPickle](https://github.com/lihaoyi/upickle).

In case you want return your own
case class (e.g. `MyCaseClass`), you can make it JSON-serializable by adding the
following implicit def to its companion object:

```scala
object MyCaseClass {
  implicit def rw: upickle.default.ReadWriter[MyCaseClass] = upickle.default.macroRW
}
```

If you want to return a file or a set of files as the result of a `Target`,
write them to disk within your `T.ctx().dest` available through the
[Task Context API](#task-context-api) and return a `PathRef` to the files you
wrote.

If a target's inputs change but its output does not, e.g. someone changes a
comment within the source files that doesn't affect the classfiles, then
downstream targets do not re-evaluate. This is determined using the `.hashCode`
of the Target's return value. For targets returning `ammonite.ops.Path`s that
reference files on disk, you can wrap the `Path` in a `PathRef` (shown above)
whose `.hashCode()` will include the hashes of all files on disk at time of
creation.

The graph of inter-dependent targets is evaluated in topological order; that
means that the body of a target will not even begin to evaluate if one of its
upstream dependencies has failed. This is unlike normal Scala functions: a plain
old function `foo` would evaluate halfway and then blow up if one of `foo`'s
dependencies throws an exception.

Targets cannot take parameters and must be 0-argument `def`s defined directly
within a `Module` body.

### Sources

```scala
def sourceRootPath = os.pwd / 'src

def sourceRoots = T.sources { sourceRootPath }
```

`Source`s are defined using `T.sources { ... }`, taking one-or-more
`ammonite.ops.Path`s as arguments. A `Source` is a subclass of
`Target[Seq[PathRef]]`: this means that its build signature/`hashCode` depends
not just on the path it refers to (e.g. `foo/bar/baz`) but also the MD5 hash of
the filesystem tree under that path.

`T.sources` also has an overload which takes `Seq[PathRef]`, to let you
override-and-extend source lists the same way you would any other `T {...}`
definition:

```scala
def additionalSources = T.sources { os.pwd / 'additionalSources }
def sourceRoots = T.sources { super.sourceRoots() ++ additionalSources() }
```

### Commands

```scala
def run(mainClsName: String) = T.command {
  os.proc('java, "-cp", classFiles().path, mainClsName).call()
}
```

Defined using `T.command { ... }` syntax, `Command`s can run arbitrary code, with
dependencies declared using the same `foo()` syntax (e.g. `classFiles()` above).
Commands can be parametrized, but their output is not cached, so they will
re-evaluate every time even if none of their inputs have changed.

Like [Targets](#targets), a command only evaluates after all its upstream
dependencies have completed, and will not begin to run if any upstream
dependency has failed.

Commands are assigned the same scratch/output folder `out/run/dest/` as
Targets are, and its returned metadata stored at the same `out/run/meta.json`
path for consumption by external tools.

Commands can only be defined directly within a `Module` body.

## Task Context API

There are several APIs available to you within the body of a `T {...}` or
`T.command {...}` block to help your write the code implementing your Target or
Command:

### mill.util.Ctx.Dest

- `T.ctx().dest`
- `implicitly[mill.util.Ctx.Dest]`

This is the unique `out/classFiles/dest/` path or `out/run/dest/` path that is
assigned to every Target or Command. It is cleared before your task runs, and
you can use it as a scratch space for temporary files or a place to put returned
artifacts. This is guaranteed to be unique for every `Target` or `Command`, so
you can be sure that you will not collide or interfere with anyone else writing
to those same paths.

### mill.util.Ctx.Log

- `T.ctx().log`
- `implicitly[mill.util.Ctx.Log]`

This is the default logger provided for every task. While your task is running,
`System.out` and `System.in` are also redirected to this logger. The logs for a
task are streamed to standard out/error as you would expect, but each task's
specific output is also streamed to a log file on disk, e.g. `out/run/log` or
`out/classFiles/log` for you to inspect later.

Messages logged with `log.debug` appear by default only in the log files.
You can use the `--debug` option when running mill to show them on the console too.

### mill.util.Ctx.Env

- `T.ctx().env`
- `implicitly[mill.util.Ctx.Env]`

Mill keeps a long-lived JVM server to avoid paying the cost of recurrent 
classloading. Because of this, running `System.getenv` in a task might not yield
up to date environment variables, since it will be initialised when the server 
starts, rather than when the client executes. To circumvent this, mill's client 
sends the environment variables to the server as it sees them, and the server 
makes them available as a `Map[String, String]` via the `Ctx` API. 

If the intent is to always pull the latest environment values, the call should 
be wrapped in an `Input` as such : 

```scala
def envVar = T.input { T.ctx().env.get("ENV_VAR") }
```

## Other Tasks

- [Anonymous Tasks](#anonymous-tasks), defined using `T.task {...}`
- [Persistent Targets](#persistent-targets)
- [Inputs](#inputs)
- [Workers](#workers)


### Anonymous Tasks

```scala
def foo(x: Int) = T.task { ... x ... bar() ... }
```

You can define anonymous tasks using the `T.task { ... }` syntax. These are not
runnable from the command-line, but can be used to share common code you find
yourself repeating in `Target`s and `Command`s.

```scala
def downstreamTarget = T { ... foo() ... } 
def downstreamCommand = T.command { ... foo() ... } 
```
Anonymous task's output does not need to be JSON-serializable, their output is
not cached, and they can be defined with or without arguments. Unlike
[Targets](#targets) or [Commands](#commands), anonymous tasks can be defined
anywhere and passed around any way you want, until you finally make use of them
within a downstream target or command.

While an anonymous task `foo`'s own output is not cached, if it is used in a
downstream target `bar` and the upstream targets `baz` `qux` haven't changed,
`bar`'s cached output will be used and `foo`'s evaluation will be skipped
altogether.

### Persistent Targets
```scala
def foo = T.persistent { ... }
```

Identical to [Targets](#targets), except that the `dest/` folder is not
cleared in between runs.

This is useful if you are running external incremental-compilers, such as
Scala's [Zinc](https://github.com/sbt/zinc), Javascript's
[WebPack](https://webpack.js.org/), which rely on filesystem caches to speed up
incremental execution of their particular build step.

Since Mill no longer forces a "clean slate" re-evaluation of `T.persistent`
targets, it is up to you to ensure your code (or the third-party incremental
compilers you rely on!) are deterministic. They should always converge to the
same outputs for a given set of inputs, regardless of what builds and what
filesystem states existed before.

### Inputs

```scala
def foo = T.input { ... }
```

A generalization of [Sources](#sources), `T.input`s are tasks that re-evaluate
*every time* (unlike [Anonymous Tasks](#anonymous-tasks)), containing an
arbitrary block of code.

Inputs can be used to force re-evaluation of some external property that may
affect your build. For example, if I have a [Target](#targets) `bar` that makes
use of the current git version:

```scala
def bar = T { ... os.proc("git", "rev-parse", "HEAD").call().out.string ... }
```

`bar` will not know that `git rev-parse` can change, and will
not know to re-evaluate when your `git rev-parse HEAD` *does* change. This means
`bar` will continue to use any previously cached value, and `bar`'s output will
be out of date!

To fix this, you can wrap your `git rev-parse HEAD` in a `T.input`:

```scala
def foo = T.input { os.proc("git", "rev-parse", "HEAD").call().out.string }
def bar = T { ... foo() ... }
```

This makes `foo` will always re-evaluate every build; if `git rev-parse HEAD`
does not change, that will not invalidate `bar`'s caches. But if `git rev-parse
HEAD` *does* change, `foo`'s output will change and `bar` will be correctly
invalidated and re-compute using the new version of `foo`.

Note that because `T.input`s re-evaluate every time, you should ensure that the
code you put in `T.input` runs quickly. Ideally it should just be a simple check
"did anything change?" and any heavy-lifting can be delegated to downstream
targets.

### Workers

```scala
def foo = T.worker { ... }
```

Most tasks dispose of their in-memory return-value every evaluation; in the case
of [Targets](#targets), this is stored on disk and loaded next time if
necessary, while [Commands](#commands) just re-compute them each time. Even if
you use `--watch` or the Build REPL to keep the Mill process running, all this
state is still discarded and re-built every evaluation.

Workers are unique in that they store their in-memory return-value between
evaluations. This makes them useful for storing in-memory caches or references
to long-lived external worker processes that you can re-use.

Mill uses workers to manage long-lived instances of the
[Zinc Incremental Scala Compiler](https://github.com/sbt/zinc) and the
[Scala.js Optimizer](https://github.com/scala-js/scala-js). This lets us keep
them in-memory with warm caches and fast incremental execution.

Like [Persistent Targets](#persistent-targets), Workers inherently involve
mutable state, and it is up to the implementation to ensure that this mutable
state is only used for caching/performance and does not affect the
externally-visible behavior of the worker.

## Cheat Sheet

The following table might help you make sense of the small collection of
different Task types:

|                                | Target | Command | Source/Input | Anonymous Task | Persistent Target | Worker |
|:-------------------------------|:-------|:--------|:-------------|:---------------|:------------------|:-------|
| Cached on Disk                 | X      | X       |              |                | X                 |        |
| Must be JSON Writable          | X      | X       |              |                | X                 |        |
| Must be JSON Readable          | X      |         |              |                | X                 |        |
| Runnable from the Command Line | X      | X       |              |                | X                 |        |
| Can Take Arguments             |        | X       |              | X              |                   |        |
| Cached between Evaluations     |        |         |              |                |                   | X      |

