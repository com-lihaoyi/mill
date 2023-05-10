import mill._, scalalib._
import $ivy.`com.lihaoyi::mill-contrib-buildinfo:`
import mill.contrib.buildinfo.BuildInfo

object foo extends ScalaModule with BuildInfo {
  def scalaVersion = "2.13.10"
  def buildInfoPackageName = "foo"
  def buildInfoMembers = Seq(
    BuildInfo.Value("scalaVersion", scalaVersion()),
  )
}


// This example illustrates usage of Mill `contrib` plugins. These are Mill
// plugins contributed by Mill user that are maintained within the Mill
// repo, published under `mill-contrib-*`.

/** Usage

> ./mill foo.run
...
foo.BuildInfo.scalaVersion: 2.13.10

*/

