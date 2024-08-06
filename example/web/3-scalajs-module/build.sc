import mill._, scalalib._, scalajslib._

object foo extends ScalaJSModule {
  def scalaVersion = "2.13.8"
  def scalaJSVersion = "1.13.0"
  def ivyDeps = Agg(ivy"com.lihaoyi::scalatags::0.12.0")
  object test extends ScalaJSTests {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest::0.8.4")
    def testFramework = "utest.runner.Framework"
  }
}

// This build defines a single `ScalaJSModule` with a test suite.
// `ScalaJSModule` is similar to `ScalaModule`, except it requires a
// `scalaJSVersion` to be provided.
//
// By default, Scala.js code gets access to the `scala.scalajs.js` package,
// which contains the core JS APIs like `js.JSON`, `js.Date`, etc. `ivyDeps` of
// Scala.js-compatible dependencies can be given, which need two colons (`::`)
// on the right to indicate it's a Scala.js dependency. These can be both Scala
// libraries compiled to JS, or facades for Javascript libraries. If running in
// the browser, you can use the https://github.com/scala-js/scala-js-dom facade
// to access the browser DOM APIs.
//
// Normal tasks like `compile`, `run`, or `test` work as expected, with `run`
// and `test` calling `node` to run in a Javascript environment rather than in
// the JVM. There is also additional `fastLinkJS` and `fullLinkJS` commands
// that compile the module into a single Javascript file, which you can then
// distribute or deploy with your web application

/** Usage

> ./mill foo.run
<h1>Hello World</h1>
stringifiedJsObject: ["hello","world","!"]

> ./mill foo.test
+ foo.FooTests.hello...

> ./mill show foo.fullLinkJS # mac/linux
{
...
..."jsFileName": "main.js",
  "dest": ".../out/foo/fullLinkJS.dest"
}

> node out/foo/fullLinkJS.dest/main.js # mac/linux
<h1>Hello World</h1>
stringifiedJsObject: ["hello","world","!"]

*/

// Note that running Scala.js modules locally requires the `node` Javascript
// runtime to be installed on your machine.