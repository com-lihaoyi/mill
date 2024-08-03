package mill.integration

import utest._

object ImportIvyIversions extends IntegrationTestSuite {

  val tests: Tests = Tests {
    test {
      val wsRoot = initWorkspace()
      val evaled = evalStdout("show", "getUpickleVersion")
      assert(evalStdout("show", "getUpickleVersion").out.contains ("upickle_2.13-3.3.1.jar"))
      mangleFile(wsRoot / "build.sc", "import $ivy.`com.lihaoyi::upickle:3.3.0`\n" + _)
      assert(evalStdout("show", "getUpickleVersion").out.contains ("upickle_2.13-3.3.1.jar"))
      mangleFile(wsRoot / "build.sc", _.replace("3.3.0", "4.0.0-RC1"))
      assert(evalStdout("show", "getUpickleVersion").out.contains ("upickle_2.13-3.3.1.jar"))

    }
  }
}
