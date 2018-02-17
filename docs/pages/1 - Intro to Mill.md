Mill is your shiny new Scala build tool! Confused by SBT? Frustrated by Maven?
Perplexed by Gradle? Give Mill a try!

Mill is a general purpose build-tool. It has built in support for the
[Scala](https://www.scala-lang.org/) programming language, and can serve as a
replacement for [SBT](http://www.scala-sbt.org/), but can also be extended to
support any other language or platform via modules (written in Java or Scala) or
through external subprocesses.

Mill aims for simplicity by re-using concepts you are already familiar with to
let you define your project's build. Mill's `build.sc` files are Scala scripts.

To get started, download Mill and install it into your system via the following
`curl`/`chmod` command:

```bash
sudo curl -L -o /usr/local/bin/mill https://github.com/lihaoyi/mill/releases/download/0.0.7/0.0.7 && sudo chmod +x /usr/local/bin/mill
```

The simplest Mill build for a Scala project looks as follows:

```scala
import mill._
import mill.scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
}
```

This would build a project laid out as follows:

```
build.sc
foo/
    src/
        Main.scala
    resources/
        ...
out/
    foo/
        ... 
```

The source code for this module would live in the `foo/src/` folder, matching
the name you assigned to the module. Output for this module (compiled files,
resolved dependency lists, ...) would live in `out/foo/`.

This can be run from the Bash shell via:

```bash
$ mill foo.compile        # compile sources into classfiles

$ mill foo.run            # run the main method, if any

$ mill foo.jar            # bundle the classfiles into a jar

$ mill foo.assembly       # bundle the classfiles and all dependencies into a jar 
```

The most common **tasks** that Mill can run are cached **targets**, such as
`compile`, and un-cached **commands** such as `foo.run`. Targets do not
re-evaluate unless one of their inputs changes, where-as commands re-run every
time.


## Multiple Modules

```scala
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
}
object bar extends ScalaModule {
  def moduleDeps = Seq(foo)
  def scalaVersion = "2.12.4"
}
```

You can define multiple modules the same way you define a single module, using
`def moduleDeps` to define the relationship between them. The above build
expects the following project layout:

```
build.sc
foo/
    src/
        Main.scala
    resources/
        ...
bar/
    src/
        Main2.scala
    resources/
        ...
out/
    foo/
        ... 
    bar/
        ... 
```

And can be built/run using:

```bash
$ mill foo.compile        
$ mill bar.compile        

$ mill foo.run            
$ mill bar.run            

$ mill foo.jar            
$ mill bar.jar            

$ mill foo.assembly        
$ mill bar.assembly        
```

Mill's evaluator will ensure that the modules are compiled in the right order,
and re-compiled as necessary when source code in each module changes.

Modules can also be nested:

```scala
import mill._
import mill.scalalib._
object foo extends ScalaModule {
  def scalaVersion = "2.12.4"
  object bar extends ScalaModule {
    def moduleDeps = Seq(foo)
    def scalaVersion = "2.12.4"
  }
}
```

Which would result in a similarly nested project layout:

```
build.sc
foo/
    src/
        Main.scala
    resources/
        ...
    bar/
        src/
            Main2.scala
        resources/
            ...
out/
    foo/
        ...
        bar/
            ...
```

Where the nested modules can be run via:

```bash
$ mill foo.compile        
$ mill foo.bar.compile        

$ mill foo.run            
$ mill foo.bar.run            

$ mill foo.jar            
$ mill foo.bar.jar            

$ mill foo.assembly        
$ mill foo.bar.assembly        
```

## Watch and Re-evaluate

You can use the `--watch` flag to make Mill watch a task's inputs, re-evaluating
the task as necessary when the inputs change:

```bash
$ mill --watch foo.compile 
$ mill --watch foo.run 
```

Mill's `--watch` flag watches both the files you are building using Mill, as
well as Mill's own `build.sc` file and anything it imports, so any changes to
your `build.sc` will automatically get picked up.

## Command-line Tools

Mill comes built in with a small number of useful command-line utilities:

### all

```bash
mill all foo.{compile,run}
mill all "foo.{compile,run}"
mill all foo.compile foo.run
mill all _.compile # run compile for every top-level module
mill all __.compile  # run compile for every module
```

`all` runs multiple tasks in a single command

### resolve

```bash
mill resolve foo.{compile,run}
mill resolve "foo.{compile,run}"
mill resolve foo.compile foo.run
mill resolve _.compile          # list the compile tasks for every top-level module
mill resolve __.compile         # list the compile tasks for every module
mill resolve _                  # list every top level module or task
mill resolve foo._              # list every task directly within the foo module
mill resolve __                 # list every module or task recursively
mill resolve foo._              # list every task recursively within the foo module
```

`resolve` lists the tasks that match a particular query, without running them.
This is useful for "dry running" an `mill all` command to see what would be run
before you run them, or to explore what modules or tasks are available from the
command line using `resolve _`, `resolve foo._`, etc.


### describe

```bash
$ mill describe core.run

core.run(ScalaModule.scala:211)
Inputs:
    core.mainClass
    core.runClasspath
    core.forkArgs
    core.forkEnv
```

`describe` is a more verbose version of [resolve](#resolve). In addition to
printing out the name of one-or-more tasks, it also display's it's source
location and a list of input tasks. This is very useful for debugging and
interactively exploring the structure of your build from the command line.

`describe` also works with the same `_`/`__` wildcard/query syntaxes that
[all](#all)/[resolve](#resolve) do:


```bash
mill describe foo.compile
mill describe foo.{compile,run}
mill describe "foo.{compile,run}"
mill describe foo.compile foo.run
mill describe _.compile
mill describe __.compile
mill describe _
mill describe foo._
mill describe __
mill describe foo._
```

### show

By default, Mill does not print out the metadata from evaluating a task. Most
people would not be interested in e.g. viewing the metadata related to
incremental compilation: they just want to compile their code! However, if you
want to inspect the build to debug problems, you can make Mill show you the
metadata output for a task using the `show` flag:

You can also ask Mill to display the metadata output of a task using `show`:

```bash
$ mill show foo.compile
{
    "analysisFile": "/Users/lihaoyi/Dropbox/Github/test/out/foo/compile/dest/zinc",
    "classes": {
        "path": "/Users/lihaoyi/Dropbox/Github/test/out/foo/compile/dest/classes"
    }
}
```

This also applies to tasks which hold simple configurable values:

```bash
$ mill show foo.sources
[
    {"path": "/Users/lihaoyi/Dropbox/Github/test/foo/src"}
]

$ mill show foo.compileDepClasspath
[
    {"path": ".../org/scala-lang/scala-compiler/2.12.4/scala-compiler-2.12.4.jar"},
    {"path": ".../org/scala-lang/scala-library/2.12.4/scala-library-2.12.4.jar"},
    {"path": ".../org/scala-lang/scala-reflect/2.12.4/scala-reflect-2.12.4.jar"},
    {"path": ".../org/scala-lang/modules/scala-xml_2.12/1.0.6/scala-xml_2.12-1.0.6.jar"}
]
```

`show` is also useful for interacting with Mill from external tools, since the
JSON it outputs is structured and easily parsed & manipulated.

## IntelliJ Support

Mill supports IntelliJ by default. Use `mill mill.scalalib.GenIdea/idea` to
generate an IntelliJ project config for your build.

This also configures IntelliJ to allow easy navigate & code-completion within
your build file itself.


Any flags passed *before* the name of the task (e.g. `foo.compile`) are given to
Mill, while any arguments passed *after* the task are given to the task itself.
For example:

## The Build Repl

```bash
$ mill
Loading...
@ foo
res1: foo.type = ammonite.predef.build#foo:2
Commands:
    .runLocal(args: String*)()
    .run(args: String*)()
    .runMainLocal(mainClass: String, args: String*)()
    .runMain(mainClass: String, args: String*)()
    .console()()
Targets:
    .allSources()
    .artifactId()
    .artifactName()
...

@ foo.compile
res3: mill.package.T[mill.scalalib.CompilationResult] = mill.scalalib.ScalaModule#compile:152
Inputs:
    foo.scalaVersion
    foo.allSources
    foo.compileDepClasspath
...
    
@ foo.compile()
res2: mill.scalalib.CompilationResult = CompilationResult(
  root/'Users/'lihaoyi/'Dropbox/'Github/'test/'out/'foo/'compile/'dest/'zinc,
  PathRef(root/'Users/'lihaoyi/'Dropbox/'Github/'test/'out/'foo/'compile/'dest/'classes, false)
)
```

You can run `mill` alone to open a build REPL; this is a Scala console with your
`build.sc` loaded, which lets you run tasks interactively. The task-running
syntax is slightly different from the command-line, but more in-line with how
you would depend on tasks from within your build file.

You can use this REPL to run build commands quicker, due to keeping the JVM warm
between runs, or to interactively explore your build to see what is available.



