import mill._
import mill.scalalib._

trait Base extends ScalaModule {
  def scalaVersion = "3.5.0-RC1"
  def usePipeling = true
}

object mod1 extends Base
object mod2 extends Base {
  def moduleDeps = Seq(mod1)
}
