//// SNIPPET:BUILD1
import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"
  object test extends ScalaTests {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.8.4")
    def testFramework = "utest.runner.Framework"
  }
}

// This build defines a single module with a test suite, configured to use
// "uTest" as the testing framework. Test suites are themselves ``ScalaModule``s,
// nested within the enclosing module,
//// SNIPPET:END
// and have all the normal tasks like
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

//// SNIPPET:BUILD2
object bar extends ScalaModule {
  def scalaVersion = "2.13.8"

  object test extends ScalaTests with TestModule.Utest {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.8.4")
  }
}
//// SNIPPET:END
/** Usage

> mill bar.test
...bar.BarTests.hello ...
...bar.BarTests.world ...

*/

// You can also select multiple test suites in one command using Mill's
// xref:Target_Query_Syntax.adoc[Target Query Syntax]

/** Usage

> mill __.test
...bar.BarTests.hello ...
...bar.BarTests.world ...
...foo.FooTests.hello ...
...foo.FooTests.world ...
...

*/

// Mill provides three ways of running tests
//
// * `foo.test.test`: runs tests in a subprocess in an empty `sandbox/` folder.
//
// * `foo.test.testCached`: runs the tests in an empty `sandbox/` folder and caches the results
//   if successful. Also allows multiple test modules to be run in parallel e.g. via `mill __.testCached`
//
// * `foo.test.testLocal`: runs tests in an isolated classloader as part of the main Mill process.
//   This can be faster than `.test`, but is less flexible (e.g. you cannot pass `forkEnv`)
//   and more prone to interference (due to sharing the `sandbox/` folder provided by the
//   Mill process)

/** Usage

> mill bar.test.test

> mill bar.test.testCached

> mill bar.test.testLocal

*/

// Mill provides three ways of running tests
//
// * `foo.test.test`: runs tests in a subprocess in an empty `sandbox/` folder, and
//   `forkArg` and `forkEnv` can be overridden to pass JVM flags &amp; environment variables.
//
// * `foo.test.testCached`: runs the tests in an empty `sandbox/` folder and caches the results
//   if successful. Also allows multiple test modules to be run in parallel e.g. via
//   `mill __.testCached`. Again, `forkEnv` and `forkArgs` can be configured.
//
// * `foo.test.testLocal`: runs tests in an isolated classloader as part of the main Mill process.
//   This can be faster than `.test`, but is less flexible (e.g. you cannot pass `forkEnv`)
//   and more prone to interference (due to sharing the `sandbox/` folder provided by the
//   Mill process)
//
//
// [source,bash,subs="attributes,verbatim"]
// ----
// > mill bar.test.test
//
// > mill bar.test.testCached
//
// > mill bar.test.testLocal
// ----
//
// *Note that Mill runs tests with the working directory set to an empty `sandbox/` folder by default*.
// Tests can access files from their resource directory via the environment variable
// `MILL_TEST_RESOURCE_FOLDER` which provides the path to the resource folder, and additional
// paths can be provided to test via `forkEnv`. See
// xref:Java_Module_Config.adoc#_classpath_and_filesystem_resources[Classpath and Filesystem Resources]
// for more details.
//
// If you want to pass any arguments to the test framework, simply put them after
// `foo.test` in the command line. e.g. {utest-github-url}[uTest]
// lets you pass in a selector to decide which test to run, which in Mill would be:


/** Usage

> mill bar.test bar.BarTests.hello
...bar.BarTests.hello ...

*/
