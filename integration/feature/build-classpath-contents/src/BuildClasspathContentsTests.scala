package mill.integration

import mill.testkit.UtestIntegrationTestSuite
import mill.define.WorkspaceRoot.workspaceRoot
import utest._

object BuildClasspathContentsTests extends UtestIntegrationTestSuite {

  val tests: Tests = Tests {
    test("test") - integrationTest { tester =>
      val result1 = tester.eval(("--meta-level", "1", "show", "runClasspath"))
      val deserialized = upickle.default.read[Seq[mill.define.PathRef]](result1.out)
      val simplified = deserialized
        .map(_.path)
        .filter(_.startsWith(workspaceRoot))
        .map(_.subRelativeTo(workspaceRoot))
        .filter(!_.startsWith("out/integration"))
        .sortBy(_.toString)
      pprint.log(simplified)

      val expected: Seq[os.SubPath] = List(
        "out/bsp/buildInfoResources.dest",
        "out/bsp/compile.dest/classes",
        "out/core/api/buildInfoResources.dest",
        "out/core/api/compile.dest/classes",
        "out/core/compile.dest/classes",
        "out/core/constants/buildInfoResources.dest",
        "out/core/constants/compile.dest/classes",
        "out/core/define/compile.dest/classes",
        "out/core/exec/compile.dest/classes",
        "out/core/internal/compile.dest/classes",
        "out/core/resolve/compile.dest/classes",
        "out/dist/localTestOverridesClasspath.dest",
        "out/idea/compile.dest/classes",
        "out/javascriptlib/compile.dest/classes",
        "out/kotlinlib/buildInfoResources.dest",
        "out/kotlinlib/compile.dest/classes",
        "out/kotlinlib/worker/compile.dest/classes",
        "out/main/compile.dest/classes",
        "out/main/init/buildgen/compile.dest/classes",
        "out/main/init/compile.dest/classes",
        "out/main/init/exampleList.dest",
        "out/main/util/buildInfoResources.dest",
        "out/main/util/compile.dest/classes",
        "out/pythonlib/compile.dest/classes",
        "out/scalajslib/buildInfoResources.dest",
        "out/scalajslib/compile.dest/classes",
        "out/scalajslib/worker-api/compile.dest/classes",
        "out/scalalib/api/buildInfoResources.dest",
        "out/scalalib/api/compile.dest/classes",
        "out/scalalib/compile.dest/classes",
        "out/scalanativelib/compile.dest/classes",
        "out/scalanativelib/worker-api/compile.dest/classes",
        "out/testrunner/compile.dest/classes",
        "out/testrunner/entrypoint/compile.dest/classes",
        "scalalib/resources"
      )

      assert(simplified == expected)
    }
  }
}
