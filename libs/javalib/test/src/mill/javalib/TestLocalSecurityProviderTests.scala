package mill.javalib

import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import mill.util.TokenReaders.*

object TestLocalSecurityProviderTests extends TestSuite {

  object module extends TestRootModule with JavaModule {
    object test extends JavaTests with TestModule.Junit4
    lazy val millDiscover = Discover[this.type]
  }

  val testModuleSourcesPath =
    os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "test-local-security-provider"

  def tests = Tests {
    test("securityProviderCleanup") {
      // Run testLocal twice to verify that security providers registered during
      // the first run are cleaned up and don't leak into the second run.
      // Without the fix, the second run would find a stale provider from the
      // first run's (now-closed) classloader, causing ClassCastExceptions
      // for libraries like BouncyCastle (see https://github.com/com-lihaoyi/mill/issues/6995)
      UnitTester(module, testModuleSourcesPath).scoped { eval =>
        val res1 = eval(module.test.testLocal())
        assert(res1.isRight)
        val res2 = eval(module.test.testLocal())
        assert(res2.isRight)
      }
    }
  }
}
