package mill.integration

import mill.testkit.IntegrationTestSuite

import utest._

object CompileErrorTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    initWorkspace()

    test {
      val res = eval("foo.scalaVersion")

      assert(res.isSuccess == false)
//      assert(res.err.contains("""bar.mill:15:9: not found: value doesntExist"""))
//      assert(res.err.contains("""println(doesntExist)"""))

      assert(res.err.contains("""build.mill:7:22: not found: value unknownRootInternalDef"""))
      assert(res.err.contains("""def scalaVersion = unknownRootInternalDef"""))
      assert(res.err.contains("""build.mill:4:23: not found: type UnknownBeforeModule"""))
      assert(res.err.contains("""object before extends UnknownBeforeModule"""))
      assert(res.err.contains("""build.mill:10:22: not found: type UnknownAfterModule"""))
      assert(res.err.contains("""object after extends UnknownAfterModule"""))

      assert(res.err.contains("""foo/package.mill:7:22: not found: value unknownFooInternalDef"""))
      assert(res.err.contains("""def scalaVersion = unknownFooInternalDef"""))
      assert(res.err.contains("""foo/package.mill:4:23: not found: type UnknownBeforeFooModule"""))
      assert(res.err.contains("""object before extends UnknownBeforeFooModule"""))
      assert(res.err.contains("""foo/package.mill:10:22: not found: type UnknownAfterFooModule"""))
      assert(res.err.contains("""object after extends UnknownAfterFooModule"""))
    }
  }
}
