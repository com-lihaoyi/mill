package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest._

object CodeSigHelloTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("simple") - integrationTest { tester =>
      import tester._
      // Make sure the simplest case where we have a single task calling a single helper
      // method is properly invalidated when either the task body, or the helper method's body
      // is changed, or something changed in the constructor
      val initial = eval("foo")

      assert(initial.out.linesIterator.toSeq == Seq("running foo", "running helperFoo"))

      val cached = eval("foo")
      assert(cached.out == "")

      modifyFile(workspacePath / "build.mill", _.replace("running foo", "running foo2"))
      val mangledFoo = eval("foo")

      val out1 = mangledFoo.out.linesIterator.toSeq
      assert(out1 == Seq("running foo2", "running helperFoo"))

      val cached2 = eval("foo")
      assert(cached2.out == "")

      modifyFile(workspacePath / "build.mill", _.replace("running helperFoo", "running helperFoo2"))
      val mangledHelperFoo = eval("foo")

      assert(mangledHelperFoo.out.linesIterator.toSeq == Seq("running foo2", "running helperFoo2"))

      // Make sure changing `val`s, which only affects the Module constructor and
      // not the Task method itself, causes invalidation
      modifyFile(workspacePath / "build.mill", _.replace("val valueFoo = 0", "val valueFoo = 10"))
      val mangledValFoo = eval("foo")
      assert(mangledValFoo.out.linesIterator.toSeq == Seq("running foo2", "running helperFoo2"))

      // Even modifying `val`s that do not affect the task invalidates it, because
      // we only know that the constructor changed and don't do enough analysis to
      // know that this particular val is not used
      modifyFile(
        workspacePath / "build.mill",
        _.replace("val valueFooUsedInBar = 0", "val valueFooUsedInBar = 10")
      )
      val mangledValFooUsedInBar = eval("foo")
      assert(mangledValFooUsedInBar.out.linesIterator.toSeq == Seq(
        "running foo2",
        "running helperFoo2"
      ))
      mill.constants.DebugLog.println("=" * 100)

      val cached3 = eval("foo")
      assert(cached3.out == "")

      // Changing the body of a `lazy val` invalidates the usages
      modifyFile(
        workspacePath / "build.mill",
        _.replace("lazy val lazyValue2 = 0", "lazy val lazyValue3 = 0")
      )
      modifyFile(
        workspacePath / "build.mill",
        _.replace("lazy val lazyValue = 0", "lazy val lazyValue2 = 0")
      )
      modifyFile(
        workspacePath / "build.mill",
        _.replace("lazy val lazyValue3 = 0", "lazy val lazyValue = 0")
      )
      val cached4 = eval("foo")
      assert(cached4.out == "")

      // Changing the body of a `lazy val` invalidates the usages
      modifyFile(
        workspacePath / "build.mill",
        _.replace("lazy val lazyValue = 0", "lazy val lazyValue = 1")
      )
      val mangledLazyVal = eval("foo")
      assert(mangledLazyVal.out.linesIterator.toSeq == Seq(
        "running foo2",
        "running helperFoo2"
      ))
    }
  }
}
