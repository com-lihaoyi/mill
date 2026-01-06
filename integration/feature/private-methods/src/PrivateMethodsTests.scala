package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

object PrivateMethodsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("simple") - integrationTest { tester =>
      import tester.*
      // Simple public task depending on private task works
      val pub = eval(("show", "pub"))
      assert(pub.out == "\"priv\"")
      assert(pub.isSuccess)

      // Make sure calling private methods indirectly in a variety of scenarios works
      // even when they're "not really private" due to
      val fooBar = eval(("show", "foo.bar"))
      assert(fooBar.out == "\"bazOuter\"")
      assert(fooBar.isSuccess)

      val quxFooBar = eval(("show", "qux.foo.bar"))
      assert(quxFooBar.out == "\"bazInner\"")
      assert(quxFooBar.isSuccess)

      val clsFooBar = eval(("show", "cls.foo.bar"))
      assert(clsFooBar.out == "\"bazCls\"")
      assert(clsFooBar.isSuccess)

      // Make sure calling private methods directly fails
      val priv = eval(("show", "priv"))
      assert(priv.err.contains("Cannot resolve priv"))
      assert(priv.isSuccess == false)

      val baz = eval(("show", "baz"))
      assert(baz.err.contains("Cannot resolve baz"))
      assert(baz.isSuccess == false)

      val quxBaz = eval(("show", "qux.baz"))
      assert(quxBaz.err.contains("Cannot resolve qux.baz"))
      assert(quxBaz.isSuccess == false)

      val clsBaz = eval(("show", "cls.baz"))
      assert(clsBaz.err.contains("Cannot resolve cls.baz"))
      assert(clsBaz.isSuccess == false)
    }
  }
}
