//// SNIPPET:BUILD
import mill._, javalib._

object build extends RootModule with JavaModule {
  def mainClass = Some("foo.Qux")
}
