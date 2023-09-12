// There are three primary kinds of _Tasks_ that you should care about:
//
// * <<_targets>>, defined using `T {...}`
// * <<_sources>>, defined using `T.sources {...}`
// * <<_commands>>, defined using `T.command {...}`
//
// === Targets

import mill._

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

// ``Target``s are defined using the `def foo = T {...}` syntax, and dependencies
// on other targets are defined using `foo()` to extract the value from them.
// Apart from the `foo()` calls, the `T {...}` block contains arbitrary code that
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

def summarizeClassFileStats = T{
  val files = os.walk(classFiles().path)
  ClassFileData(
    totalFileSize = files.map(os.size(_)).sum,
    largestFile = files.maxBy(os.size(_)).last
  )
}

/** Usage

> ./mill show summarizeClassFileStats
{
  "totalFileSize": ...,
  "largestFile": "..."
}

*/

// === Sources

def sources = T.source { millSourcePath / "src" }
def resources = T.source { millSourcePath / "resources" }

// ``Source``s are defined using `T.sources {...}`, taking one-or-more
// ``os.Path``s as arguments. A `Source` is a subclass of `Target[Seq[PathRef]]`:
// this means that its build signature/`hashCode` depends not just on the path
// it refers to (e.g. `foo/bar/baz`) but also the MD5 hash of the filesystem
// tree under that path.
//
// `T.source` and `T.sources` are the most common inputs to your Mill build:
// they watch source files and folders and cause downstream targets to
// re-compute if a change is detected.
//
// Note that even though a source file changed, that does not necessarily cause
// all transitive downstream targets to re-compute:

/** Usage

> ./mill jar # Cached from earlier

> printf "\n" >> src/Foo.java # Add a newline to the end of Foo.java

> ./mill jar # Classfiles recompiled but output unchanged, jar was not rebuilt
Generating classfiles

*/

// `T.sources` can be overriden with `super`, to let you
// override-and-extend source lists the same way you would any other target
// definition:
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

/** Usage

> ./mill show bar.sourceContents # includes both source folders
[
  "File Data From src/",
  "File Data From src2/"
]

*/

// === Commands

def run(args: String*) = T.command {
  os.proc(
      "java",
      "-cp", s"${classFiles().path}:${resources().path}",
      "foo.Foo",
      args
    )
    .call(stdout = os.Inherit)
}

// Defined using `T.command {...}` syntax, ``Command``s can run arbitrary code, with
// dependencies declared using the same `foo()` syntax (e.g. `classFiles()` above).
// Commands can be parametrized, but their output is not cached, so they will
// re-evaluate every time even if none of their inputs have changed.
// A command with no parameter is defined as `def myCommand() = T.command {...}`.
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