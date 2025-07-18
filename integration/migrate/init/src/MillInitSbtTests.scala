package mill.integration

import utest.*

object MillInitSbtAirstreamTests extends MillInitMigrateTestSuite:
  def tests = Tests:
    test - integrationTestGitRepo("https://github.com/raquo/Airstream", "v17.2.0"):
      tester =>
        tester.eval("init", stdout = os.Inherit, stderr = os.Inherit)

        val resolveRes = tester.eval(("resolve", "_"))
        val topLevelModules = Seq("[2.13.15]", "[3.3.3]")
        for module <- topLevelModules
        do assert(resolveRes.out.linesIterator.contains(module))

        // compilation hangs
        // tester.eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
  end tests

object MillInitSbtFs2Tests extends MillInitMigrateTestSuite:
  def tests = Tests:
    test - integrationTestGitRepo("https://github.com/typelevel/fs2", "v3.12.0"):
      tester =>
        tester.eval("init", stdout = os.Inherit, stderr = os.Inherit)

        val resolveRes = tester.eval(("resolve", "_"))
        val topLevelModules = Seq(
          "benchmark",
          "core",
          "integration",
          "io",
          "mdoc",
          "protocols",
          "reactive-streams",
          "scodec",
          "unidocs"
        )
        for module <- topLevelModules
        do assert(resolveRes.out.linesIterator.contains(module))

        // compilation hangs
        // tester.eval("__.compile", stdout = os.Inherit, stderr = os.Inherit).isSuccess ==> true
  end tests
