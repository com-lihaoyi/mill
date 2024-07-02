import mill._, scalalib._

trait MyModule extends ScalaModule {
  def scalaVersion = "2.13.11"
}

// Example Docs


/** Usage

> ./mill resolve __

> ./mill bar.qux.module.compile

> ./mill foo.compile

> ./mill foo.run --foo-text hello --bar-qux-text world
Foo.value: hello
BarQux.value: <p>world</p>
*/
