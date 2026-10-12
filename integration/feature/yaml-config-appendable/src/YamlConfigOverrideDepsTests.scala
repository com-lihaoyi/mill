package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

// Regression test for https://github.com/com-lihaoyi/mill/issues/7083: a task
// whose value is supplied entirely by a non-`!append` YAML override must not
// execute its declared dependencies, since the original task body never runs.
object YamlConfigOverrideDepsTests extends UtestIntegrationTestSuite {
  val tests: Tests = Tests {

    def check(
        cmd: os.Shellable,
        contains: Seq[String],
        containsNot: Seq[String]
    ): Unit = integrationTest { tester =>
      val res = tester.eval(("show", cmd))
      assert(res.isSuccess)
      val resLines = res.out.linesIterator.toSeq.map(_.trim)

      contains.map(s => "\"" + s + "\"").foreach { line =>
        assert(resLines.contains(line))
      }

      containsNot.map(s => "\"" + s + "\"").foreach { line =>
        assert(!resLines.contains(line))
      }
    }

    test("root.configSeq") - check("configSeq", Seq("A"), Seq("B"))
    test("root.configTask") - check("configTask", Seq("A"), Seq("B"))
    test("root.configTaskExt") - check("configTaskExt", Seq("A"), Seq("B"))

    test("foo.configSeq") - check("foo.configSeq", Seq("B"), Seq("A"))
    test("foo.configTask") - check("foo.configTask", Seq("B"), Seq("A"))
    test("foo.configTaskExt") - check("foo.configTaskExt", Seq("B"), Seq("A"))

    test("bar.configSeq") - check("bar.configSeq", Seq("B"), Seq("A"))
    test("bar.configTask") - check("bar.configTask", Seq("B"), Seq("A"))
    test("bar.configTaskExt") - check("bar.configTaskExt", Seq("C"), Seq("A", "B"))

    test("baz.configSeq") - check("baz.configSeq", Seq("A", "B"), Seq("C"))
    test("baz.configTask") - check("baz.configTask", Seq("A", "B"), Seq("C"))
    test("baz.configTaskExt") - check("baz.configTaskExt", Seq("C"), Seq("A", "B"))

    //    test("baz") - integrationTest { tester =>
    //      {
    //        val res = tester.eval(("show", "baz.configTask"))
    //        assert(res.isSuccess)
    //        val lines = res.out.linesIterator.toSeq
    //        assert(lines.exists(_.contains(""""A"""")))
    //        assert(lines.exists(_.contains(""""B"""")))
    //        assert(!lines.exists(_.contains(""""C"""")))
    //      }
    //      {
    //        val res = tester.eval(("show", "baz.configTaskExt"))
    //        assert(res.isSuccess)
    //        val lines = res.out.linesIterator.toSeq
    //        assert(lines.exists(_.contains(""""C"""")))
    //        assert(!lines.exists(_.contains(""""A"""")))
    //        assert(!lines.exists(_.contains(""""B"""")))
    //      }
    //    }

  }
}
