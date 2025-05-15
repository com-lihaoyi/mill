package mill.spotless

import coursier.Resolve
import coursier.parse.DependencyParser
import mill.util.Jvm
import utest.*
import utest.framework.TestPath

object SpotlessApiTest extends TestSuite {

  def tests = Tests {
    // sources copied from https://github.com/diffplug/spotless/tree/main/testlib/src/main/resources
    test("step") {
      test("generic") {
        test("fence") {
          testProject("generic/fence")
        }
        test("licenseheader") {
          testProject("generic/licenseheader")
        }
      }
      test("java") {
        test("formatannotations") {
          testProject("java/formatannotations")
        }
        test("palantirjavaformat") {
          testProject("java/palantirjavaformat")
        }
      }
      test("kotlin") {
        test("ktfmt") {
          testProject("kotlin/ktfmt")
        }
        test("ktlint") {
          testProject("kotlin/ktlint", formatReturns = 1)
        }
      }
      test("scala") {
        test("scalafmt") {
          testProject("scala/scalafmt")
        }
      }
    }
    test("lintSuppression") {
      testProject("lintsuppression", checkReturns = 0)
    }
  }

  def testProject(projectRoot: os.SubPath, checkReturns: Int = 1, formatReturns: Int = 0)(using
      TestPath
  ): Unit = {
    val resources = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
    val unformatted = resources / "unformatted" / projectRoot
    TestUtil.sandbox(unformatted) {
      val workspace = os.pwd
      val workspaceSrc = workspace / "src"
      val files = os.walk.stream(workspaceSrc).filter(os.isFile).toSeq
      var check = true
      val onFormat: os.Path => Unit =
        path => if check then throw Exception(s"$path was formatted on check")
      val api = SpotlessApi.create(
        spotlessConfig = workspace / "spotless-config.json",
        configRoots = Seq(workspace),
        provision = provision
      )
      assert(
        api.format(workspace, files, check, onFormat) == checkReturns,
        TestUtil.diff(unformatted / "src", workspaceSrc) == 0
      )
      check = false
      assert(
        api.format(workspace, files, check, onFormat) == formatReturns,
        formatReturns != 0 ||
          TestUtil.diff(resources / "formatted" / projectRoot, workspaceSrc) == 0
      )
    }
  }

  private val provision = (_: Boolean, mavenCoordinates: Seq[String]) => {
    val dependencies = DependencyParser.dependencies(mavenCoordinates, "")
      .either
      .fold(errs => throw Exception(errs.mkString(", ")), identity)
    Jvm.getArtifacts(
      Resolve.defaultRepositories,
      dependencies,
      checkGradleModules = false
    ).get.files.toSet
  }
}
