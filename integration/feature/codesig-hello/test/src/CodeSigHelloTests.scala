package mill.integration

import utest._

object CodeSigHelloTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    val wsRoot = initWorkspace()
    "simple" - {
      // Make sure the simplest case where we have a single target calling a single helper
      // method is properly invalidated when either the target body, or the helper method's body
      // is changed, or something changed in the constructor
      val initial = evalStdout("foo")

      assert(initial.out.linesIterator.toSeq == Seq("running foo", "running helperFoo"))

      val cached = evalStdout("foo")
      assert(cached.out == "")

      mangleFile(wsRoot / "build.sc", _.replace("running foo", "running foo2"))
      val mangledFoo = evalStdout("foo")

      assert(mangledFoo.out.linesIterator.toSeq == Seq("running foo2", "running helperFoo"))

      val cached2 = evalStdout("foo")
      assert(cached2.out == "")

      mangleFile(wsRoot / "build.sc", _.replace("running helperFoo", "running helperFoo2"))
      val mangledHelperFoo = evalStdout("foo")

      assert(mangledHelperFoo.out.linesIterator.toSeq == Seq("running foo2", "running helperFoo2"))

      // Make sure changing `val`s, which only affects the Module constructor and
      // not the Target method itself, causes invalidation
      mangleFile(wsRoot / "build.sc", _.replace("val valueFoo = 0", "val valueFoo = 10"))
      val mangledValFoo = evalStdout("foo")
      assert(mangledValFoo.out.linesIterator.toSeq == Seq("running foo2", "running helperFoo2"))

      // Even modifying `val`s that do not affect the target invalidates it, because
      // we only know that the constructor changed and don't do enough analysis to
      // know that this particular val is not used
      mangleFile(
        wsRoot / "build.sc",
        _.replace("val valueFooUsedInBar = 0", "val valueFooUsedInBar = 10")
      )
      val mangledValFooUsedInBar = evalStdout("foo")
      assert(mangledValFooUsedInBar.out.linesIterator.toSeq == Seq(
        "running foo2",
        "running helperFoo2"
      ))

      val cached3 = evalStdout("foo")
      assert(cached3.out == "")
    }
  }
}
