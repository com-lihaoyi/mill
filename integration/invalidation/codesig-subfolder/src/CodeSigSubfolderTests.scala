package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

// Basically a copy of CodeSigHelloTests, but split across two files
// (build.mill and subfolder/package.mill) and with some extra assertions
// to exercise invalidation behavior specific to multi-file-builds
object CodeSigSubfolderTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("simple") - integrationTest { tester =>
      import tester._

      val initial = eval("foo")

      assert(initial.out.linesIterator.toSeq == Seq("running foo", "running helperFoo"))

      val cached = eval("foo")
      assert(cached.out == "")
      val subFolderRes = eval("subfolder.subFolderTask")
      assert(subFolderRes.out.linesIterator.toSeq == Seq("running subFolderTask"))

      modifyFile(workspacePath / "build.mill", _.replace("running foo", "running foo2"))
      val mangledFoo = eval("foo")
      assert(mangledFoo.out.linesIterator.toSeq == Seq("running foo2", "running helperFoo"))

      val cached2 = eval("foo")
      assert(cached2.out == "")

      // Changing stuff in the top-level build.mill does not invalidate tasks in subfolder/package.mill
      val subFolderResCached = eval("subfolder.subFolderTask")
      assert(subFolderResCached.out == "")

      modifyFile(
        workspacePath / "subfolder/package.mill",
        _.replace("running subFolderTask", "running subFolderTask2")
      )
      // Changing stuff in subfolder/package.mill does not invalidate unrelated tasks in build.mill
      val cached3 = eval("foo")
      assert(cached3.out == "")

      modifyFile(
        workspacePath / "subfolder/package.mill",
        _.replace("running helperFoo", "running helperFoo2")
      )
      val mangledHelperFoo = eval("foo")

      assert(mangledHelperFoo.out.linesIterator.toSeq == Seq("running foo2", "running helperFoo2"))

      // Make sure changing `val`s, which only affects the Module constructor and
      // not the Task method itself, causes invalidation
      modifyFile(
        workspacePath / "subfolder/package.mill",
        _.replace("val valueFoo = 0", "val valueFoo = 10")
      )
      val mangledValFoo = eval("foo")
      assert(mangledValFoo.out.linesIterator.toSeq == Seq("running foo2", "running helperFoo2"))

      // Even modifying `val`s that do not affect the task invalidates it, because
      // we only know that the constructor changed and don't do enough analysis to
      // know that this particular val is not used
      modifyFile(
        workspacePath / "subfolder/package.mill",
        _.replace("val valueFooUsedInBar = 0", "val valueFooUsedInBar = 10")
      )
      val mangledValFooUsedInBar = eval("foo")
      assert(mangledValFooUsedInBar.out.linesIterator.toSeq == Seq(
        "running foo2",
        "running helperFoo2"
      ))

      val cached4 = eval("foo")
      assert(cached4.out == "")
    }
  }
}
