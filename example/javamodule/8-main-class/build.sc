// SNIPPET:BUILD
import mill._, javalib._

object foo extends RootModule with JavaModule {
  def mainClass = Some("foo.Qux")
}
