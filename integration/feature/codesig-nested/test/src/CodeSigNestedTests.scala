package mill.integration

import utest._

object CodeSigNestedTests extends IntegrationTestSuite {
  val tests: Tests = Tests {
    val wsRoot = initWorkspace()

    "nested" - {
      // Make sure the code-change invalidation works in more complex cases: multi-step
      // target graphs, targets inside module objects, targets inside module traits

      // Check normal behavior for initial run and subsequent fully-cached run
      // with no changes
      val initial = evalStdout("outer.inner.qux")
      assert(
        initial.out.linesIterator.toSeq == Seq(
          "running foo",
          "running helperFoo",
          "running bar",
          "running helperBar",
          "running qux",
          "running helperQux"
        )
      )

      val cached = evalStdout("outer.inner.qux")
      assert(cached.out == "")

      // Changing the body of a T{...} block directly invalidates that target,
      // but not downstream targets unless the return value changes
      mangleFile(wsRoot / "build.sc", _.replace("running foo", "running foo2"))
      val mangledFoo = evalStdout("outer.inner.qux")
      assert(
        mangledFoo.out.linesIterator.toSeq == Seq(
          "running foo2",
          "running helperFoo"
          // The return value of foo did not change so qux is not invalidated
        )
      )

      mangleFile(wsRoot / "build.sc", _.replace("; helperFoo }", "; helperFoo + 4 }"))
      val mangledHelperFooCall = evalStdout("outer.inner.qux")
      assert(
        mangledHelperFooCall.out.linesIterator.toSeq == Seq(
          "running foo2",
          "running helperFoo",
          // The return value of foo changes from 1 to 1+4=5, so qux is invalidated
          "running qux",
          "running helperQux"
        )
      )

      mangleFile(wsRoot / "build.sc", _.replace("running qux", "running qux2"))
      val mangledQux = evalStdout("outer.inner.qux")
      assert(
        mangledQux.out.linesIterator.toSeq ==
          // qux itself was changed, and so it is invalidated
          Seq("running qux2", "running helperQux")
      )

      // Changing the body of some helper method that gets called by a T{...}
      // block also invalidates the respective targets, and downstream targets if necessary

      mangleFile(wsRoot / "build.sc", _.replace(" 1 ", " 6 "))
      val mangledHelperFooValue = evalStdout("outer.inner.qux")
      assert(
        mangledHelperFooValue.out.linesIterator.toSeq == Seq(
          "running foo2",
          "running helperFoo",
          // Because the return value of helperFoo/foo changes from 1+4=5 to 6+5=11, qux is invalidated
          "running qux2",
          "running helperQux"
        )
      )

      mangleFile(wsRoot / "build.sc", _.replace("running helperBar", "running helperBar2"))
      val mangledHelperBar = evalStdout("outer.inner.qux")
      assert(
        mangledHelperBar.out.linesIterator.toSeq == Seq(
          "running bar",
          "running helperBar2"
          // We do not need to re-evaluate qux because the return value of bar did not change
        )
      )

      mangleFile(wsRoot / "build.sc", _.replace("20", "70"))
      val mangledHelperBarValue = evalStdout("outer.inner.qux")
      assert(
        mangledHelperBarValue.out.linesIterator.toSeq == Seq(
          "running bar",
          "running helperBar2",
          // Because the return value of helperBar/bar changes from 20 to 70, qux is invalidated
          "running qux2",
          "running helperQux"
        )
      )

      mangleFile(wsRoot / "build.sc", _.replace("running helperQux", "running helperQux2"))
      val mangledBar = evalStdout("outer.inner.qux")
      assert(
        mangledBar.out.linesIterator.toSeq ==
          // helperQux was changed, so qux needs to invalidate
          Seq("running qux2", "running helperQux2")
      )

      // Make sure changing `val`s in varying levels of nested modules conservatively invalidates
      // all targets in inner modules, regardless of whether they are related or not
      mangleFile(wsRoot / "build.sc", _.replace("val valueFoo = 0", "val valueFoo = 10"))
      val mangledValFoo = evalStdout("outer.inner.qux")
      assert(
        mangledValFoo.out.linesIterator.toSeq == Seq(
          "running foo2",
          "running helperFoo",
          "running bar",
          "running helperBar2",
          "running qux2",
          "running helperQux2"
        )
      )

      mangleFile(wsRoot / "build.sc", _.replace("val valueBar = 0", "val valueBar = 10"))
      val mangledValBar = evalStdout("outer.inner.qux")
      assert(
        mangledValBar.out.linesIterator.toSeq == Seq(
          "running bar",
          "running helperBar2",
          "running qux2",
          "running helperQux2"
        )
      )

      mangleFile(wsRoot / "build.sc", _.replace("val valueQux = 0", "val valueQux = 10"))
      val mangledValQux = evalStdout("outer.inner.qux")
      assert(
        mangledValQux.out.linesIterator.toSeq == Seq(
          "running qux2",
          "running helperQux2"
        )
      )

      mangleFile(
        wsRoot / "build.sc",
        _.replace("val valueFooUsedInBar = 0", "val valueFooUsedInBar = 10")
      )
      val mangledValFooUsedInBar = evalStdout("outer.inner.qux")
      assert(
        mangledValFooUsedInBar.out.linesIterator.toSeq == Seq(
          "running foo2",
          "running helperFoo",
          "running bar",
          "running helperBar2",
          "running qux2",
          "running helperQux2"
        )
      )

      mangleFile(
        wsRoot / "build.sc",
        _.replace("val valueBarUsedInQux = 0", "val valueBarUsedInQux = 10")
      )
      val mangledValBarUsedInQux = evalStdout("outer.inner.qux")
      assert(
        mangledValBarUsedInQux.out.linesIterator.toSeq == Seq(
          "running bar",
          "running helperBar2",
          "running qux2",
          "running helperQux2"
        )
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
    }

    "trait" - {
      val initial = evalStdout("traitOuter.traitInner.inner")
      assert(
        initial.out.linesIterator.toSeq == Seq(
          "running foo",
          "running helperFoo",
          "running outer",
          "running helperTraitOuter",
          "running inner",
          "running helperTraitInner"
        )
      )

      val cached = evalStdout("traitOuter.traitInner.inner")
      assert(cached.out == "")

      mangleFile(
        wsRoot / "build.sc",
        _.replace("val valueTraitInner = 0", "val valueTraitInner = 10")
      )
      val mangleTraitInnerValue = evalStdout("traitOuter.traitInner.inner")
      assert(
        mangleTraitInnerValue.out.linesIterator.toSeq == Seq(
          "running inner",
          "running helperTraitInner"
        )
      )

      mangleFile(
        wsRoot / "build.sc",
        _.replace("val valueTraitOuter = 0", "val valueTraitOuter = 10")
      )
      val mangleTraitOuterValue = evalStdout("traitOuter.traitInner.inner")
      assert(
        mangleTraitOuterValue.out.linesIterator.toSeq == Seq(
          "running outer",
          "running helperTraitOuter",
          "running inner",
          "running helperTraitInner"
        )
      )
    }
  }
}
