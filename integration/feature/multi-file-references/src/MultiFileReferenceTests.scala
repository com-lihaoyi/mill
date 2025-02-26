package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

/**
 * Make sure `build.mill` files, `package.mill` files, and helper files can
 * all reference each other as normal, and that the code generation doesn't
 * end up messing things up
 */
object MultiFileReferencesTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester._
      val res = eval(("show", "foo"))
      assert(res.out == "1337")

      val res2 = eval(("show", "bar"))
      assert(res2.out == "31337")

      val res3 = eval(("show", "local.foo"))
      assert(res3.out == "31337")

      val res4 = eval(("show", "local.bar"))
      assert(res4.out == "1337")

      val res5 = eval(("show", "local2.foo"))
      assert(res5.out == "1337")

      val res6 = eval(("show", "local2.bar"))
      assert(res6.out == "31337")

      val res7 = eval(("show", "nested.foo"))
      assert(res7.out == "31337")

      val res8 = eval(("show", "nested.bar"))
      assert(res8.out == "1337")

      val res9 = eval(("show", "nested.local.foo"))
      assert(res9.out == "1337")

      val res10 = eval(("show", "nested.local.bar"))
      assert(res10.out == "31337")

      val res11 = eval(("show", "nested.local2.foo"))
      assert(res11.out == "31337")

      val res12 = eval(("show", "nested.local2.bar"))
      assert(res12.out == "1337")
    }
  }
}
