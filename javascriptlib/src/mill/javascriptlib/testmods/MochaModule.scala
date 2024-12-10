package mill.javascriptlib.testmods

import mill.*
import os.*

trait MochaModule extends TestModule {
  override def npmDevDeps: T[Seq[String]] = Task {
    Seq(
      "@types/chai@4.3.1",
      "@types/mocha@9.1.1",
      "chai@4.3.6",
      "mocha@10.0.0"
    )
  }

  // test-runner.js: run tests on ts files
  private def testRunnerBuilder: Task[Path] = Task.Anon {
    val compiled = compile()._1.path
    val testRunner = compiled / "test-runner.js"

    val content =
      """|require('node_modules/ts-node/register');
         |require('node_modules/mocha/bin/_mocha');
         |""".stripMargin

    os.write(testRunner, content)

    testRunner
  }

  override def getPathToTest: T[String] = Task { (compile()._2.path / "test").toString + "/**/*.test.ts" }

  // node test-runner.js path/to/test
  override def test: T[CommandResult] = Task {
    os.call(
      (
        "node",
        testRunnerBuilder(),
        getPathToTest()
      ),
      stdout = os.Inherit,
      env = mkENV(),
      cwd = compile()._1.path
    )
  }
}
