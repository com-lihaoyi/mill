import mill._
import mill.api.PathRef
import mill.scalalib._

trait HelloBspModule extends ScalaModule {
  def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
  object test extends ScalaModuleTests with TestModule.Utest

  override def generatedSources = T {
    Seq(PathRef(T.ctx().dest / "classes"))
  }
}

object HelloBsp extends HelloBspModule
