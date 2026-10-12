package mill.integration

import mill.testkit.UtestIntegrationTestSuite

import utest.*

/** Test YAML !append tag feature. */
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
    test("root.configClass") - check("configClass", Seq("A"), Seq("B"))

    test("foo.configSeq") - check("foo.configSeq", Seq("B"), Seq("A"))
    test("foo.configClass") - check("foo.configClass", Seq("B"), Seq("A"))

    test("bar.configSeq") - check("bar.configSeq", Seq("A", "B"), Seq())
    test("bar.configClass") - check("bar.configClass", Seq("A", "B"), Seq())

  }
}
