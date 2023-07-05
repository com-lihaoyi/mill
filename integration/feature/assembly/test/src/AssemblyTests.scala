package mill.integration.local

import mill.integration.IntegrationTestSuite
import mill.util.Jvm
import utest._

// Ensure the assembly is runnable, even if we have assembled lots of dependencies into it
// Reproduction of issues:
// - https://github.com/com-lihaoyi/mill/issues/528
// - https://github.com/com-lihaoyi/mill/issues/2650

object AssemblyTests extends IntegrationTestSuite {
  def tests: Tests = Tests {
    test("Assembly") {
      test("without-prependShellScript") {
        val workspacePath = initWorkspace()
        assert(eval("foo.assembly"))
        val assemblyFile = workspacePath / "out" / "foo" / "assembly.dest" / "out.jar"
        assert(os.exists(assemblyFile))
        println(s"File size: ${os.stat(assemblyFile).size}")
        Jvm.runSubprocess(
          commandArgs = Seq(Jvm.javaExe, "-jar", assemblyFile.toString(), "--text", "tutu"),
          envArgs = Map.empty[String, String],
          workingDir = workspacePath
        )
      }
      test("with-prependShellScript") {
        val workspacePath = initWorkspace()
        assert(eval("bar.assembly"))
        val assemblyFile = workspacePath / "out" / "bar" / "assembly.dest" / "out.jar"
        assert(os.exists(assemblyFile))
        println(s"File size: ${os.stat(assemblyFile).size}")
        Jvm.runSubprocess(
          commandArgs = Seq(Jvm.javaExe, "-jar", assemblyFile.toString(), "--text", "tutu"),
          envArgs = Map.empty[String, String],
          workingDir = workspacePath
        )
      }
    }
  }
}
