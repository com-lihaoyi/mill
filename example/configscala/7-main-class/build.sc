// == Specifying the Main Class

import mill._, scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.8"
  def mainClass = Some("foo.Qux")
}

// Mill's `foo.run` by default will discover which main class to run from your
// compilation output, but if there is more than one or the main class comes from
// some library you can explicitly specify which one to use. This also adds the
// main class to your `foo.jar` and `foo.assembly` jars.

/** Example Usage

> ./mill run
Hello Qux

*/