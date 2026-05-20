package mill.javalib

import mill.*
import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

object TestCachedSandboxTests extends TestSuite {

  object TestCachedSandbox extends TestRootModule {
    trait MyModule extends JavaModule {
      object test extends JavaTests with TestModule.Junit5
    }
    object foo extends MyModule
    object bar extends MyModule
    object baz extends MyModule
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "test-cached-sandbox"

  def tests: Tests = Tests {
    test("parallelTestCachedSandbox") {
      // Run with multiple threads so testCached tasks from different modules
      // execute in parallel, exercising the os.checker propagation in fork.async
      UnitTester(TestCachedSandbox, resourcePath, threads = Some(10)).scoped { eval =>
        val res = eval(Seq(
          TestCachedSandbox.foo.test.testCached,
          TestCachedSandbox.bar.test.testCached,
          TestCachedSandbox.baz.test.testCached
        ))
        assert(res.isRight)
      }
    }
  }
}
