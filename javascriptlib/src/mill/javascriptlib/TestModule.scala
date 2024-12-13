package mill.javascriptlib

import mill.*
import os.*

trait TestModule extends TaskModule {
  import TestModule.TestResult

  def test(args: String*): Command[TestResult] =
    Task.Command {
      testTask(Task.Anon { args })()
    }

  def testCachedArgs: T[Seq[String]] = Task { Seq[String]() }

  def testCached: T[Unit] = Task {
    testTask(testCachedArgs)()
  }

  protected def testTask(args: Task[Seq[String]]): Task[TestResult]

  override def defaultCommandName() = "test"
}

object TestModule {
  type TestResult = Unit

  trait Shared extends TypeScriptModule {
    override def upstreamPathsBuilder: T[Seq[(String, String)]] =
      Task {
        val stuUpstreams = for {
          ((_, ts), mod) <- Task.traverse(moduleDeps)(_.compile)().zip(moduleDeps)
        } yield (
          mod.millSourcePath.subRelativeTo(Task.workspace).toString + "/test/utils/*",
          (ts.path / "test/src/utils").toString
        )

        stuUpstreams ++ super.upstreamPathsBuilder()
      }

    def getPathToTest: T[String] = Task { compile()._2.path.toString }
  }

  trait Jest extends TypeScriptModule with Shared with TestModule {
    override def npmDevDeps: T[Seq[String]] = Task {
      Seq(
        "@types/jest@^29.5.14",
        "@babel/core@^7.26.0",
        "@babel/preset-env@^7.26.0",
        "jest@^29.7.0",
        "ts-jest@^29.2.5",
        "babel-jest@^29.7.0"
      )
    }

    def testConfigSource: T[PathRef] =
      Task.Source(Task.workspace / "jest.config.ts")

    override def allSources: T[IndexedSeq[PathRef]] = Task {
      super.allSources() ++ IndexedSeq(testConfigSource())
    }

    override def compilerOptions: T[Map[String, ujson.Value]] =
      Task { super.compilerOptions() + ("resolveJsonModule" -> ujson.Bool(true)) }

    def getConfigFile: T[String] =
      Task { (compile()._1.path / "jest.config.ts").toString }

    private def copyConfig: Task[TestResult] = Task.Anon {
      os.copy.over(
        testConfigSource().path,
        compile()._1.path / "jest.config.ts"
      )
    }

    private def runTest: T[TestResult] = Task {
      copyConfig()
      os.call(
        (
          "node",
          npmInstall().path / "node_modules/jest/bin/jest.js",
          "--config",
          getConfigFile(),
          getPathToTest()
        ),
        stdout = os.Inherit,
        env = mkENV(),
        cwd = compile()._1.path
      )
      ()
    }

    protected def testTask(args: Task[Seq[String]]): Task[TestResult] = Task.Anon {
      runTest()
    }

  }

  trait Mocha extends TypeScriptModule with Shared with TestModule {
    override def npmDevDeps: T[Seq[String]] = Task {
      Seq(
        "@types/chai@4.3.1",
        "@types/mocha@9.1.1",
        "chai@4.3.6",
        "mocha@10.0.0"
      )
    }

    override def getPathToTest: T[String] =
      Task { super.getPathToTest() + "/**/**/*.test.ts" }

    // test-runner.js: run tests on ts files
    private def testRunnerBuilder: Task[Path] = Task.Anon {
      val compiled = compile()._1.path
      val testRunner = compiled / "test-runner.js"

      val content =
        """|require('node_modules/ts-node/register');
           |require('tsconfig-paths/register');
           |require('node_modules/mocha/bin/_mocha');
           |""".stripMargin

      os.write(testRunner, content)

      testRunner
    }

    private def runTest: T[Unit] = Task {
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
      ()
    }

    protected def testTask(args: Task[Seq[String]]): Task[TestResult] = Task.Anon {
      runTest()
    }

  }
}
