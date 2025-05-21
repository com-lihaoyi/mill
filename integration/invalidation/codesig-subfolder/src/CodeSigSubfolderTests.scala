package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

// Basically a copy of CodeSigHelloTests, but split across two files
// (build.mill and subfolder/package.mill) and with some extra assertions
// to exercise invalidation behavior specific to multi-file-builds
//
// Add a bunch of dummy subfolders to try and ensure that codesig is computed
// correctly even in the presence of non-trivial subfolder `package.mill` setups
object CodeSigSubfolderTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("simple") - integrationTest { tester =>
      import tester._

      // eager capture output so we see it in asserts
      case class EvalOuts(out: String, err: String)
      def evalOuts(cmd: String): EvalOuts = {
        val res = eval(cmd)
        EvalOuts(res.out, res.err)
      }

      val initial = evalOuts("foo")

      assert(initial.out.linesIterator.toSeq == Seq("running foo", "running helperFoo"))
      assert(initial.err.contains("compiling 21 Scala sources"))

      val cached = evalOuts("foo")
      assert(cached.out == "")
      assert(!cached.err.contains("compiling"))

      val subFolderRes = evalOuts("subfolder.subFolderTask")
      assert(subFolderRes.out.linesIterator.toSeq == Seq("running subFolderTask"))
      assert(!subFolderRes.err.contains("compiling"))

      modifyFile(workspacePath / "build.mill", _.replace("running foo", "running foo2"))
      val mangledFoo = evalOuts("foo")
      assert(mangledFoo.out.linesIterator.toSeq == Seq("running foo2", "running helperFoo"))
      assert(mangledFoo.err.contains("compiling 1 Scala source"))

      val cached2 = evalOuts("foo")
      assert(cached2.out == "")
      assert(!cached2.err.contains("compiling"))

      // Changing stuff in the top-level build.mill does not invalidate tasks in subfolder/package.mill
      val subFolderResCached = evalOuts("subfolder.subFolderTask")
      assert(subFolderResCached.out == "")
      assert(!subFolderResCached.err.contains("compiling"))

      modifyFile(
        workspacePath / "subfolder/package.mill",
        _.replace("running subFolderTask", "running subFolderTask2")
      )
      // Changing stuff in subfolder/package.mill does not invalidate unrelated tasks in build.mill
      val cached3 = evalOuts("foo")
      assert(cached3.out == "")
      assert(cached3.err.contains("compiling 1 Scala source"))

      modifyFile(
        workspacePath / "subfolder/package.mill",
        _.replace("running helperFoo", "running helperFoo2")
      )
      val mangledHelperFoo = evalOuts("foo")
      assert(mangledHelperFoo.out.linesIterator.toSeq == Seq("running foo2", "running helperFoo2"))
      assert(mangledHelperFoo.err.contains("compiling 1 Scala source"))

      // Make sure changing `val`s, which only affects the Module constructor and
      // not the Task method itself, causes invalidation
      modifyFile(
        workspacePath / "subfolder/package.mill",
        _.replace("val valueFoo = 0", "val valueFoo = 10")
      )
      val mangledValFoo = evalOuts("foo")
      assert(mangledValFoo.out.linesIterator.toSeq == Seq("running foo2", "running helperFoo2"))
      assert(mangledValFoo.err.contains("compiling 1 Scala source"))

      // Even modifying `val`s that do not affect the task invalidates it, because
      // we only know that the constructor changed and don't do enough analysis to
      // know that this particular val is not used
      modifyFile(
        workspacePath / "subfolder/package.mill",
        _.replace("val valueFooUsedInBar = 0", "val valueFooUsedInBar = 10")
      )
      val mangledValFooUsedInBar = evalOuts("foo")
      assert(mangledValFooUsedInBar.out.linesIterator.toSeq == Seq(
        "running foo2",
        "running helperFoo2"
      ))

      assert(mangledValFooUsedInBar.err.contains("compiling 1 Scala source"))
    }
  }
}

object CodeSigSubfolderRenamedSameOrderTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {

    test("subfolder-renames-same-order") - integrationTest { tester =>
      import tester._
      val cached4 = eval("foo")
      assert(cached4.out.contains("running foo"))

      // Make sure if we rename a subfolder without re-ordering it
      // alphabetically, it does not cause spurious invalidations
      modifyFile(
        workspacePath / "subfolder9/package.mill",
        _.replace("package build.subfolder9", "package build.z_subfolder9")
      )
      os.move(workspacePath / "subfolder9", workspacePath / "z_subfolder9")
      val cached5 = eval("foo")
      assert(!cached5.out.contains("running foo"))
    }
  }
}

object CodeSigSubfolderRenamedReorderTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("subfolder-renames-reorder") - integrationTest { tester =>
      import tester._
      val cached4 = eval("foo")
      assert(cached4.out.contains("running foo"))

      // Make sure if we rename a subfolder while moving it to the top of
      // the list alphabetically, it does not cause spurious invalidations
      modifyFile(
        workspacePath / "subfolder9/package.mill",
        _.replace("package build.subfolder9", "package build.a_subfolder9")
      )
      os.move(workspacePath / "subfolder9", workspacePath / "a_subfolder9")
      val cached5 = eval("foo")
      assert(!cached5.out.contains("running foo"))
    }
  }
}
