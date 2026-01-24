package mill.javalib

import mill.api.Discover
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import mill.util.TokenReaders.*
import utest.*

object TestClassLoaderTests extends TestSuite {

  object testclassloader extends TestRootModule with JavaModule {

    object test extends JavaTests with TestModule.Junit4

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "classloader-test"

  override def tests: Tests = Tests {
    test("com.sun classes exist in tests classpath (Java 8 only)") - UnitTester(
      testclassloader,
      resourcePath
    ).scoped { eval =>
      assert(eval.apply(testclassloader.test.testForked()).isRight)
    }
  }
}
