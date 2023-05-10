package mill.integration

import utest._

object CodeSigSimpleTests extends IntegrationTestSuite {
  val tests = Tests {
    val wsRoot = initWorkspace()
    "test" - {
      // Check normal behavior for initial run and subsequent fully-cached run
      // with no changes
      val initial = evalStdout("qux")

      assert(
        initial.out ==
        """running foo
          |running helperFoo
          |running bar
          |running helperBar
          |running qux
          |running helperQux""".stripMargin
      )

      val cached = evalStdout("qux")
      assert(cached.out == "")

      // Changing the body of a T{...} block directly invalidates that target
      // and any downstream targets
      mangleFile(wsRoot / "build.sc", _.replace("running foo", "running foo2"))
      val mangledFoo = evalStdout("qux")

      assert(
        mangledFoo.out ==
        """running foo2
          |running helperFoo
          |running qux
          |running helperQux""".stripMargin
      )

      mangleFile(wsRoot / "build.sc", _.replace("running qux", "running qux2"))
      val mangledQux = evalStdout("qux")
      assert(
        mangledQux.out ==
        """running qux2
          |running helperQux""".stripMargin
      )

      // Changing the body of some helper method that gets called by a T{...}
      // block also invalidates the respective targets
      mangleFile(wsRoot / "build.sc", _.replace("running helperBar", "running helperBar2"))
      val mangledHelperBar = evalStdout("qux")
      assert(
        mangledHelperBar.out ==
        """running bar
          |running helperBar2
          |running qux2
          |running helperQux""".stripMargin
      )

      mangleFile(wsRoot / "build.sc", _.replace("running helperQux", "running helperQux2"))
      val mangledBar = evalStdout("qux")

      assert(
        mangledBar.out ==
        """running qux2
          |running helperQux2""".stripMargin
      )

      // Adding a newline before one of the target definitions does not invalidate it
      mangleFile(wsRoot / "build.sc", _.replace("def qux", "\ndef qux"))
      val addedSingleNewline = evalStdout("qux")
      assert(addedSingleNewline.out == "")


      mangleFile(wsRoot / "build.sc", _.replace("def", "\ndef"))
      val addedManyNewlines = evalStdout("qux")
      assert(addedManyNewlines.out == "")


      // Reformatting the entire file, replacing `;`s with `\n`s and spacing out
      // the target bodies over multiple lines does not cause anything to invalidate
      mangleFile(
        wsRoot / "build.sc",
        _.replace("{", "{\n").replace("}", "\n}").replace(";", "\n")
      )
      val addedNewlinesInsideCurlies = evalStdout("qux")
      assert(addedNewlinesInsideCurlies.out == "")

      mangleFile(
        wsRoot / "build.sc",
        _.replace("import mill._", "import mill.T; import java.util.Properties")
      )
      val mangledImports = evalStdout("qux")
      assert(mangledImports.out == "")
    }
  }
}
