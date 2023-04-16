import mill._, scalalib._, scalajslib._

trait AppScalaModule extends ScalaModule{
  def scalaVersion = "2.13.8"
}

trait AppScalaJSModule extends AppScalaModule with ScalaJSModule {
  def scalaJSVersion = "1.13.0"
}

object app extends RootModule with AppScalaModule{

  def moduleDeps = Seq(shared.jvm)

  def ivyDeps = Agg(ivy"com.lihaoyi::cask:0.9.1")

  def resources = T{
    os.makeDir(T.dest / "webapp")
    val jsPath = client.fastLinkJS().dest.path
    os.copy(jsPath / "main.js", T.dest / "webapp" / "main.js")
    os.copy(jsPath / "main.js.map", T.dest / "webapp" / "main.js.map")
    super.resources() ++ Seq(PathRef(T.dest))
  }

  object test extends Tests{
    def testFramework = "utest.runner.Framework"

    def ivyDeps = Agg(
      ivy"com.lihaoyi::utest:0.7.10",
      ivy"com.lihaoyi::requests:0.6.9",
    )
  }

  object shared extends Module{
    trait SharedModule extends AppScalaModule with PlatformScalaModule {

      def ivyDeps = Agg(
        ivy"com.lihaoyi::scalatags:0.12.0",
        ivy"com.lihaoyi::upickle:3.0.0",
      )
    }

    object jvm extends SharedModule
    object js extends SharedModule with AppScalaJSModule
  }

  object client extends AppScalaJSModule {
    def moduleDeps = Seq(shared.js)
    def ivyDeps = Agg(ivy"org.scala-js::scalajs-dom:2.2.0")
  }
}

// A Scala-JVM backend server wired up with a Scala.js front-end, with a
// `shared` module containing code that is used in both client and server.
// Rather than the server sending HTML for the initial page load and HTML for
// page updates, it sends HTML for the initial load and JSON for page updates
// which is then rendered into HTML on the client.
//
// The JSON serialization logic and HTML generation logic in the `shared` module
// is shared between client and server, and uses libraries like uPickle and
// Scalatags which work on both ScalaJVM and Scala.js. This allows us to freely
// move code between the client and server, without worrying about what
// platform or language the code was originally implemented in.
//
// This is a minimal example of shared code compiled to ScalaJVM and Scala.js,
// running on both client and server, meant for illustrating the build
// configuration. A full exploration of client-server code sharing techniques
// is beyond the scope of this example.

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