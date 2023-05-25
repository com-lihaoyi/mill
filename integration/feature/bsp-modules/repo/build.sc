import mill._
import mill.api.{PathRef}
import mill.scalalib._
import $file.proj1.{build => proj1}
import $file.proj2.{build => proj2}
import $file.proj3.{build => proj3}

trait HelloBspModule extends ScalaModule {
  def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  object test extends super.Tests with TestModule.Utest
}

object HelloBsp extends HelloBspModule {
  // Explicitly depends on proj1
  def moduleDeps: Seq[JavaModule] = Seq(proj1.proj1)
  // Explicitly depends on proj2
  def compileModuleDeps: Seq[JavaModule] = Seq(proj2.proj2)
  // Implicitly depends on proj3 via a target
  override def unmanagedClasspath: T[Agg[PathRef]] = T {
    Agg(proj3.proj3.jar())
  }
}

def validate() = T.command {
  val transitiveModules = mill.scalalib.internal.JavaModuleUtils.transitiveModules(build)
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
