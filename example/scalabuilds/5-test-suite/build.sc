// SNIPPET:BUILD1
import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"
  object test extends ScalaTests {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.11")
    def testFramework = "utest.runner.Framework"
  }
}
// SNIPPET:END
// This build defines a single module with a test suite, configured to use
// "uTest" as the testing framework. Test suites are themselves ``ScalaModule``s,
// nested within the enclosing module, and have all the normal tasks like
// `foo.test.compile` available to run, but with an additional `.test` task
// that runs the tests. You can also run the test suite directly, in which case
// it will run the `.test` task as the default task for that module.

/** Usage

> mill foo.compile
compiling 1 ... source...

> mill foo.test.compile
compiling 1 ... source...

> mill foo.test.test
...foo.FooTests.hello ...
...foo.FooTests.world ...

> mill foo.test
...foo.FooTests.hello ...
...foo.FooTests.world ...

*/

// For convenience, you can also use one of the predefined test frameworks:
//
// * `TestModule.Junit4`
// * `TestModule.Junit5`
// * `TestModule.TestNg`
// * `TestModule.Munit`
// * `TestModule.ScalaTest`
// * `TestModule.Specs2`
// * `TestModule.Utest`
// * `TestModule.ZioTest`

// SNIPPET:BUILD2
object bar extends ScalaModule {
  def scalaVersion = "2.13.8"

  object test extends ScalaTests with TestModule.Utest {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.11")
  }
}
// SNIPPET:END
/** Usage

> mill bar.test
...bar.BarTests.hello ...
...bar.BarTests.world ...

*/

// By default, tests are run in a subprocess, and `forkArg` and `forkEnv` can be
// overridden to pass JVM flags &amp; environment variables. You can also use
//
// [source,bash]
// ----
// mill foo.test.testLocal
// ----
//
// To run tests in-process in an isolated classloader.
//
// If you want to pass any arguments to the test framework, simply put them after
// `foo.test` in the command line. e.g. {utest-github-url}[uTest]
// lets you pass in a selector to decide which test to run, which in Mill would be:


/** Usage

> mill bar.test bar.BarTests.hello
...bar.BarTests.hello ...

*/

// You can also define multiple test suites if you want, e.g.:
// SNIPPET:BUILD3
object qux extends ScalaModule {
  def scalaVersion = "2.13.8"

  object test extends ScalaTests with TestModule.Utest {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.11")
  }
  object integration extends ScalaTests with TestModule.Utest {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.11")
  }
}
// SNIPPET:END
// Each of which will expect their sources to be in their respective `foo/test` and
// `foo/integration` folder.

/** Usage

> mill qux.test
...qux.QuxTests.hello ...
...qux.QuxTests.world ...

> mill qux.integration
...qux.QuxIntegrationTests.helloworld ...

> mill qux.{test,integration}
...qux.QuxTests.hello ...
...qux.QuxTests.world ...
...qux.QuxIntegrationTests.helloworld ...

*/