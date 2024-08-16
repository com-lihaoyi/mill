// There are three primary kinds of _Tasks_ that you should care about:
//
// * <<_sources>>, defined using `Task.sources {...}`
// * <<_targets>>, defined using `Task {...}`
// * <<_commands>>, defined using `Task.command {...}`

// === Sources


import mill.{Module, T, _}

def sources = Task.source { millSourcePath / "src" }
def resources = Task.source { millSourcePath / "resources" }


// ``Source``s are defined using `Task.source{...}` taking one `os.Path`, or `Task.sources{...}`,
// taking multiple ``os.Path``s as arguments. A ``Source``'s':
// its build signature/`hashCode` depends not just on the path
// it refers to (e.g. `foo/bar/baz`) but also the MD5 hash of the filesystem
// tree under that path.
//
// `Task.source` and `Task.sources` are most common inputs in any Mill build:
// they watch source files and folders and cause downstream targets to
// re-compute if a change is detected.

// === Targets

def allSources = Task {
  os.walk(sources().path)
    .filter(_.ext == "java")
    .map(PathRef(_))
}

def lineCount: T[Int] = Task {
  println("Computing line count")
  allSources()
    .map(p => os.read.lines(p.path).size)
    .sum
}

// [graphviz]
// ....
// digraph G {
//   rankdir=LR
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   sources -> allSources -> lineCount
//   sources [color=white]
// }
// ....
//
// ``Target``s are defined using the `def foo = Task {...}` syntax, and dependencies
// on other targets are defined using `foo()` to extract the value from them.
// Apart from the `foo()` calls, the `Task {...}` block contains arbitrary code that
// does some work and returns a result.

// If a target's inputs change but its output does not, e.g. someone changes a
// comment within the source files that doesn't affect the classfiles, then
// downstream targets do not re-evaluate. This is determined using the
// `.hashCode` of the Target's return value.

/** Usage

> ./mill show lineCount
Computing line count
16

> ./mill show lineCount # line count already cached, doesn't need to be computed
16

*/

// The return-value of targets has to be JSON-serializable via
// {upickle-github-url}[uPickle]. You can run targets directly from the command
// line, or use `show` if you want to see the JSON content or pipe it to
// external tools.

// Each target, e.g. `classFiles`, is assigned a {mill-doc-url}/api/latest/mill/api/Ctx.html#dest:os.Path[Task.dest]
// folder e.g. `out/classFiles.dest/` on disk as scratch space & to store its
// output files , and its returned metadata is automatically JSON-serialized
// and stored at `out/classFiles.json`. If you want to return a file or a set
// of files as the result of a `Target`, write them to disk within your `Task.dest`
// folder and return a `PathRef()` that referencing the files or folders
// you want to return:

def classFiles = Task {
  println("Generating classfiles")

  os.proc("javac", allSources().map(_.path), "-d", Task.dest)
    .call(cwd = Task.dest)

  PathRef(Task.dest)
}

def jar = Task {
  println("Generating jar")
  os.copy(classFiles().path, Task.dest, mergeFolders = true)
  os.copy(resources().path, Task.dest, mergeFolders = true)

  os.proc("jar", "-cfe", Task.dest / "foo.jar", "foo.Foo", ".").call(cwd = Task.dest)

  PathRef(Task.dest / "foo.jar")
}

// [graphviz]
// ....
// digraph G {
//   rankdir=LR
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   allSources -> classFiles -> jar
//   resources -> jar
//   allSources [color=white]
//   resources [color=white]
// }
// ....

/** Usage

> ./mill jar
Generating classfiles
Generating jar

> ./mill show jar
".../out/jar.dest/foo.jar"

*/

// Targets can depend on other targets via the `foo()` syntax.
// The graph of inter-dependent targets is evaluated in topological order; that
// means that the body of a target will not even begin to evaluate if one of its
// upstream dependencies has failed. Similar, even if the upstream targets is
// not used in one branch of an `if` condition, it will get computed regardless
// before the `if` condition is even considered.
//
// The following example demonstrates this behavior, with the `println` defined
// in `def largeFile` running even though the `largeFile()` branch of the
// `if` conditional does not get used:

