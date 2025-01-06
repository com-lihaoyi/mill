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

  trait IntegrationSuite extends TypeScriptModule {
    def service: TypeScriptModule

    def port: T[String]
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
        env = forkEnv(),
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
        env = forkEnv(),
        cwd = compile()._1.path
      )
      ()
    }

    protected def testTask(args: Task[Seq[String]]): Task[TestResult] = Task.Anon {
      runTest()
    }

  }

  trait Vitest extends TypeScriptModule with Shared with TestModule {
    override def npmDevDeps: T[Seq[String]] =
      Task {
        Seq(
          "@vitest/runner@2.1.8",
          "vite@5.4.11",
          "vite-tsconfig-paths@3.6.0",
          "vitest@2.1.8"
        )
      }

    def testConfigSource: T[PathRef] =
      Task.Source(Task.workspace / "vite.config.ts")

    override def compilerOptions: T[Map[String, ujson.Value]] =
      Task {
        super.compilerOptions() + (
          "target" -> ujson.Str("ESNext"),
          "module" -> ujson.Str("ESNext"),
          "moduleResolution" -> ujson.Str("Node"),
          "skipLibCheck" -> ujson.Bool(true),
          "types" -> ujson.Arr(
            s"${npmInstall().path}/node_modules/vitest/globals"
          )
        )
      }

    def getConfigFile: T[String] =
      Task { (compile()._1.path / "vite.config.ts").toString }

    private def copyConfig: Task[Unit] = Task.Anon {
      os.copy.over(
        testConfigSource().path,
        compile()._1.path / "vite.config.ts"
      )
    }

    private def runTest: T[TestResult] = Task {
      copyConfig()
      os.call(
        (
          "node",
          npmInstall().path / "node_modules/.bin/vitest",
          "--run",
          "--config",
          getConfigFile(),
          getPathToTest()
        ),
        stdout = os.Inherit,
        env = forkEnv(),
        cwd = compile()._1.path
      )
      ()
    }

    protected def testTask(args: Task[Seq[String]]): Task[TestResult] = Task.Anon {
      runTest()
    }

  }

  trait Jasmine extends TypeScriptModule with Shared with TestModule {
    override def npmDevDeps: T[Seq[String]] =
      Task {
        Seq(
          "@types/jasmine@5.1.2",
          "jasmine@5.1.0",
          "ts-node@10.9.1",
          "tsconfig-paths@4.2.0",
          "typescript@5.2.2"
        )
      }

    override def compilerOptions: T[Map[String, ujson.Value]] =
      Task {
        super.compilerOptions() + (
          "target" -> ujson.Str("ES5"),
          "module" -> ujson.Str("commonjs"),
          "moduleResolution" -> ujson.Str("node"),
          "allowJs" -> ujson.Bool(true)
        )
      }

    def configBuilder: T[PathRef] = Task {
      val path = compile()._1.path / "jasmine.json"
      os.write(
        path,
        ujson.write(
          ujson.Obj(
            "spec_dir" -> ujson.Str("typescript/src"),
            "spec_files" -> ujson.Arr(ujson.Str("**/*.test.ts")),
            "stopSpecOnExpectationFailure" -> ujson.Bool(false),
            "random" -> ujson.Bool(false)
          )
        )
      )
      PathRef(path)
    }

    private def runTest: T[Unit] = Task {
      configBuilder()
      val jasmine = npmInstall().path / "node_modules/jasmine/bin/jasmine.js"
      val tsnode = npmInstall().path / "node_modules/ts-node/register/transpile-only.js"
      val tsconfigPath = npmInstall().path / "node_modules/tsconfig-paths/register.js"
      os.call(
        (
          "node",
          jasmine,
          "--config=jasmine.json",
          s"--require=$tsnode",
          s"--require=$tsconfigPath"
        ),
        stdout = os.Inherit,
        env = forkEnv(),
        cwd = compile()._1.path
      )
      ()
    }

    protected def testTask(args: Task[Seq[String]]): Task[TestResult] = Task.Anon {
      runTest()
    }

  }

  trait Cypress extends TypeScriptModule with IntegrationSuite with TestModule {
    override def npmDevDeps: T[Seq[String]] = Task {
      Seq(
        "cypress@13.17.0"
      )
    }

    def testConfigSource: T[PathRef] =
      Task.Source(Task.workspace / "cypress.config.ts")

    override def compilerOptions: T[Map[String, ujson.Value]] =
      Task {
        super.compilerOptions() + (
          "target" -> ujson.Str("ES5"),
          "module" -> ujson.Str("ESNext"),
          "moduleResolution" -> ujson.Str("Node"),
          "skipLibCheck" -> ujson.Bool(true),
          "types" -> ujson.Arr(
            s"${npmInstall().path}/node_modules/cypress/types"
          )
        )
      }

    private def mkConfig: Task[TestResult] = Task {
      val tsc = npmInstall().path / "node_modules/.bin/tsc"
      os.call((
        tsc,
        testConfigSource().path.toString,
        "--outDir",
        compile()._1.path,
        "--target",
        "ES2020",
        "--module",
        "CommonJS",
        "--esModuleInterop"
      ))
      ()
    }

    override def forkEnv: T[Map[String, String]] =
      Task {
        Map("NODE_PATH" -> Seq(
          npmInstall().path,
          npmInstall().path / "node_modules"
        ).mkString(":"))
      }

    private def runTest: T[TestResult] = Task {
      val mainFile = service.mainFilePath()
      val tsnode = npmInstall().path / "node_modules/.bin/ts-node"
      val tsconfigpaths = npmInstall().path / "node_modules/tsconfig-paths/register"
      val env = forkEnv() + ("PORT" -> port())

      val serviceProcess = os.proc("node", tsnode, "-r", tsconfigpaths, mainFile).spawn(
        stdout = os.Inherit,
        env = env,
        cwd = service.compile()._1.path
      )

      mkConfig()
      val cypress = npmInstall().path / "node_modules/.bin/cypress"
      os.call(
        (
          cypress,
          "run"
        ),
        stdout = os.Inherit,
        env = forkEnv(),
        cwd = compile()._1.path
      )

      serviceProcess.destroy()
    }

    protected def testTask(args: Task[Seq[String]]): Task[TestResult] = Task.Anon {
      runTest()
    }

  }

  trait PlayWright extends TypeScriptModule with IntegrationSuite with TestModule {
    override def npmDevDeps: T[Seq[String]] = Task {
      super.npmDevDeps() ++ Seq(
        "@playwright/test@1.49.0",
        "glob@10.4.5"
      )
    }

    def testConfigSource: T[PathRef] =
      Task.Source(Task.workspace / "playwright.config.ts")

    private def copyConfig: Task[TestResult] = Task.Anon {
      os.copy.over(
        testConfigSource().path,
        compile()._1.path / "playwright.config.ts"
      )
    }

    private def runTest: T[TestResult] = Task {
      val mainFile = service.mainFilePath()
      val tsnode = npmInstall().path / "node_modules/.bin/ts-node"
      val tsconfigpaths = npmInstall().path / "node_modules/tsconfig-paths/register"
      val env = forkEnv() + ("PORT" -> port())

      val serviceProcess = os.proc("node", tsnode, "-r", tsconfigpaths, mainFile).spawn(
        stdout = os.Inherit,
        env = env,
        cwd = service.compile()._1.path
      )

      copyConfig()
      os.call(
        (
          "node",
          npmInstall().path / "node_modules/.bin/playwright",
          "test"
        ),
        stdout = os.Inherit,
        env = forkEnv(),
        cwd = compile()._1.path
      )

      serviceProcess.destroy()
    }

    protected def testTask(args: Task[Seq[String]]): Task[TestResult] = Task.Anon {
      runTest()
    }

  }

}
