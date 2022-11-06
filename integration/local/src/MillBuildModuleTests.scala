package mill.integration

import mill.scalalib.buildfile.MillBuildModule
import mill.util.ScriptTestSuite
import os.Path
import utest.{Tests, assert, test}

class MillBuildModuleTests(fork: Boolean, clientServer: Boolean)
    extends IntegrationTestSuite("mill-build-module", fork, clientServer) {

  val ExtMod = MillBuildModule.millModuleSegments.render

  def cleanRefs(string: String): String = string.replaceAll("[\"]ref:[0-9a-f]{8}:", "\"")

  def checkShow(target: String, expected: String) = {
    val (true, out) = evalStdout("show", s"${ExtMod}/${target}")
    val expectedString = expected.trim()
    val outString = cleanRefs(out.mkString("\n")).trim()
    assert(outString == expectedString, out.nonEmpty)
  }

  val tests = Tests {
    test("Mill itself") {
      initWorkspace()
      checkShow("millVersion", """"0.10.7"""")
      checkShow(
        "sources",
        s"""[
          |  "${workspacePath}/.mill-version",
          |  "${workspacePath}/.config/mill-version",
          |  "${workspacePath}/.mill-jvm-opts",
          |  "${workspacePath}/build.sc",
          |  "${workspacePath}/ci/shared.sc",
          |  "${workspacePath}/ci/upload.sc"
          |]
          |""".stripMargin
      )
    }

  }
}

//object MillBuildModuleTests extends ScriptTestSuite(fork = false, clientServer = true) {
//  override def workspaceSlug: String = "mill-build-module"
//  override def scriptSourcePath: Path = os.pwd / "scalalib" / "test" / "resources" / workspaceSlug
//
//  val ExtMod = MillBuildModule.millModuleSegments.render
//
//  def cleanRefs(string: String): String = string.replaceAll("[\"]ref:[0-9a-e]{8}:", "\"")
//
//  override def tests: Tests = Tests {
//    test("External Module") {
//      test("Legacy Mill self") {
//
//        def checkShow(target: String, expected: String) = {
//          initWorkspace()
//          val (true, out) = evalStdout("show", s"${ExtMod}/${target}")
//          val outString = cleanRefs(out.mkString("\n"))
//          assert(outString == expected, out.nonEmpty)
//        }
//
//        test("millVersion") { checkShow("millVersion", """"0.10.7"""") }
//        test("millVersion") {
//          checkShow(
//            "sources",
//            """[
//              |  ""
//              |]""".stripMargin
//          )
//        }
//      }
//    }
//  }
//}
