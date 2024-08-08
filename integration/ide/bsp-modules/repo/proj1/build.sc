import mill._
import mill.scalalib._

object proj1 extends ScalaModule {
  def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
}
