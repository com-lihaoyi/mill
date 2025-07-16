import mill.testkit.UtestIntegrationTestSuite
import utest.{assert, *}

object CodeSigNestedTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("nested") - integrationTest { tester =>
      import tester.*
      // Make sure the code-change invalidation works in more complex cases: multi-step
      // task graphs, tasks inside module objects, tasks inside module traits

      // Check normal behavior for initial run and subsequent fully-cached run
      // with no changes
      val initial = eval("outer.inner.qux")
      assert(
        initial.out.linesIterator.toSet == Set(
          "running foo",
          "running helperFoo",
          "running bar",
          "running helperBar",
          "running qux",
          "running helperQux"
        )
      )

      val cached = eval("outer.inner.qux")
      assert(cached.out == "")

      // Changing the body of a Task{...} block directly invalidates that task,
      // but not downstream tasks unless the return value changes
      modifyFile(workspacePath / "build.mill", _.replace("running foo", "running foo2"))
      val mangledFoo = eval("outer.inner.qux")
      assert(
        mangledFoo.out.linesIterator.toSet == Set(
          "running foo2",
          "running helperFoo"
          // The return value of foo did not change so qux is not invalidated
        )
      )

      modifyFile(workspacePath / "build.mill", _.replace("; helperFoo }", "; helperFoo + 4 }"))
      val mangledHelperFooCall = eval("outer.inner.qux")
      assert(
        mangledHelperFooCall.out.linesIterator.toSet == Set(
          "running foo2",
          "running helperFoo",
          // The return value of foo changes from 1 to 1+4=5, so qux is invalidated
          "running qux",
          "running helperQux"
        )
      )

      modifyFile(workspacePath / "build.mill", _.replace("running qux", "running qux2"))
      val mangledQux = eval("outer.inner.qux")
      assert(
        mangledQux.out.linesIterator.toSet ==
          // qux itself was changed, and so it is invalidated
          Set("running qux2", "running helperQux")
      )

      // Changing the body of some helper method that gets called by a Task{...}
      // block also invalidates the respective tasks, and downstream tasks if necessary

      modifyFile(workspacePath / "build.mill", _.replace(" 1 ", " 6 "))
      val mangledHelperFooValue = eval("outer.inner.qux")
      assert(
        mangledHelperFooValue.out.linesIterator.toSet == Set(
          "running foo2",
          "running helperFoo",
          // Because the return value of helperFoo/foo changes from 1+4=5 to 6+5=11, qux is invalidated
          "running qux2",
          "running helperQux"
        )
      )

      modifyFile(workspacePath / "build.mill", _.replace("running helperBar", "running helperBar2"))
      val mangledHelperBar = eval("outer.inner.qux")
      assert(
        mangledHelperBar.out.linesIterator.toSet == Set(
          "running bar",
          "running helperBar2"
          // We do not need to re-evaluate qux because the return value of bar did not change
        )
      )

      modifyFile(workspacePath / "build.mill", _.replace("20", "70"))
      val mangledHelperBarValue = eval("outer.inner.qux")
      assert(
        mangledHelperBarValue.out.linesIterator.toSet == Set(
          "running bar",
          "running helperBar2",
          // Because the return value of helperBar/bar changes from 20 to 70, qux is invalidated
          "running qux2",
          "running helperQux"
        )
      )

      modifyFile(workspacePath / "build.mill", _.replace("running helperQux", "running helperQux2"))
      val mangledBar = eval("outer.inner.qux")
      assert(
        mangledBar.out.linesIterator.toSet ==
          // helperQux was changed, so qux needs to invalidate
          Set("running qux2", "running helperQux2")
      )

      // Make sure changing `val`s in varying levels of nested modules conservatively invalidates
      // all tasks in inner modules, regardless of whether they are related or not
      modifyFile(workspacePath / "build.mill", _.replace("val valueFoo = 0", "val valueFoo = 10"))
      val mangledValFoo = eval("outer.inner.qux")
      assert(
        mangledValFoo.out.linesIterator.toSet == Set(
          "running foo2",
          "running helperFoo",
          "running bar",
          "running helperBar2",
          "running qux2",
          "running helperQux2"
        )
      )

