import mill._, scalalib._

object foo extends ScalaModule {
  def scalaVersion = "2.13.8"
  object test extends Tests {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.11")
    def testFramework = "utest.runner.Framework"
  }
}

// This build defines a single module with a test suite, configured to use
// "uTest" as the testing framework. Test suites are themselves `ScalaModule`s,
// and have all the normal tasks like `foo.test.compile` available to run, but
// with an additional `.test` task that runs the tests. You can also run the
// test suite directly, in which case it will run the `.test` task as the
// default task for that module

/* Example Usage

> ./mill foo.compile
compiling 1 Scala source

> ./mill foo.test.compile
compiling 1 Scala source

> ./mill foo.test.test
+ foo.FooTests.hello

> ./mill foo.test
+ foo.FooTests.hello

*/