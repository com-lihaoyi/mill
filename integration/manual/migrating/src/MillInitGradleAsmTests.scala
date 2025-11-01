package mill.integration
import utest.*
object MillInitGradleAsmTests extends MillInitTestSuite {
  def tests = Tests {
    test("realistic") - checkImport(
      "https://gitlab.ow2.org/asm/asm.git",
      "ASM_9_9",
      passingTasks = Seq("asm.compile"),
      failingTasks = Seq(
        // requires custom source dir
        "tools.retrofitter.compile"
      )
    )
  }
}
