import mill._, scalalib._

trait MyModule extends ScalaModule {
  def scalaVersion = "2.13.11"
}

// Example Docs


/** Usage

> ./mill resolve __

> ./mill bar.build.compile

> ./mill foo.compile

> ./mill foo.run --foo-text hello --bar-text world
Foo.value: hello
Bar.value: <p>world</p>
*/
