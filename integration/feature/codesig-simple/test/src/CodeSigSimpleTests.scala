package mill.integration

import utest._

object CodeSigSimpleTests extends IntegrationTestSuite {
  val tests = Tests {
    val wsRoot = initWorkspace()
    "test" - {
      val initial = evalStdout("baz")

      assert(
        initial.out ==
          """running helperBar
            |running constant
            |running foo
            |running helperFoo
            |running transitiveHelperFoo
            |running bar
            |running qux
            |running helperQux
            |running baz
            |running HelperBaz.help""".stripMargin
      )

      val cached = evalStdout("baz")
      assert(cached.out == "")

      mangleFile(wsRoot / "build.sc", _.replace("running transitiveHelperFoo", "running transitiveHelperFoo2"))
      val editTransitiveHelperFoo = evalStdout("baz")

      pprint.log(editTransitiveHelperFoo.out)
//      assert(!initial.out.linesIterator.toSet.contains("running foo"))
//      assert(!initial.out.linesIterator.toSet.contains("running bar"))
//      assert(!initial.out.linesIterator.toSet.contains("running qux"))
//      assert(!initial.out.linesIterator.toSet.contains("running baz"))
    }
  }
}
