package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object ZincBuildCompilationTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("simple") - integrationTest { tester =>
      import tester._

      val initial = eval(("dummy"))

      assert(initial.err.contains("compiling 2 Scala sources"))

      val cached = eval(("dummy"))
      assert(!cached.err.contains("compiling"))

      modifyFile(workspacePath / "build.mill", _.replace("running foo", "running foo2"))
      val mangledFoo = eval(("dummy"))
      assert(mangledFoo.err.contains("compiling 1 Scala source"))

      val cached2 = eval(("dummy"))
      assert(!cached2.err.contains("compiling"))

      val subFolderResCached = eval(("dummy"))
      assert(!subFolderResCached.err.contains("compiling"))

      modifyFile(
        workspacePath / "subfolder/package.mill",
        _.replace("running helperFoo", "running helperFoo2")
      )
      val mangledHelperFoo = eval(("dummy"))
      // This should only compile 1 source but it seems there's an upstream bug in Zinc
      // https://github.com/sbt/zinc/issues/1461
      assert(mangledHelperFoo.err.contains("compiling 2 Scala source"))

    }
  }
}
