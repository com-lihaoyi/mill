package mill.integration

import utest._

object CodeSigSimpleTests extends IntegrationTestSuite {
  val tests = Tests {
    val wsRoot = initWorkspace()
    "test" - {
      println("=" * 150 + " INITIAL")
      val initial = evalStdout("qux")

//      assert(
//        initial.out ==
//          """running foo
//            |running helperFoo
//            |running bar
//            |running helperBar
//            |running qux
//            |running helperQux""".stripMargin
//      )
      pprint.log(initial.out)
      println("=" * 150 + " CACHED")
      val cached = evalStdout("qux")
      pprint.log(cached.out)
//      assert(cached.out == "")

      println("=" * 150 + " ADDED NEWLINE")
      mangleFile(wsRoot / "build.sc", _.replace("def qux", "\ndef qux"))
      val addedNewlines = evalStdout("qux")
      pprint.log(addedNewlines.out)
      assert(addedNewlines.out == "")
//
//      mangleFile(wsRoot / "build.sc", _.replace("running helperFoo", "running helperFoo2"))
//      val mangledFoo = evalStdout("qux")
//
//      assert(
//        mangledFoo.out ==
//          """running foo
//            |running helperFoo2
//            |running qux
//            |running helperQux""".stripMargin
//      )
//
//      mangleFile(wsRoot / "build.sc", _.replace("running helperBar", "running helperBar2"))
//      val mangledBar = evalStdout("qux")
//
//      assert(
//        mangledBar.out ==
//          """running bar
//            |running helperBar2
//            |running qux
//            |running helperQux""".stripMargin
//      )

    }
  }
}
