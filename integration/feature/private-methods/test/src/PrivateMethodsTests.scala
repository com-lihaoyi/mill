package mill.integration

import utest._

object PrivateMethodsTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()
    "simple" - {
      // Simple public target depending on private target works
      val pub = evalStdout("show", "pub")
      assert(pub.out == "\"priv\"")
      assert(pub.isSuccess)

      // Make sure calling private methods indirectly in a variety of scenarios works
      // even when they're "not really private" due to
      val fooBar = evalStdout("show", "foo.bar")
      assert(fooBar.out == "\"bazOuter\"")
      assert(fooBar.isSuccess)

      val quxFooBar = evalStdout("show", "qux.foo.bar")
      assert(quxFooBar.out == "\"bazInner\"")
      assert(quxFooBar.isSuccess)

      val clsFooBar = evalStdout("show", "cls.foo.bar")
      assert(clsFooBar.out == "\"bazCls\"")
      assert(clsFooBar.isSuccess)

      // Make sure calling private methods directly fails
      val priv = evalStdout("show", "priv")
      assert(priv.err.contains("Cannot resolve priv"))
      assert(priv.isSuccess == false)

      val baz = evalStdout("show", "baz")
      assert(baz.err.contains("Cannot resolve baz"))
      assert(baz.isSuccess == false)

      val quxBaz = evalStdout("show", "qux.baz")
      assert(quxBaz.err.contains("Cannot resolve qux.baz"))
      assert(quxBaz.isSuccess == false)

      val clsBaz = evalStdout("show", "cls.baz")
      assert(clsBaz.err.contains("Cannot resolve cls.baz"))
      assert(clsBaz.isSuccess == false)
    }
  }
}
