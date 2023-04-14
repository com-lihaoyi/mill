package mill.integration

import utest._

object CodeSigSimpleTests extends IntegrationTestSuite {
  val tests = Tests {
    val wsRoot = initWorkspace()
    "test" - {
      println("=" * 150 + " INITIAL")
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
      pprint.log(initial.out)
      println("=" * 150 + " CACHED")
      val cached = evalStdout("qux")
      pprint.log(cached.out)
      assert(cached.out == "")

      mangleFile(wsRoot / "build.sc", _.replace("running helperFoo", "running helperFoo2"))
      val mangledFoo = evalStdout("qux")

      assert(
        mangledFoo.out ==
          """running foo
            |running helperFoo2
            |running qux
            |running helperQux""".stripMargin
      )

      mangleFile(wsRoot / "build.sc", _.replace("running helperBar", "running helperBar2"))
      val mangledBar = evalStdout("qux")

      assert(
        mangledBar.out ==
          """running bar
            |running helperBar2
            |running qux
            |running helperQux""".stripMargin
      )

      mangleFile(wsRoot / "build.sc", _.replace("def qux", "\ndef qux"))
      val addedSingleNewline = evalStdout("qux")
      pprint.log(addedSingleNewline.out)
      assert(addedSingleNewline.out == "")


      mangleFile(wsRoot / "build.sc", _.replace("def", "\ndef"))
      val addedManyNewlines = evalStdout("qux")
      pprint.log(addedManyNewlines.out)
      assert(addedManyNewlines.out == "")

      os.copy.over(
        os.pwd/os.SubPath("out/integration/feature/codesig-simple/local/workspaceDir.dest/out/mill-build/compile.dest/classes/millbuild/build.class"),
        os.pwd / "build-old.class"
      )

      mangleFile(wsRoot / "build.sc", _.replace("{", "{\n").replace("}", "\n}"))
      val addedNewlinesInsideCurlies = evalStdout("qux")
      pprint.log(addedNewlinesInsideCurlies.out)
      os.copy.over(
        os.pwd/os.SubPath("out/integration/feature/codesig-simple/local/workspaceDir.dest/out/mill-build/compile.dest/classes/millbuild/build.class"),
        os.pwd / "build-new.class"
      )
      assert(addedNewlinesInsideCurlies.out == "")

    }
  }
}
