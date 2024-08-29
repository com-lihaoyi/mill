import mill._
import mill.scalalib._

object module extends RootModule with ScalaModule {
  def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
}
