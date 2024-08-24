// The following is a simple self-contained example using Mill to compile Java:

import mill._

def mainClass: T[Option[String]] = Some("foo.Foo")

def sources = T.source(millSourcePath / "src")
def resources = T.source(millSourcePath / "resources")

def compile = T {
  val allSources = os.walk(sources().path)
  os.proc("javac", allSources, "-d", T.dest).call()
  PathRef(T.dest)
}

def assembly = T {
  for(p <- Seq(compile(), resources())) os.copy(p.path, T.dest, mergeFolders = true)

  val mainFlags = mainClass().toSeq.flatMap(Seq("-e", _))
  os.proc("jar", "-c", mainFlags, "-f", T.dest / s"assembly.jar", ".")
    .call(cwd = T.dest)

  PathRef(T.dest / s"assembly.jar")
}

// This code defines the following task graph, with the boxes being the tasks
// and the arrows representing the _data-flow_ between them:
//
// [graphviz]
// ....
// digraph G {
//   rankdir=LR
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   sources -> compile -> assembly
//   resources -> assembly
//   mainClass -> assembly
// }
// ....
//
// This example does not use any of Mill's builtin support for building Java or
// Scala projects, and instead builds a pipeline "from scratch" using Mill
// tasks and `javac`/`jar`/`java` subprocesses. We define `T.source` folders,
// plain `T{...}` targets that depend on them, and a `T.command`.

/** Usage

> ./mill show assembly
".../out/assembly.dest/assembly.jar"

> java -jar out/assembly.dest/assembly.jar i am cow
Foo.value: 31337
args: i am cow

> unzip -p out/assembly.dest/assembly.jar foo.txt
My Example Text

*/

// When you first evaluate `assembly` (e.g. via `mill assembly` at the command
// line), it will evaluate all the defined targets: `mainClass`, `sources`,
// `compile`, and `assembly`.
//
// Subsequent invocations of `mill assembly` will evaluate only as much as is
// necessary, depending on what input sources changed:
//
// * If the files in `sources` change, it will re-evaluate
//  `compile`, and `assembly` (red)
//
// [graphviz]
// ....
// digraph G {
//   rankdir=LR
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   sources -> compile -> assembly
//   resources -> assembly
//   mainClass -> assembly
//   assembly [fillcolor=lightpink]
//   sources [fillcolor=lightpink]
//   compile [fillcolor=lightpink]
//   resources [fillcolor=lightgreen]
//   mainClass [fillcolor=lightgreen]
// }
// ....
//
// * If the files in `resources` change, it will only re-evaluate `assembly` (red)
//   and use the cached output of `compile` (green)
//
// [graphviz]
// ....
// digraph G {
//   rankdir=LR
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   sources -> compile -> assembly
//   resources -> assembly
//   mainClass -> assembly
//   assembly [fillcolor=lightpink]
//   resources [fillcolor=lightpink]
//   compile [fillcolor=lightgreen]
//   sources [fillcolor=lightgreen]
//   mainClass [fillcolor=lightgreen]
// }
// ....
//