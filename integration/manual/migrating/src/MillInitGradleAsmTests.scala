package mill.integration
import utest.*
object MillInitGradleAsmTests extends MillInitTestSuite {
  def tests = Tests {
    test - checkImport(
      gitUrl = "https://gitlab.ow2.org/asm/asm.git",
      gitBranch = "ASM_9_8",
      initArgs = Seq("--gradle-jvm-id", "11"),
      passingTasks = Seq("asm.compile"),
      failingTasks = Seq("tools.retrofitter.compile")
    )
  }
}
