import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"
  def moduleDeps = Seq(bar)
  def ivyDeps = Agg(ivy"com.lihaoyi::mainargs:0.4.0")
  def barWorkingDir = T{ T.dest }
  def barArgs = T.task{
    Args(super.sources().map(_.path).mkString(","), barWorkingDir().toString())
  }

  def sources = T{
    val dest = bar.run(barArgs)()
    Seq(PathRef(barWorkingDir()))
  }
}

object bar extends ScalaModule{
  def scalaVersion = "2.13.8"
  def ivyDeps = Agg(ivy"com.lihaoyi::os-lib:0.9.1")
}

// explanation

/** Usage

> ./mill foo.run
...
Foo.value: HELLO

*/