def largeFile = Task {
  println("Finding Largest File")
  allSources()
    .map(_.path)
    .filter(_.ext == "java")
    .maxBy(os.read.lines(_).size)
}

def hugeFileName = Task {
  if (lineCount() > 999) largeFile().last
  else "<no-huge-file>"
}

// [graphviz]
// ....
// digraph G {
//   rankdir=LR
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   allSources -> largeFile-> hugeFileName
//   allSources [color=white]
// }
// ....

/** Usage

> ./mill show lineCount
16

> ./mill show hugeFileName # This still runs `largestFile` even though `lineCount() < 999`
Finding Largest File
"<no-huge-file>"

*/

// uPickle comes with built-in support for most Scala primitive types and
// builtin data structures: tuples, collections, ``PathRef``s, etc. can be
// returned and automatically serialized/de-serialized as necessary. One
// notable exception is ``case class``es: if you want return your own
// `case class`, you must mark it JSON-serializable by adding the following
// `implicit` to its companion object:

case class ClassFileData(totalFileSize: Long, largestFile: String)
object ClassFileData {
  implicit val rw: upickle.default.ReadWriter[ClassFileData] = upickle.default.macroRW
}

def summarizeClassFileStats = Task {
  val files = os.walk(classFiles().path)
  ClassFileData(
    totalFileSize = files.map(os.size(_)).sum,
    largestFile = files.maxBy(os.size(_)).last
  )
}

// [graphviz]
// ....
// digraph G {
//   rankdir=LR
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   classFiles -> summarizedClassFileStats
//   classFiles [color=white]
// }
// ....

/** Usage

> ./mill show summarizeClassFileStats
{
  "totalFileSize": ...,
  "largestFile": "..."
}

*/



// === Commands

def run(args: String*) = Task.command {
  os.proc(
      "java",
      "-cp", s"${classFiles().path}:${resources().path}",
      "foo.Foo",
      args
    )
    .call(stdout = os.Inherit)
}

// [graphviz]
// ....
// digraph G {
//   rankdir=LR
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   classFiles -> run
//   resources -> run
//   classFiles [color=white]
//   resources [color=white]
// }
// ....

// Defined using `Task.command {...}` syntax, ``Command``s can run arbitrary code, with
// dependencies declared using the same `foo()` syntax (e.g. `classFiles()` above).
// Commands can be parametrized, but their output is not cached, so they will
// re-evaluate every time even if none of their inputs have changed.
// A command with no parameter is defined as `def myCommand() = Task.command {...}`.
// It is a compile error if `()` is missing.
//
// Like <<_targets>>, a command only evaluates after all its upstream
// dependencies have completed, and will not begin to run if any upstream
// dependency has failed.
//
// Commands are assigned the same scratch/output folder `out/run.dest/` as
// Targets are, and its returned metadata stored at the same `out/run.json`
// path for consumption by external tools.
//
// Commands can only be defined directly within a `Module` body.

// === Overrides

// Targets and sources can be overriden, with the override task callable via `super`.
// This lets you override-and-extend source lists the same way you would any other target
// definition:
//

trait Foo extends Module {
  def sourceRoots = Task.sources(millSourcePath / "src")
  def sourceContents = Task {
    sourceRoots()
      .flatMap(pref => os.walk(pref.path))
      .filter(_.ext == "txt")
      .sorted
      .map(os.read(_))
  }
}

trait Bar extends Foo {
  def additionalSources = Task.sources(millSourcePath / "src2")
  def sourceRoots = Task { super.sourceRoots() ++ additionalSources() }
}

object bar extends Bar

// [graphviz]
// ....
// digraph G {
//   rankdir=LR
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   "bar.sourceRoots.super" -> "bar.sourceRoots" -> "bar.sourceContents"
//   "bar.additionalSources" -> "bar.sourceRoots"
// }
// ....
/** Usage

> ./mill show bar.sourceContents # includes both source folders
[
  "File Data From src/",
  "File Data From src2/"
]

*/
