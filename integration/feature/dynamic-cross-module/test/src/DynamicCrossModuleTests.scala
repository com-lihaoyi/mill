package mill.integration

import utest._


// Make sure that values watched by `interp.watchValue` properly cause the base
// module to be re-evaluated, so that changes in the module tree that depend on
// those changes can properly take place. This is most commonly due to defining
// the cases of a cross-module to depend on the files present in a folder, in
// which case the `interp.watchValue(os.list(...))` call will need to trigger
// re-evaluation of the module tree when it changes.
object DynamicCrossModuleTests extends IntegrationTestSuite {
  val tests = Tests {
    val wsRoot = initWorkspace()
    test("test") - {


      val res = evalStdout("resolve", "modules._")
      assert(res.isSuccess == true)
      assert(
        res.out.linesIterator.toSet ==
        Set("modules[bar]", "modules[foo]", "modules[qux]")
      )

      val res2 = evalStdout("modules[bar].run")
      assert(res2.isSuccess == true)
      if (integrationTestMode != "local") {
        assert(res2.out.contains("Hello World Bar"))
      }

      val res3 = evalStdout("modules[new].run")
      assert(res3.isSuccess == false)
      assert(res3.err.contains("Cannot resolve modules[new]"))

      val res4 = evalStdout("modules[newer].run")
      assert(res4.isSuccess == false)
      assert(res4.err.contains("Cannot resolve modules[newer]"))

      os.copy(wsRoot / "modules" / "bar", wsRoot / "modules" / "new")
      mangleFile(
        wsRoot / "modules" / "new" / "src" / "Example.scala",
        _.replace("Bar", "New")
      )
      os.copy(wsRoot / "modules" / "bar", wsRoot / "modules" / "newer")
      mangleFile(
        wsRoot / "modules" / "newer" / "src" / "Example.scala",
        _.replace("Bar", "Newer")
      )

      val res5 = evalStdout("modules[new].run")
      assert(res5.isSuccess == true)
      if (integrationTestMode != "local") {
        assert(res5.out.contains("Hello World New"))
      }

      val res6 = evalStdout("modules[newer].run")
      assert(res6.isSuccess == true)
      if (integrationTestMode != "local") {
        assert(res6.out.contains("Hello World Newer"))
      }

      val res7 = evalStdout("resolve", "modules._")
      assert(res7.isSuccess == true)
      assert(
        res7.out.linesIterator.toSet ==
        Set("modules[bar]", "modules[foo]", "modules[qux]", "modules[new]", "modules[newer]")
      )
    }
  }
}
