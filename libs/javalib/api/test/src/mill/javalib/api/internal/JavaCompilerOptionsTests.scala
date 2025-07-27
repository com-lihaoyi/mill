package mill.javalib.api.internal

import utest.*

object JavaCompilerOptionsTests extends TestSuite {
  val tests = Tests {
    test("apply") {
      val options = JavaCompilerOptions(Seq(
        "1",
        "2",
        "-J-Xmx1g",
        "-J-Dfoo=bar",
        "3",
//        "-XDcompilePolicy=simple",
//        "-processorpath", "foo",
//        "-Xplugin:ErrorProne -XepAllErrorsAsWarnings",
        "4"
      ))

      assert(
        options.runtime == JavaRuntimeOptions(Seq(
          "-Xmx1g",
          "-Dfoo=bar"
//          "-XDcompilePolicy=simple",
//          "-processorpath", "foo",
//          "-Xplugin:ErrorProne -XepAllErrorsAsWarnings"
        ))
      )

      assert(options.compiler == JavaCompilerOptions.unsafe(Seq("1", "2", "3", "4")))
    }
  }
}
