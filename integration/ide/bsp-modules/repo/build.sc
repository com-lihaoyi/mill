import mill._
import mill.api.{PathRef}
import mill.scalalib._

trait HelloBspModule extends ScalaModule {
  def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  object test extends ScalaTests with TestModule.Utest
}

object HelloBsp extends HelloBspModule {
  // Explicitly depends on proj1
  def moduleDeps: Seq[JavaModule] = Seq(build.proj1.module)
  // Explicitly depends on proj2
  def compileModuleDeps: Seq[JavaModule] = Seq(build.proj2.module)
  // Implicitly depends on proj3 via a target
  override def unmanagedClasspath: T[Agg[PathRef]] = T {
    Agg(build.proj3.module.jar())
  }
}

def validate() = T.command {
  val transitiveModules = mill.scalalib.internal.JavaModuleUtils.transitiveModules(build.`package`)
  val file = T.dest / "transitive-modules.json"
  val moduleNames = transitiveModules.map(m =>
    mill.scalalib.internal.ModuleUtils.moduleDisplayName(m)
  ).mkString("\n")
  val content =
    s"""${moduleNames}
       |""".stripMargin
  os.write(file, content)
  PathRef(file)
}
