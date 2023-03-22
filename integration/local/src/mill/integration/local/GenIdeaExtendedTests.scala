package mill.integration
package local

import os.Path
import utest._

import scala.util.Try

object GenIdeaExtendedTests extends ScriptTestSuite(false) {

  override def workspaceSlug: String = "gen-idea-extended-hello-world"

  override def scriptSourcePath: Path =
    os.pwd / "integration" / "resources" / workspaceSlug

  private val scalaVersionLibPart = "2_13_6"

  def tests: Tests = Tests {
    "genIdeaTests" - {
      val workspacePath = initWorkspace()
      eval("mill.scalalib.GenIdea/idea")

      val checks = Seq(
        os.sub / "mill_modules" / "helloworld.iml",
        os.sub / "mill_modules" / "helloworld.test.iml",
        os.sub / "mill_modules" / "helloworld.subscala3.iml",
        os.sub / "mill_modules" / "mill-build.iml",
        os.sub / "libraries" / s"scala_library_${scalaVersionLibPart}_jar.xml",
        //NOTE: on IntelliJ Scala Plugin side there is a cosmetic issue: scala suffix is added even for Java libraries (notice `_2_13` suffix)
        //In future it might be fixed and `GenIdea` will need to be updated
        os.sub / "libraries" / "SBT_ junit_junit_2_13_4_13_2_jar.xml",
        os.sub / "libraries" / "SBT_ org_scalameta_munit_2_13_0_7_29_jar.xml",
        os.sub / "modules.xml",
        os.sub / "misc.xml",
        os.sub / "compiler.xml"
      ).map { resource =>
        Try {
          GenIdeaTests.assertIdeaXmlResourceMatchesFile(
            workspaceSlug,
            workspacePath,
            resource
          )
        }
      }
      assert(checks.forall(_.isSuccess))
    }
  }

}