      modifyFile(workspacePath / "build.mill", _.replace("val valueBar = 0", "val valueBar = 10"))
      val mangledValBar = eval("outer.inner.qux")
      assert(
        mangledValBar.out.linesIterator.toSet == Set(
          "running bar",
          "running helperBar2",
          "running qux2",
          "running helperQux2"
        )
      )

      modifyFile(workspacePath / "build.mill", _.replace("val valueQux = 0", "val valueQux = 10"))
      val mangledValQux = eval("outer.inner.qux")
      assert(
        mangledValQux.out.linesIterator.toSet == Set(
          "running qux2",
          "running helperQux2"
        )
      )

      modifyFile(
        workspacePath / "build.mill",
        _.replace("val valueFooUsedInBar = 0", "val valueFooUsedInBar = 10")
      )
      val mangledValFooUsedInBar = eval("outer.inner.qux")
      assert(
        mangledValFooUsedInBar.out.linesIterator.toSet == Set(
          "running foo2",
          "running helperFoo",
          "running bar",
          "running helperBar2",
          "running qux2",
          "running helperQux2"
        )
      )

      modifyFile(
        workspacePath / "build.mill",
        _.replace("val valueBarUsedInQux = 0", "val valueBarUsedInQux = 10")
      )
      val mangledValBarUsedInQux = eval("outer.inner.qux")
      assert(
        mangledValBarUsedInQux.out.linesIterator.toSet == Set(
          "running bar",
          "running helperBar2",
          "running qux2",
          "running helperQux2"
        )
      )

      // Adding a newline before one of the task definitions does not invalidate it
      modifyFile(workspacePath / "build.mill", _.replace("def qux", "\ndef qux"))
      val addedSingleNewline = eval("outer.inner.qux")
      assert(addedSingleNewline.out == "")

      modifyFile(workspacePath / "build.mill", _.replace("def", "\ndef"))
      val addedManyNewlines = eval("outer.inner.qux")
      assert(addedManyNewlines.out == "")

      // Reformatting the entire file, replacing `;`s with `\n`s and spacing out
      // the task bodies over multiple lines does not cause anything to invalidate
      modifyFile(
        workspacePath / "build.mill",
        _.replace("{", "{\n").replace("}", "\n}").replace(";", "\n")
      )
      val addedNewlinesInsideCurlies = eval("outer.inner.qux")
      assert(addedNewlinesInsideCurlies.out == "")
    }
  }
}

object CodeSigNestedTraitTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {
    test("trait") - integrationTest { tester =>
      import tester.*
      val initial = eval("traitOuter.traitInner.inner")
      assert(
        initial.out.linesIterator.toSet == Set(
          "running foo",
          "running helperFoo",
          "running outer",
          "running helperTraitOuter",
          "running inner",
          "running helperTraitInner"
        )
      )

      val cached = eval("traitOuter.traitInner.inner")
      assert(cached.out == "")

      modifyFile(
        workspacePath / "build.mill",
        _.replace("val valueTraitInner = 0", "val valueTraitInner = 10")
      )
      val mangleTraitInnerValue = eval("traitOuter.traitInner.inner")
      assert(
        mangleTraitInnerValue.out.linesIterator.toSet == Set(
          "running inner",
          "running helperTraitInner"
        )
      )

      modifyFile(
        workspacePath / "build.mill",
        _.replace("val valueTraitOuter = 0", "val valueTraitOuter = 10")
      )
      val mangleTraitOuterValue = eval("traitOuter.traitInner.inner")
      assert(
        mangleTraitOuterValue.out.linesIterator.toSet == Set(
          "running outer",
          "running helperTraitOuter",
          "running inner",
          "running helperTraitInner"
        )
      )
    }
  }
}
