package mill.entrypoint

class MillBootstrapModule(enclosingClasspath: Seq[os.Path], millSourcePath0: os.Path)
  extends mill.define.BaseModule(os.pwd)(implicitly, implicitly, implicitly, implicitly, mill.define.Caller(()))
    with mill.scalalib.ScalaModule {
  implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]

  def scalaVersion = "2.13.10"

  def generatedSources = mill.define.Target.input {
    val top =
      s"""
         |package millbuild
         |import _root_.mill._
         |object MillBuildModule
         |extends _root_.mill.define.BaseModule(os.Path("${os.pwd}"))(
         |  implicitly, implicitly, implicitly, implicitly, mill.define.Caller(())
         |)
         |with MillBuildModule{
         |  // Stub to make sure Ammonite has something to call after it evaluates a script,
         |  // even if it does nothing...
         |  def $$main() = Iterator[String]()
         |
         |  // Need to wrap the returned Module in Some(...) to make sure it
         |  // doesn't get picked up during reflective child-module discovery
         |  def millSelf = Some(this)
         |
         |  @_root_.scala.annotation.nowarn("cat=deprecation")
         |  implicit lazy val millDiscover: _root_.mill.define.Discover[this.type] = _root_.mill.define.Discover[this.type]
         |}
         |
         |sealed trait MillBuildModule extends _root_.mill.main.MainModule{
         |""".stripMargin
    val bottom = "\n}"


    os.write(
      mill.define.Target.dest / "Build.scala",
      top + os.read(os.pwd / "build.sc") + bottom
    )
    Seq(mill.api.PathRef(mill.define.Target.dest / "Build.scala"))
  }

  def unmanagedClasspath = mill.define.Target.input {
    mill.api.Loose.Agg.from(enclosingClasspath.map(p => mill.api.PathRef(p)))
  }

  def millSourcePath = millSourcePath0
}
