import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"
  def moduleDeps = Seq(bar)
  def ivyDeps = Agg(ivy"com.lihaoyi::mainargs:0.4.0")

  def barWorkingDir = T{ T.dest }
  def sources = T{
    bar.run(T.task(Args(barWorkingDir(), super.sources().map(_.path))))()
    Seq(PathRef(barWorkingDir()))
  }
}

object bar extends ScalaModule{
  def scalaVersion = "2.13.8"
  def ivyDeps = Agg(ivy"com.lihaoyi::os-lib:0.9.1")
}

// This example demonstrates using Mill `ScalaModule`s as build tasks: rather
// than defining the task logic in the `build.sc`, we instead put the build
// logic within the `bar` module as `bar/src/Bar.scala`. In this example, we use
// `Bar.scala` as a source-code pre-processor on the `foo` module source code:
// we override `foo.sources`, passing the `super.sources()` to `bar.run` along
// with a `barWorkingDir`, and returning a `PathRef(barWorkingDir())` as the
// new `foo.sources`.

/** Usage

> ./mill foo.run
...
Foo.value: HELLO

*/

// This example does a trivial string-replace of "hello" with "HELLO", but is
// enough to demonstrate how you can use Mill `ScalaModule`s to implement your
// own arbitrarily complex transformations. This is useful for build logic that
// may not fit nicely inside a `build.sc` file, whether due to the sheer lines
// of code or due to dependencies that may conflict with the Mill classpath
// present in `build.sc`