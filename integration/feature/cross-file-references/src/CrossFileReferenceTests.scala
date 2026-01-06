package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

/**
 * Make sure `build.mill` files, `package.mill` files, and helper files
 * at various levels can all reference each other as normal, and that the
 * Mill's code generation doesn't break any of thes cases
 */
object CrossFileReferencesTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>
      import tester.*
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

      val res7 = eval(("show", "qux"))
      assert(res7.out == "31337")

      val res8 = eval(("show", "nested.foo"))
      assert(res8.out == "31337")

      val res9 = eval(("show", "nested.bar"))
      assert(res9.out == "1337")

      val res10 = eval(("show", "nested.local.foo"))
      assert(res10.out == "1337")

      val res11 = eval(("show", "nested.local.bar"))
      assert(res11.out == "31337")

      val res12 = eval(("show", "nested.local2.foo"))
      assert(res12.out == "31337")

      val res13 = eval(("show", "nested.local2.bar"))
      assert(res13.out == "1337")

      val res14 = eval(("show", "nested.qux"))
      assert(res14.out == "1337")
    }
  }
}
