// This section puts together what we've learned about ``Task``s and ``Module``s
// so far into a worked example: implementing our own minimal version of
// `mill.scalalib.JavaModule` from first principles.

import mill._

trait DiyJavaModule extends Module{
  def moduleDeps: Seq[DiyJavaModule] = Nil
  def mainClass: T[Option[String]] = None

  def upstream: T[Seq[PathRef]] = T{ T.traverse(moduleDeps)(_.classPath)().flatten }
  def sources = T.source(millSourcePath / "src")

  def compile = T {
    val allSources = os.walk(sources().path)
    val cpFlag = Seq("-cp", upstream().map(_.path).mkString(":"))
    os.proc("javac", cpFlag, allSources, "-d", T.dest).call()
    PathRef(T.dest)
  }

  def classPath = T{ Seq(compile()) ++ upstream() }

  def assembly = T {
    for(cp <- classPath()) os.copy(cp.path, T.dest, mergeFolders = true)

    val mainFlags = mainClass().toSeq.flatMap(Seq("-e", _))
    os.proc("jar", "-c", mainFlags, "-f", T.dest / s"assembly.jar", ".")
      .call(cwd = T.dest)

    PathRef(T.dest / s"assembly.jar")
  }
}
// This defines the following build graph for `DiyJavaModule`. Note that some of the
// edges (dashed) are not connected; that is because `DiyJavaModule` is abstract, and
// needs to be inherited by a concrete `object` before it can be used.

// [graphviz]
// ....
// digraph G {
//   rankdir=LR
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   bgcolor=transparent
//   subgraph cluster_0 {
//     style=dashed
//     node [shape=box width=0 height=0 style=filled fillcolor=white]
//     label = "DiyJavaModule";
//     n0 [label= "", shape=none,height=.0,width=.0]
//     n0 -> "compile" [style=dashed]
//     n0 -> "classPath" [style=dashed]
//     "mainClass" -> "assembly"
//     "sources" -> "compile" -> "classPath" -> "assembly"
//   }
// }
// ....
//
// Some notable things to call out:
//
// * `def moduleDeps` is not a Target. This is necessary because targets cannot
//   change the shape of the task graph during evaluation, whereas `moduleDeps`
//   defines  module dependencies that determine the shape of the graph.
//
// * Using `T.traverse` to recursively gather the upstream classpath. This is
//   necessary to convert the `Seq[T[V]]` into a `T[Seq[V]]` that we can work
//   with inside our targets
//
// * We use the `millSourcePath` together with `T.workspace` to infer a default
//   name for the jar of each module. Users can override it if they want, but
//   having a default is very convenient
//
// * `def cpFlag` is not a task or target, it's just a normal helper method.
//
// Below, the inherit `DiyJavaModule` in three ``object``s: `foo`, `bar`, and `qux`:

object foo extends DiyJavaModule {
  def moduleDeps = Seq(bar)
  def mainClass = Some("foo.Foo")

  object bar extends DiyJavaModule
}

object qux extends DiyJavaModule {
  def moduleDeps = Seq(foo)
  def mainClass = Some("qux.Qux")
}

// This results in the following build graph, with the build graph for `DiyJavaModule`
// duplicated three times - once per module - with the tasks wired up between the modules
// according to our overrides for `moduleDeps`

// [graphviz]
// ....
// digraph G {
//   rankdir=LR
//   node [shape=box width=0 height=0 style=filled fillcolor=white]
//   bgcolor=transparent
//   newrank=true;
//   subgraph cluster_0 {
//     style=dashed
//     node [shape=box width=0 height=0 style=filled fillcolor=white]
//     label = "foo.bar";
//
//     "foo.bar.sources" -> "foo.bar.compile" -> "foo.bar.classPath" -> "foo.bar.assembly"
//     "foo.bar.mainClass" -> "foo.bar.assembly"
//   }
//   subgraph cluster_1 {
//     style=dashed
//     node [shape=box width=0 height=0 style=filled fillcolor=white]
//     label = "foo";
//
//     "foo.bar.classPath" -> "foo.compile"   [constraint=false];
//     "foo.bar.classPath" -> "foo.classPath"
//     "foo.sources" -> "foo.compile" -> "foo.classPath" -> "foo.assembly"
//     "foo.mainClass" -> "foo.assembly"
//   }
//   subgraph cluster_2 {
//     style=dashed
//     node [shape=box width=0 height=0 style=filled fillcolor=white]
//     label = "qux";
//
//     "qux.mainClass" -> "qux.assembly"
//     "foo.classPath" -> "qux.compile" [constraint=false];
//     "foo.classPath" -> "qux.classPath"
//     "qux.sources" -> "qux.compile" -> "qux.classPath" -> "qux.assembly"
//   }
// }
// ....
//
// This simple set of `DiyJavaModule` can be used as follows:

/** Usage

> ./mill showNamed __.sources
{
  "foo.sources": ".../foo/src",
  "foo.bar.sources": ".../foo/bar/src",
  "qux.sources": ".../qux/src"
}

> ./mill show qux.assembly
".../out/qux/assembly.dest/assembly.jar"

> java -jar out/qux/assembly.dest/assembly.jar
Foo.value: 31337
Bar.value: 271828
Qux.value: 9000

> ./mill show foo.assembly
".../out/foo/assembly.dest/assembly.jar"

> java -jar out/foo/assembly.dest/assembly.jar
Foo.value: 31337
Bar.value: 271828

*/

// Like any other ``Target``s, the compilation and packaging of the Java code
// is incremental: if you change a file in `foo/src/` and run `qux.assembly`,
// `foo.compile` and `qux.compile` will be re-computed, but `bar.compile` will
// not as it does not transitively depend on `foo.sources`. We did not need to
// build support for this caching and invalidation ourselves, as it is
// automatically done by Mill based on the structure of the build graph.
//
// Note that this is a minimal example is meant for educational purposes: the
// `mill.scalalib.JavaModule` and `ScalaModule` that Mill provides is more
// complicated to provide additional flexibility and performance. Nevertheless,
// this example should give you a good idea of how Mill ``module``s can be
// developed, so you can define your own custom modules when the need arises.

