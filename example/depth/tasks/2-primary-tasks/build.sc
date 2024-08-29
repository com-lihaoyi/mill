// There are three primary kinds of _Tasks_ that you should care about:
//
// * <<_sources>>, defined using `T.sources {...}`
// * <<_targets>>, defined using `T {...}`
// * <<_commands>>, defined using `T.command {...}`

// === Sources


import mill.{Module, T, _}

def sources = T.source { millSourcePath / "src" }
def resources = T.source { millSourcePath / "resources" }


// ``Source``s are defined using `T.source{...}` taking one `os.Path`, or `T.sources{...}`,
// taking multiple ``os.Path``s as arguments. A ``Source``'s':
// its build signature/`hashCode` depends not just on the path
// it refers to (e.g. `foo/bar/baz`) but also the MD5 hash of the filesystem
// tree under that path.
//
// `T.source` and `T.sources` are most common inputs in any Mill build:
// they watch source files and folders and cause downstream targets to
// re-compute if a change is detected.

// === Targets

def allSources = T {
  os.walk(sources().path)
    .filter(_.ext == "java")
    .map(PathRef(_))
}

def lineCount: T[Int] = T {
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
// ``Target``s are defined using the `def foo = T {...}` syntax, and dependencies
// on other targets are defined using `foo()` to extract the value from them.
// Apart from the `foo()` calls, the `T {...}` block contains arbitrary code that
// does some work and returns a result.
//
// The `os.walk` and `os.read.lines` statements above are from the
// https://github.com/com-lihaoyi/os-lib[OS-Lib] library, which provides all common
// filesystem and subprocess operations for Mill builds. You can see the OS-Lib library
// documentation for more details:
//
// * https://github.com/com-lihaoyi/os-lib[OS-Lib Library Documentation]

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

// Furthermore, when code changes occur, targets only invalidate if the code change
// may directly or indirectly affect it. e.g. adding a comment to `lineCount` will
// not cause it to recompute:

// ```diff
//  def lineCount: T[Int] = T {
//   println("Computing line count")
//+  // Hello World
//   allSources()
//     .map(p => os.read.lines(p.path).size)
//     .sum
// ```
//
// But changing the code of the target or any upstream helper method will cause the
// old value to be invalidated and a new value re-computed (with a new `println`)
// next time it is invoked:
//
// ```diff
//   def lineCount: T[Int] = T {
//-  println("Computing line count")
//+  println("Computing line count!!!")
//   allSources()
//     .map(p => os.read.lines(p.path).size)
//     .sum
// ```
//
// For more information on how the bytecode analysis necessary for invalidating targets
// based on code-changes work, see https://github.com/com-lihaoyi/mill/pull/2417[PR#2417]
// that implemented it.
//
// The return-value of targets has to be JSON-serializable via
// {upickle-github-url}[uPickle]. You can run targets directly from the command
// line, or use `show` if you want to see the JSON content or pipe it to
// external tools. See the uPickle library documentation for more details:
//
// * {upickle-github-url}[uPickle Library Documentation]

// ==== T.dest
//
// Each target, e.g. `classFiles`, is assigned a {mill-doc-url}/api/latest/mill/api/Ctx.html#dest:os.Path[T.dest]
// folder e.g. `out/classFiles.dest/` on disk as scratch space & to store its
// output files , and its returned metadata is automatically JSON-serialized
// and stored at `out/classFiles.json`. If you want to return a file or a set
// of files as the result of a `Target`, write them to disk within your `T.dest`
// folder and return a `PathRef()` that referencing the files or folders
// you want to return:

def classFiles = T {
  println("Generating classfiles")

  os.proc("javac", allSources().map(_.path), "-d", T.dest)
    .call(cwd = T.dest)

  PathRef(T.dest)
}

def jar = T {
  println("Generating jar")
  os.copy(classFiles().path, T.dest, mergeFolders = true)
  os.copy(resources().path, T.dest, mergeFolders = true)

  os.proc("jar", "-cfe", T.dest / "foo.jar", "foo.Foo", ".").call(cwd = T.dest)

  PathRef(T.dest / "foo.jar")
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

// *Note that `os.pwd` of the Mill process is set to an empty `sandbox/` folder by default.*
// This is to stop you from accidentally reading and writing files to the base repository root,
// which would cause problems with Mill's caches not invalidating properly or files from different
// tasks colliding and causing issues.
// You should never use `os.pwd` or rely on the process working directory, and always explicitly
// use `T.dest` or the `.path` of upstream ``PathRef``s when accessing files. In the rare case where
// you truly need the Mill project root folder, you can access it via `T.workspace`
//
// ==== Dependent Targets
//
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

def largeFile = T {
  println("Finding Largest File")
  allSources()
    .map(_.path)
    .filter(_.ext == "java")
    .maxBy(os.read.lines(_).size)
}

def hugeFileName = T{
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

// ==== Custom Types
//
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

def summarizeClassFileStats = T{
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

def run(mainClass: String, args: String*) = T.command {
  os.proc(
      "java",
      "-cp", s"${classFiles().path}:${resources().path}",
      mainClass,
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

// Defined using `T.command {...}` syntax, ``Command``s can run arbitrary code, with
// dependencies declared using the same `foo()` syntax (e.g. `classFiles()` above).
// Commands can be parametrized, but their output is not cached, so they will
// re-evaluate every time even if none of their inputs have changed.
// A command with no parameter is defined as `def myCommand() = T.command {...}`.
// It is a compile error if `()` is missing.
//
// Targets can take command line params, parsed by the https://github.com/com-lihaoyi/mainargs[MainArgs]
// library. Thus the signature `def run(mainClass: String, args: String*)` takes
// params of the form `--main-class <str> <arg1> <arg2> ... <argn>`:

/** Usage

> ./mill run --main-class foo.Foo hello world
Foo.value: 31337
args: hello world
foo.txt resource: My Example Text

*/

// Command line arguments can take most primitive types: `String`, `Int`, `Boolean`, etc.,
// along with `Option[T]` representing optional values and `Seq[T]` representing repeatable values,
// and `mainargs.Flag` representing flags and `mainargs.Leftover[T]` representing any command line
// arguments not parsed earlier. Default values for command line arguments are also supported.
// See the mainargs documentation for more details:
//
// * [MainArgs Library Documentation](https://github.com/com-lihaoyi/mainargs[MainArgs])
//
// By default, all command parameters need to be named, except for variadic parameters
// of type `T*` or `mainargs.Leftover[T]`. You can use the flag `--allow-positional-command-args`
// to allow arbitrary arguments to be passed positionally, as shown below:

/** Usage

> ./mill run foo.Foo hello world # this raises an error because `--main-class` is not given
error: Missing argument: --mainClass <str>
Expected Signature: run
  --mainClass <str>
  args <str>...
...

> ./mill --allow-positional-command-args run foo.Foo hello world # this succeeds due to --allow-positional-command-args
Foo.value: 31337
args: hello world
foo.txt resource: My Example Text

*/


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

// Tasks can be overriden, with the overriden task callable via `super`.
// You can also override a task with a different type of task, e.g. below
// we override `sourceRoots` which is a `T.sources` with a `T{}` target:
//

trait Foo extends Module {
  def sourceRoots = T.sources(millSourcePath / "src")
  def sourceContents = T{
    sourceRoots()
      .flatMap(pref => os.walk(pref.path))
      .filter(_.ext == "txt")
      .sorted
      .map(os.read(_))
  }
}

trait Bar extends Foo {
  def additionalSources = T.sources(millSourcePath / "src2")
  def sourceRoots = T { super.sourceRoots() ++ additionalSources() }
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
