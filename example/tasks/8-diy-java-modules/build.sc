// This section puts together what we've learned about ``Task``s and ``Module``s
// so far into a worked example: implementing our own minimal version of
// `mill.scalalib.JavaModule` from first principles.

import mill._

trait DiyJavaModule extends Module{
  def moduleDeps: Seq[DiyJavaModule] = Nil
  def mainClass: T[Option[String]] = None

  def upstreamClasses: T[Seq[PathRef]] = T{
    T.traverse(moduleDeps)(_.classPath)().flatten
  }

  def sources = T.source(millSourcePath / "src")

  def name = T{ millSourcePath.relativeTo(T.workspace).segments.mkString("-") }

  def cpFlag(cp: Seq[PathRef]) = Seq("-cp", cp.map(_.path).mkString(":"))

  def compile = T {
    val allSources = os.walk(sources().path)
    os.proc("javac", cpFlag(upstreamClasses()), allSources, "-d", T.dest)
      .call(stdout = os.Inherit, cwd = T.dest)

    PathRef(T.dest)
  }

  def classPath = T{ Seq(compile()) ++ upstreamClasses() }

  def assembly = T {
    for(cp <- classPath()) os.copy(cp.path, T.dest, mergeFolders = true)

    val mainFlags = mainClass().toSeq.flatMap(Seq("-e", _))
    os.proc("jar", "-c", mainFlags, "-f", T.dest / s"${name()}.jar", ".")
      .call(stdout = os.Inherit, cwd = T.dest)

    PathRef(T.dest / s"${name()}.jar")
  }
}

object foo extends DiyJavaModule {
  def moduleDeps = Seq(bar)
  def mainClass = Some("foo.Foo")

  object bar extends DiyJavaModule
}

object qux extends DiyJavaModule {
  def moduleDeps = Seq(foo)
  def mainClass = Some("qux.Qux")
}

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
// This simple set of `DiyJavaModule` can be used as follows:

/** Usage

> ./mill showNamed __.sources
{
  "foo.sources": ".../foo/src",
  "foo.bar.sources": ".../foo/bar/src",
  "qux.sources": ".../qux/src"
}

> ./mill showNamed __.name
{
  "foo.name": "foo",
  "foo.bar.name": "foo-bar",
  "qux.name": "qux"
}

> ./mill show qux.assembly
".../out/qux/assembly.dest/qux.jar"

> java -jar out/qux/assembly.dest/qux.jar
Foo.value: 31337
Bar.value: 271828
Qux.value: 9000

> ./mill show foo.assembly
".../out/qux/assembly.dest/qux.jar"

> java -jar out/foo/assembly.dest/foo.jar
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

