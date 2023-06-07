import $file.`foo-bar`, `foo-bar`.FooBar

import mill._

object `foo-bar-module` extends FooBar {
  def scalaVersion = "3.2.2"
}
