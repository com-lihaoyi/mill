import mill._, scalalib._

object root extends RootModule with ScalaModule {
  def scalaVersion = "2.13.8"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::cask:0.9.1",
    ivy"com.lihaoyi::scalatags:0.12.0"
  )

  object test extends ScalaTests {
    def testFramework = "utest.runner.Framework"

    def ivyDeps = Agg(
      ivy"com.lihaoyi::utest::0.8.4",
      ivy"com.lihaoyi::requests::0.6.9",
    )
  }
}

// This example demonstrates how to set up a simple Scala webserver
// implementing the popular Todo-MVC demo application. It includes a test suite
// that spins up the web server locally and makes HTTP requests against it.

/** Usage

> ./mill test
+ webapp.WebAppTests.simpleRequest...

> ./mill runBackground; sleep 2 # give time for server to start

> curl http://localhost:8080
...What needs to be done...
...

> ./mill clean runBackground

*/