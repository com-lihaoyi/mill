package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ClassloaderHygieneTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test - integrationTest { tester =>

      val out0 = tester.eval(("show", "countClassLoaders"))
      assert(out0.out == "0")

      tester.eval(("show", "clean"))
      tester.eval(("show", "__.compile"))
      val out1 = tester.eval(("show", "countClassLoaders"))
      assert(out1.out == "3")

      tester.eval(("show", "clean"))
      tester.eval(("show", "__.compile"))
      val out2 = tester.eval(("show", "countClassLoaders"))
      assert(out2.out == "3")

      tester.eval(("show", "clean"))
      tester.eval(("show", "__.compile"))
      val out3 = tester.eval(("show", "countClassLoaders"))
      assert(out3.out == "3")

      tester.eval(("shutdown"))
      val out4 = tester.eval(("show", "countClassLoaders"))
      assert(out4.out == "0")


      tester.eval(("show", "clean"))
      tester.eval(("show", "__.compile"))
      val out5 = tester.eval(("show", "countClassLoaders"))
      assert(out5.out == "3")
    }
  }
}
