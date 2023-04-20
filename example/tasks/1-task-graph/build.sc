// The following is a simple self-contained example using Mill to compile Java:

import mill._

def sources = T.source { T.workspace / "src" }
def resources = T.source { T.workspace / "resources" }

def allSources = T { os.walk(sources().path).map(PathRef(_)) }

def classFiles = T {
  os.proc("javac", allSources().map(_.path.toString()), "-d", T.dest).call(cwd = T.dest)
  PathRef(T.dest)
}

def jar = T {
  os.copy(classFiles().path, T.dest, mergeFolders = true)
  os.copy(resources().path, T.dest, mergeFolders = true)

  os.proc("jar", "-cfe", T.dest / "foo.jar", "foo.Foo", ".").call(cwd = T.dest)

  PathRef(T.dest / "foo.jar")
}

def run(args: String*) = T.command {
  os.proc(
      "java",
      "-cp", s"${classFiles().path}:${resources().path}",
      "foo.Foo",
      args
    )
    .call(stdout = os.Inherit)
}

// This example does not use any of Mill's builtin support for building Java or
// Scala projects, and instead builds a pipeline "from scratch" using Mill
// tasks and `javac`/`jar`/`java` subprocesses. We define `T.source` folders,
// `T{...}` targets that depend on them, and a `T.command`.

/** Usage

> ./mill run hello

> ./mill show jar
".../out/jar.dest/foo.jar"

> java -jar out/jar.dest/foo.jar i am cow
Foo.value: 31337
args: i am cow
foo.txt resource: My Example Text

> unzip -p out/jar.dest/foo.jar foo.txt
My Example Text

*/

// When you first evaluate `jar` (e.g. via `mill jar` at the command line), it
// will evaluate all the defined targets: `sourceRoot`, `allSources`,
// `classFiles`, `resourceRoot` and `jar`.
//
// Subsequent invocations of `mill jar` will evaluate only as much as is
// necessary, depending on what input sources changed:
//
// * If the files in `sourceRoot` change, it will re-evaluate `allSources`,
//   compiling to `classFiles`, and building the `jar`
//
// * If the files in `resourceRoot` change, it will only re-evaluate `jar` and
//   use the cached output of `allSources` and `classFiles`