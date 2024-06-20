// This plugin brings a incompatible transitive version of Mill
import $ivy.`com.disneystreaming.smithy4s::smithy4s-mill-codegen-plugin::0.18.22`

import mill._
import mill.scalalib._

object root extends RootModule with ScalaModule {
  def scalaVersion = "3.4.2"
}
