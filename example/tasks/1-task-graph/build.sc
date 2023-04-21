// The following is a simple self-contained example using Mill to compile Java:

import mill._

def sources = T.source(millSourcePath / "src")
def resources = T.source(millSourcePath / "resources")

def compile = T {
  os.proc("javac", os.walk(sources().path), "-d", T.dest).call(cwd = T.dest)
  PathRef(T.dest)
}

def classPath = T{ Seq(compile(), resources()) }

def jar = T {
  for(cp <- classPath()) os.copy(cp.path, T.dest, mergeFolders = true)
  os.proc("jar", "-cfe", T.dest / "foo.jar", "foo.Foo", ".").call(cwd = T.dest)
  PathRef(T.dest / "foo.jar")
}

def run(args: String*) = T.command {
  val classPathStr = classPath().map(_.path).mkString(":")
  os.proc("java", "-cp", classPathStr, "foo.Foo", args)
    .call(stdout = os.Inherit)
}

// This example does not use any of Mill's builtin support for building Java or
// Scala projects, and instead builds a pipeline "from scratch" using Mill
// tasks and `javac`/`jar`/`java` subprocesses. We define `T.source` folders,
// plain `T{...}` targets that depend on them, and a `T.command`.

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
// will evaluate all the defined targets: `sources`, `allSources`,
// `compile`, `resources` and `jar`.
//
// Subsequent invocations of `mill jar` will evaluate only as much as is
// necessary, depending on what input sources changed:
//
// * If the files in `sources` change, it will re-evaluate `allSources`,
//   compiling to `compile`, and building the `jar`
//
// * If the files in `resources` change, it will only re-evaluate `jar` and
//   use the cached output of `allSources` and `compile`