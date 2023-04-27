import mill._, scalalib._

trait MyModule extends ScalaModule{
  def scalaVersion = "2.13.8"
}

object foo extends MyModule {
  def moduleDeps = Seq(bar)
  def ivyDeps = Agg(ivy"com.lihaoyi::mainargs:0.4.0")
  def sources = T{
    val dest = bar.run(
      T.task{
        mainargs.Leftover(super.sources().map(_.path).mkString(","), T.dest.toString())
      }
    ).mapCtx((x, y) => y.dest)()

    Seq(PathRef(dest))
  }
}

object bar extends MyModule{
  def ivyDeps = Agg(ivy"com.lihaoyi::os-lib:0.9.1")
}

// explanation

/** Usage

> ./mill show foo.sources
sdad

> ./mill foo.run
asdas


*/
