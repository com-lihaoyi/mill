package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object CompileErrorTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      val res = tester.eval("foo.scalaVersion")

      assert(!res.isSuccess)

      locally {
        assert(res.err.contains("""bar.mill:15:9"""))
        assert(res.err.contains("""println(doesntExist)"""))
        assert(res.err.contains("""Not found: doesntExist"""))
      }

      locally {
        assert(res.err.contains("""qux.mill:4:34"""))
        assert(res.err.contains("""myMsg.substring("0")"""))
        assert(res.err.contains("""Found:    ("0" : String)"""))
        assert(res.err.contains("""Required: Int"""))
      }

      locally {
        assert(res.err.contains("""build.mill:11:5"""))
        assert(res.err.contains("""foo.noSuchMethod"""))
        assert(res.err.contains("""value noSuchMethod is not a member"""))
      }
    }
  }
}
