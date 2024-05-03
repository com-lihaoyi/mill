package mill.integration.local

import mill.api.IO
import mill.integration.IntegrationTestSuite
import mill.util.Jvm
import utest._

// Ensure the assembly is runnable, even if we have assembled lots of dependencies into it
// Reproduction of issues:
// - https://github.com/com-lihaoyi/mill/issues/528
// - https://github.com/com-lihaoyi/mill/issues/2650

object AssemblyTests extends IntegrationTestSuite {

  def checkAssembly(
      targetSegments: Seq[String],
      checkExe: Boolean = false,
      failMsg: Option[String] = None
  ): Unit = {
    val workspacePath = initWorkspace()
    val targetName = "assembly"
    val res = evalStdout(targetSegments.mkString(".") + "." + targetName)

    assert(res.isSuccess == failMsg.isEmpty)

    failMsg match {
      case None =>
        val assemblyFile =
          workspacePath / "out" / targetSegments / s"${targetName}.dest" / "out.jar"
        assert(os.exists(assemblyFile))
        println(s"File size: ${os.stat(assemblyFile).size}")

        Jvm.runSubprocess(
          commandArgs = Seq(Jvm.javaExe, "-jar", assemblyFile.toString(), "--text", "tutu"),
          envArgs = Map.empty[String, String],
          workingDir = workspacePath
        )

        if (checkExe) {
          Jvm.runSubprocess(
            commandArgs = Seq(assemblyFile.toString(), "--text", "tutu"),
            envArgs = Map.empty[String, String],
            workingDir = workspacePath
          )
        }
      case Some(msg) =>
        assert((res.out ++ res.err).contains(msg))
    }
  }

  def tests: Tests = Tests {
    test("Assembly") {
      test("noExe") {
        test("small") {
          checkAssembly(Seq("noExe", "small"))
        }
        test("large") {
          checkAssembly(Seq("noExe", "large"))
        }
      }
      test("exe") {
        test("small") {
          checkAssembly(Seq("exe", "small"), checkExe = true)
        }
        test("large") {
          checkAssembly(
            Seq("exe", "large"),
            failMsg = Some(
              """exe.large.assembly The created assembly would contain more than 65535 ZIP entries.
                |JARs of that size are known to not work correctly with a prepended shell script.
                |Either reduce the entries count of the assembly or disable the prepended shell script with:
                |
                |  def prependShellScript = ""
                |""".stripMargin
            )
          )
        }
      }
    }
  }
}
