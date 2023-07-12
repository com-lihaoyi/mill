package mill.integration

import utest._

object CodeSigSimpleTests extends IntegrationTestSuite {
  val tests = Tests {
    val wsRoot = initWorkspace()
    "target" - {
      // Make sure the simplest case where we have a single target calling a single helper
      // method is properly invalidated when either the target body or the helper method's body
      // is changed
      val initial = evalStdout("foo")

      assert(
        initial.out.linesIterator.toSeq ==
          """running foo
            |running helperFoo""".stripMargin.linesIterator.toSeq
      )

      val cached = evalStdout("foo")
      assert(cached.out == "")

      mangleFile(wsRoot / "build.sc", _.replace("running foo", "running foo2"))
      val mangledFoo = evalStdout("foo")

      assert(
        mangledFoo.out.linesIterator.toSeq ==
          """running foo2
            |running helperFoo""".stripMargin.linesIterator.toSeq
      )

      val cached2 = evalStdout("foo")
      assert(cached2.out == "")

      mangleFile(wsRoot / "build.sc", _.replace("running helperFoo", "running helperFoo2"))
      val mangledHelperFoo = evalStdout("foo")

      assert(
        mangledHelperFoo.out.linesIterator.toSeq ==
          """running foo2
            |running helperFoo2""".stripMargin.linesIterator.toSeq
      )

      val cached3 = evalStdout("foo")
      assert(cached3.out == "")
    }

    "traitsAndObjects" - {
      // Make sure the code-change invalidation works in more complex cases: multi-step
      // target graphs, targets inside module objects, targets inside module traits

      // Check normal behavior for initial run and subsequent fully-cached run
      // with no changes
      val initial = evalStdout("outer.inner.qux")

      assert(
        initial.out.linesIterator.toSeq ==
          """running foo
            |running helperFoo
            |running bar
            |running helperBar
            |running qux
            |running helperQux""".stripMargin.linesIterator.toSeq
      )

      val cached = evalStdout("outer.inner.qux")
//      assert(cached.out == "")

      // Changing the body of a T{...} block directly invalidates that target
      // and any downstream targets
      mangleFile(wsRoot / "build.sc", _.replace("running foo", "running foo2"))
      val mangledFoo = evalStdout("outer.inner.qux")

      assert(
        mangledFoo.out.linesIterator.toSeq ==
          """running foo2
            |running helperFoo
            |running qux
            |running helperQux""".stripMargin.linesIterator.toSeq
      )

      mangleFile(wsRoot / "build.sc", _.replace("running qux", "running qux2"))
      val mangledQux = evalStdout("outer.inner.qux")
      assert(
        mangledQux.out.linesIterator.toSeq ==
          """running qux2
            |running helperQux""".stripMargin.linesIterator.toSeq
      )

      // Changing the body of some helper method that gets called by a T{...}
      // block also invalidates the respective targets
      mangleFile(wsRoot / "build.sc", _.replace("running helperBar", "running helperBar2"))
      val mangledHelperBar = evalStdout("outer.inner.qux")
      assert(
        mangledHelperBar.out.linesIterator.toSeq ==
          """running bar
            |running helperBar2
            |running qux2
            |running helperQux""".stripMargin.linesIterator.toSeq
      )

      mangleFile(wsRoot / "build.sc", _.replace("running helperQux", "running helperQux2"))
      val mangledBar = evalStdout("outer.inner.qux")

      assert(
        mangledBar.out.linesIterator.toSeq ==
          """running qux2
            |running helperQux2""".stripMargin.linesIterator.toSeq
      )

      // Adding a newline before one of the target definitions does not invalidate it
      mangleFile(wsRoot / "build.sc", _.replace("def qux", "\ndef qux"))
      val addedSingleNewline = evalStdout("outer.inner.qux")
      assert(addedSingleNewline.out == "")

      mangleFile(wsRoot / "build.sc", _.replace("def", "\ndef"))
      val addedManyNewlines = evalStdout("outer.inner.qux")
      assert(addedManyNewlines.out == "")

      // Reformatting the entire file, replacing `;`s with `\n`s and spacing out
      // the target bodies over multiple lines does not cause anything to invalidate
      mangleFile(
        wsRoot / "build.sc",
        _.replace("{", "{\n").replace("}", "\n}").replace(";", "\n")
      )
      val addedNewlinesInsideCurlies = evalStdout("outer.inner.qux")
      assert(addedNewlinesInsideCurlies.out == "")

      mangleFile(
        wsRoot / "build.sc",
        _.replace("import mill._", "import mill.T; import java.util.Properties")
      )
      val mangledImports = evalStdout("outer.inner.qux")
      assert(mangledImports.out == "")
    }
  }
}
