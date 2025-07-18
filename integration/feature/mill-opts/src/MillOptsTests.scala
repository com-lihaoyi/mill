package mill.integration

import mill.constants.Util
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.*

object MillOptsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    def runTests(tester: IntegrationTester) = {
      import tester._
      val res = eval("checkJvmOpts")
      assert(res.isSuccess)

      // interpolatedEnvVars
      if (!Util.isWindows) { // PWD does not exist on windows
        import tester._
        val res = eval(("show", "getEnvJvmOpts"))
        val out = res.out
        val expected = "\"value-with-" + tester.workspacePath + "\""
        assert(out == expected)
      }

      // nonJvmOpts
      val res2 = eval(("show", "getNonJvmOpts"))
      assert(res2.out == "17")

      // overrideNonJvmOpts
      val res3 = eval(("--jobs", "19", "show", "getNonJvmOpts"))
      assert(res3.out == "19")

    }

    test("files") - integrationTest(runTests)

    test("header") - integrationTest { tester =>
      os.remove(tester.workspacePath / ".mill-jvm-opts")
      os.remove(tester.workspacePath / ".mill-opts")
      val header =
        """//| # comment
          |//| mill-jvm-opts:
          |//| - "-Dproperty.properly.set.via.jvm.opts=value-from-file"
          |//| - "-Dproperty.with.interpolated.working.dir=value-with-${PWD}"
          |//| - "-Xss120m"
          |//| # empty line
          |//|
          |//| # comment after an empty line
          |//| mill-opts: ["--jobs=17"]
          |""".stripMargin
      os.write.over(
        tester.workspacePath / "build.mill",
        header ++ os.read(tester.workspacePath / "build.mill")
      )
      runTests(tester)
    }

    test("flags") - integrationTest { tester =>
      import tester.*

      // env
      val n = 2L
      val maxMemory = eval(("show", "maxMemory"), Map("JAVA_OPTS" -> s"-Xmx${n}G"))
      assert(maxMemory.isSuccess)
      assert(maxMemory.out.trim.toLong == n * 1024 * 1024 * 1024)

      val propValue = 123
      val testProperty =
        eval(("show", "testProperty"), Map("JAVA_OPTS" -> s"-Dtest.property=$propValue"))
      assert(testProperty.isSuccess)
      assert(testProperty.out.trim.toInt == propValue)

      // props
      // Property not set
      val res1 = eval(("printSysProp", "--propName", "foo-property"))
      assert(res1.out == "null")

      // Property newly set
      val res2 = eval(("-Dfoo-property=hello-world", "printSysProp", "--propName", "foo-property"))
      assert(res2.out == "hello-world")

      // Existing property modified
      val res3 = eval(("-Dfoo-property=i-am-cow", "printSysProp", "--propName", "foo-property"))
      assert(res3.out == "i-am-cow")

      // Existing property removed
      val res4 = eval(("printSysProp", "--propName", "foo-property"))
      assert(res4.out == "null")
    }
  }
}
