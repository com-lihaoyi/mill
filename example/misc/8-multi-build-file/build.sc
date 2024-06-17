import mill._, scalalib._

trait MyModule extends ScalaModule {
  def scalaVersion = "2.13.11"
}

// Example Docs


/** Usage

> ./mill resolve __

> ./mill foo.foo.compile
*/
