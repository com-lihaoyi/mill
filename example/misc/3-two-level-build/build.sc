import mill._, scalalib._
import scalatags.Text.all._

object foo extends ScalaModule {
  def scalaVersion = "2.13.2"

  def forkEnv = Map(
    "snippet" -> frag(h1("hello"), p("world"), p(constant.Constant.scalatagsVersion)).render
  )
}

//

/* Example Usage

> ./mill foo.run
<h1>hello</h1><p>world</p><p>0.8.2</p>

> sed -i 's/0.8.2/0.12.0/g' mill-build/build.sc

> ./mill foo.run
<h1>hello</h1><p>world</p><p>0.12.0</p>

*/