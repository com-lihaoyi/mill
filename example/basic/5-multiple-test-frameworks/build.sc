import mill._
import mill.define.ModuleRef
import scalalib._

object foo extends RootModule with ScalaModule {
  def scalaVersion = "2.13.11"
  def ivyDeps = Agg(
    ivy"com.lihaoyi::scalatags:0.12.0",
    ivy"com.lihaoyi::mainargs:0.6.2"
  )

  object test extends ScalaTests {
    def ivyDeps = Agg(
      ivy"com.lihaoyi::utest:0.7.11",
      ivy"org.scalatest::scalatest-freespec:3.2.18"
    )
    def testFramework = "utest.runner.Framework"
  }
  object test2 extends TestModule with TestModule.ScalaTest {
    def testRunModule = ModuleRef(foo.test)
    override def testClasspath = foo.test.testClasspath()
  }
}

/** Usage

> mill resolve __:TestModule.test
...
test.test
test2.test

> mill test
...
+ foo.FooTests.simple ...  <h1>hello</h1>
+ foo.FooTests.escaping ...  <h1>&lt;hello&gt;</h1>
Tests: 2, Passed: 2, Failed: 0
> mill test2
...
FooScalaTests:
Foo
- simple
- escaping
...
Total number of tests run: 2
Suites: completed 1, aborted 0
Tests: succeeded 2, failed 0, canceled 0, ignored 0, pending 0

*/
