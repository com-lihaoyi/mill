package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import utest.*

object DoNotDiscoverTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("doNotDiscoverSkippedByDefault") - integrationTest { tester =>
      val res = tester.eval("foo.test")
      assert(res.isSuccess)
      assert(res.out.contains("NormalSuite"))
      assert(!res.out.contains("NotAutoDiscoverable"))
    }

    test("doNotDiscoverRunsWhenExplicitlySelected") - integrationTest { tester =>
      val res = tester.eval(("foo.test.testOnly", "foo.NotAutoDiscoverable"))
      assert(res.isSuccess)
      assert(res.out.contains("NotAutoDiscoverable"))
    }

    test("doNotDiscoverSkippedByGlobSelector") - integrationTest { tester =>
      // A glob only counts as "discovery", not an explicit selection, so it must not
      // bypass `@DoNotDiscover` even though it matches the class name.
      val res = tester.eval(("foo.test.testOnly", "foo.*"))
      assert(res.isSuccess)
      assert(res.out.contains("NormalSuite"))
      assert(!res.out.contains("NotAutoDiscoverable"))
    }
  }
}
