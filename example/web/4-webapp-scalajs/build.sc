import mill._, scalalib._, scalajslib._

object app extends BuildModule with ScalaModule{

  def scalaVersion = "2.13.10"
  def ivyDeps = Agg[Dep](
    ivy"com.lihaoyi::cask:0.9.0",
    ivy"com.lihaoyi::scalatags:0.12.0"
  )

  def resources = T.sources{
    os.makeDir(T.dest / "webapp")
    val jsPath = client.fastLinkJS().dest.path
    os.copy(jsPath / "main.js", T.dest / "webapp" / "main.js")
    os.copy(jsPath / "main.js.map", T.dest / "webapp" / "main.js.map")
    super.resources() ++ Seq(PathRef(T.dest))
  }

  object test extends Tests{
    def testFramework = "utest.runner.Framework"

    def ivyDeps = Agg(
      ivy"com.lihaoyi::utest::0.7.10",
      ivy"com.lihaoyi::requests::0.6.9",
    )
  }

  object client extends ScalaJSModule {
    def scalaVersion = "2.13.10"
    def scalaJSVersion = "1.13.0"
    def ivyDeps = Agg(ivy"org.scala-js::scalajs-dom::2.2.0")
  }
}

// A minimal example of a Scala-JVM backend server wired up with a Scala.js
// front-end. The backend code is identical to the [2-todo-webapp] example, but
// we replace the `main.js` client side code with the Javascript output of
// `ClientApp.scala`.
//
// Note that the client-side Scala code is the simplest 1-to-1 translation of
// the original Javascript, using `scalajs-dom`, as this example is intended to
// demonstrate the `build.sc` config in Mill. A real codebase is likely to use
// Javascript or Scala UI frameworks to manage the UI, but those are beyond the
// scope of this example.

/* Example Usage

> ./mill test
+ webapp.WebAppTests.simpleRequest

> ./mill runBackground

> curl http://localhost:8080
What needs to be done

> curl http://localhost:8080/static/main.js
Scala.js

> ./mill clean runBackground

*/