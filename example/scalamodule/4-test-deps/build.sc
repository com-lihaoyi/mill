// [IMPORTANT]
// --
// _Mill has no `test`-scoped dependencies!_
// --
//
// You might be used to test-scoped dependencies from other build tools like
// Maven, Gradle or sbt. As test modules in Mill are just regular modules,
// there is no special need for a dedicated test-scope. You can use `ivyDeps`
// and `runIvyDeps` to declare dependencies in test modules, and test modules
// can use their `moduleDeps` to also depend on each other

import mill._, scalalib._

object qux extends ScalaModule {
  def scalaVersion = "2.13.8"
  def moduleDeps = Seq(baz)

  object test extends ScalaModuleTests {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.11")
    def testFramework = "utest.runner.Framework"
    def moduleDeps = super.moduleDeps ++ Seq(baz.test)
  }
}


object baz extends ScalaModule {
  def scalaVersion = "2.13.8"

  object test extends ScalaModuleTests {
    def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.7.11")
    def testFramework = "utest.runner.Framework"
  }
}

// In this example, not only does `qux` depend on `baz`, but we also make
// `qux.test` depend on `baz.test`. That lets `qux.test` make use of the
// `BazTestUtils` class that `baz.test` defines, allowing us to re-use this
// test helper throughout multiple modules' test suites

/** Usage

> ./mill qux.test
-------------------------------- Running Tests --------------------------------
Using BazTestUtils.bazAssertEquals
+ qux.QuxTests.simple ...

> ./mill baz.test
-------------------------------- Running Tests --------------------------------
Using BazTestUtils.bazAssertEquals
+ baz.BazTests.simple ...

*/
