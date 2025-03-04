package mill.javascriptlib

import mill.*

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

  trait Coverage extends TscModule with TestModule {
    override def npmDevDeps: T[Seq[String]] = Task {
      super.npmDevDeps() ++ Seq("serve@14.2.4")
    }

    private[TestModule] def runCoverage: T[TestResult]

    protected def coverageTask(args: Task[Seq[String]]): Task[TestResult] = Task { runCoverage() }

    def coverage(args: String*): Command[TestResult] =
      Task.Command {
        coverageTask(Task.Anon { args })()
      }

    def istanbulNycrcConfigBuilder: Task[PathRef] = Task.Anon {
      val compiled = compile()._1.path
      val fileName = ".nycrc"
      val config = compiled / fileName
      val customConfig = Task.workspace / fileName

      val content =
        s"""|{
            |  "report-dir": ${ujson.Str(s"${moduleDeps.head}_coverage")},
            |  "extends": "@istanbuljs/nyc-config-typescript",
            |  "all": true,
            |  "include": ${ujson.Arr.from(coverageDirs())},
            |  "exclude": ["node_modules", "*/**/*.test.ts"],
            |  "reporter": ["text", "html"],
            |  "require": ["ts-node/register", "tsconfig-paths/register"]
            |}
            |""".stripMargin

      if (!os.exists(customConfig)) os.write.over(config, content)
      else os.copy.over(customConfig, config)

      os.write.over(config, content)

      PathRef(config)
    }

    // web browser - serve coverage report
    def htmlReport: T[PathRef] = Task {
      runCoverage()
      val htmlPath = coverageFiles().path / "index.html"
      println(s"HTML Report: $htmlPath")
      PathRef(htmlPath)
    }

    // coverage files - returnn coverage files directory
    def coverageFiles: T[PathRef] = Task {
      val dir = compileDir().path / s"${moduleDeps.head}_coverage"
      println(s"coverage files: $dir")
      PathRef(dir)
    }
  }

  trait Shared extends TscModule {
    override def upstreamPathsBuilder: T[Seq[(String, String)]] =
      Task {
        val stuUpstreams = for {
          mod <- recModuleDeps
        } yield {
          val prefix = mod.moduleName.replaceAll("\\.", "/")
          (
            prefix + "/test/utils/*",
            if (prefix == primeMod)
              s"typescript/test/src/utils" + ":" + s"typescript/test/utils"
            else s"typescript/$mod/test/src/utils" + ":" + s"typescript/$mod/test/utils"
          )
        }

        stuUpstreams
      }

    private def primeMod: String = outerModuleName.getOrElse("")

    def testDir: String = this.toString.split("\\.").tail.mkString

    def getPathToTest: T[String] = Task { compile()._2.path.toString + s"/$testDir" }
  }

  trait IntegrationSuite extends TscModule {
    def service: TscModule

    def port: T[String]
  }

  trait Jest extends Coverage with Shared {
    override def npmDevDeps: T[Seq[String]] = Task {
      super.npmDevDeps() ++ Seq(
        "@types/jest@^29.5.14",
        "@babel/core@^7.26.0",
        "@babel/preset-env@^7.26.0",
        "jest@^29.7.0",
        "ts-jest@^29.2.5",
        "babel-jest@^29.7.0"
      )
    }

    override def compilerOptions: T[Map[String, ujson.Value]] =
      Task {
        Map("resolveJsonModule" -> ujson.Bool(true))
      }

    def conf: Task[PathRef] = Task.Anon {
      val compiled = compile()._1.path
      val fileName = "jest.config.ts"
      val config = compiled / fileName
      val customConfig = Task.workspace / fileName

      val content =
        s"""|import {pathsToModuleNameMapper} from 'ts-jest';
            |import {compilerOptions} from './tsconfig.json';
            |
            |const moduleDeps = {...compilerOptions.paths};
            |delete moduleDeps['typeRoots'];
            |
            |const sortedModuleDeps = Object.keys(moduleDeps)
            |    .sort((a, b) => b.length - a.length) // Sort by descending length
            |    .reduce((acc, key) => {
            |        acc[key] = moduleDeps[key];
            |        return acc;
            |    }, {});
            |
            |export default {
            |roots: ["<rootDir>/typescript"],
            |preset: 'ts-jest',
            |testEnvironment: 'node',
            |testMatch: ["<rootDir>/typescript/$testDir/**/?(*.)+(spec|test).[jt]s?(x)"],
            |transform: ${ujson.Obj("^.+\\.(ts|tsx)$" -> ujson.Arr.from(Seq(
             ujson.Str("ts-jest"),
             ujson.Obj("tsconfig" -> "tsconfig.json")
           )))},
            |moduleFileExtensions: ${ujson.Arr.from(Seq("ts", "tsx", "js", "jsx", "json", "node"))},
            |moduleNameMapper: pathsToModuleNameMapper(sortedModuleDeps, {prefix: "<rootDir>"})
            |}
            |""".stripMargin

      if (!os.exists(customConfig)) os.write.over(config, content)
      else os.copy.over(customConfig, config)

      PathRef(config)
    }

    private def runTest: T[TestResult] = Task {
      os.call(
        (
          "node",
          npmInstall().path / "node_modules/jest/bin/jest.js",
          "--config",
          conf().path,
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

    // with coverage
    def coverageConf: Task[PathRef] = Task.Anon {
      val compiled = compile()._1.path
      val fileName = "jest.config.ts"
      val config = compiled / fileName
      val customConfig = Task.workspace / fileName

      val content =
        s"""|import {pathsToModuleNameMapper} from 'ts-jest';
            |import {compilerOptions} from './tsconfig.json';
            |
            |const moduleDeps = {...compilerOptions.paths};
            |delete moduleDeps['typeRoots'];
            |
            |const sortedModuleDeps = Object.keys(moduleDeps)
            |    .sort((a, b) => b.length - a.length) // Sort by descending length
            |    .reduce((acc, key) => {
            |        acc[key] = moduleDeps[key];
            |        return acc;
            |    }, {});
            |
            |export default {
            |roots: ["<rootDir>/typescript"],
            |preset: 'ts-jest',
            |testEnvironment: 'node',
            |testMatch: ["<rootDir>/typescript/$testDir/**/?(*.)+(spec|test).[jt]s?(x)"],
            |transform: ${ujson.Obj("^.+\\.(ts|tsx)$" -> ujson.Arr.from(Seq(
             ujson.Str("ts-jest"),
             ujson.Obj("tsconfig" -> "tsconfig.json")
           )))},
            |moduleFileExtensions: ${ujson.Arr.from(Seq("ts", "tsx", "js", "jsx", "json", "node"))},
            |moduleNameMapper: pathsToModuleNameMapper(sortedModuleDeps, {prefix: "<rootDir>"}),
            |
            |collectCoverage: true,
            |collectCoverageFrom: ${ujson.Arr.from(coverageDirs())},
            |coveragePathIgnorePatterns: [${ujson.Str(".*\\.test\\.ts$")}],
            |coverageDirectory: '${moduleDeps.head}_coverage',
            |coverageReporters: ['text', 'html'],
            |}
            |""".stripMargin

      if (!os.exists(customConfig)) os.write.over(config, content)
      else os.copy.over(customConfig, config)

      PathRef(config)
    }

    def runCoverage: T[TestResult] = Task {
      os.call(
        (
          "node",
          "node_modules/jest/bin/jest.js",
          "--config",
          coverageConf().path,
          "--coverage",
          getPathToTest()
        ),
        stdout = os.Inherit,
        env = forkEnv(),
        cwd = compileDir().path
      )
      ()
    }

  }

  trait Mocha extends Coverage with Shared {
    override def npmDevDeps: T[Seq[String]] = Task {
      super.npmDevDeps() ++ Seq(
        "@types/chai@4.3.1",
        "@types/mocha@9.1.1",
        "chai@4.3.6",
        "mocha@10.0.0",
        "@istanbuljs/nyc-config-typescript@1.0.2",
        "nyc@17.1.0",
        "source-map-support@0.5.21"
      )
    }

    override def getPathToTest: T[String] =
      Task { super.getPathToTest() + "/**/**/*.test.ts" }

    // test-runner.js: run tests on ts files
    def conf: Task[PathRef] = Task.Anon {
      val compiled = compile()._1.path
      val runner = compiled / "test-runner.js"

      val content =
        """|require('ts-node/register');
           |require('tsconfig-paths/register');
           |require('mocha/bin/_mocha');
           |""".stripMargin

      os.write.over(runner, content)

      PathRef(runner)
    }

    private def runTest: T[Unit] = Task {
      os.call(
        (
          "node",
          conf().path,
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

    // with coverage
    def runCoverage: T[TestResult] = Task {
      istanbulNycrcConfigBuilder()
      os.call(
        (
          "./node_modules/.bin/nyc",
          "node",
          conf().path,
          getPathToTest()
        ),
        stdout = os.Inherit,
        env = forkEnv(),
        cwd = compile()._1.path
      )
      ()
    }
  }

  trait Vitest extends Coverage with Shared {
    override def npmDevDeps: T[Seq[String]] =
      Task {
        super.npmDevDeps() ++ Seq(
          "@vitest/runner@3.0.3",
          "vite@6.0.11",
          "vitest@3.0.3",
          "vite-tsconfig-paths@3.6.0",
          "@vitest/coverage-v8@3.0.3"
        )
      }

    override def compilerOptions: T[Map[String, ujson.Value]] =
      Task {
        Map(
          "target" -> ujson.Str("ESNext"),
          "module" -> ujson.Str("ESNext"),
          "moduleResolution" -> ujson.Str("Node"),
          "skipLibCheck" -> ujson.Bool(true),
          "types" -> ujson.Arr(
            s"${npmInstall().path}/node_modules/vitest/globals"
          )
        )
      }

    def conf: Task[PathRef] = Task.Anon {
      val compiled = compile()._1.path
      val fileName = "vitest.config.ts"
      val config = compiled / fileName
      val customConfig = Task.workspace / fileName

      val content =
        s"""|import { defineConfig } from 'vite';
            |import tsconfigPaths from 'vite-tsconfig-paths';
            |
            |export default defineConfig({
            |    plugins: [tsconfigPaths()],
            |    test: {
            |        globals: true,
            |        environment: 'node',
            |        include: ['typescript/$testDir/**/**/*.test.ts']
            |    },
            |});
            |""".stripMargin

      if (!os.exists(customConfig)) os.write.over(config, content)
      else os.copy.over(customConfig, config)

      PathRef(config)
    }

    private def runTest: T[TestResult] = Task {
      os.call(
        (
          npmInstall().path / "node_modules/.bin/ts-node",
          npmInstall().path / "node_modules/.bin/vitest",
          "--run",
          "--config",
          conf().path,
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

    // coverage
    def coverageConf: Task[PathRef] = Task.Anon {
      val compiled = compile()._1.path
      val fileName = "vitest.config.ts"
      val config = compiled / fileName
      val customConfig = Task.workspace / fileName
      val content =
        s"""|import { defineConfig } from 'vite';
            |import tsconfigPaths from 'vite-tsconfig-paths';
            |
            |export default defineConfig({
            |    plugins: [tsconfigPaths()],
            |    test: {
            |        globals: true,
            |        environment: 'node',
            |        include: ['typescript/$testDir/**/**/*.test.ts'],
            |        coverage: {
            |            provider: 'v8',
            |            reporter: ['text', 'json', 'html'],
            |            reportsDirectory: '${moduleDeps.head}_coverage',
            |            include: ${ujson.Arr.from(
             coverageDirs()
           )}, // Specify files to include for coverage
            |            exclude: ['*/**/*.test.ts'], // Specify files to exclude from coverage
            |        },
            |    },
            |});
            |""".stripMargin

      if (!os.exists(customConfig)) os.write.over(config, content)
      else os.copy.over(customConfig, config)

      PathRef(config)
    }

    def runCoverage: T[TestResult] = Task {
      os.call(
        (
          npmInstall().path / "node_modules/.bin/ts-node",
          npmInstall().path / "node_modules/.bin/vitest",
          "--run",
          "--config",
          coverageConf().path,
          "--coverage",
          getPathToTest()
        ),
        stdout = os.Inherit,
        env = forkEnv(),
        cwd = compile()._1.path
      )
      ()
    }

  }

  trait Jasmine extends Coverage with Shared {
    override def npmDevDeps: T[Seq[String]] =
      Task {
        super.npmDevDeps() ++ Seq(
          "@types/jasmine@5.1.2",
          "jasmine@5.1.0",
          "@istanbuljs/nyc-config-typescript@1.0.2",
          "nyc@17.1.0",
          "source-map-support@0.5.21"
        )
      }

    override def compilerOptions: T[Map[String, ujson.Value]] =
      Task {
        Map(
          "target" -> ujson.Str("ES5"),
          "module" -> ujson.Str("commonjs"),
          "moduleResolution" -> ujson.Str("node"),
          "allowJs" -> ujson.Bool(true)
        )
      }

    def conf: Task[PathRef] = Task.Anon {
      val path = compile()._1.path / "jasmine.json"
      os.write.over(
        path,
        ujson.write(
          ujson.Obj(
            "spec_dir" -> ujson.Str("typescript"),
            "spec_files" -> ujson.Arr(ujson.Str(s"$testDir/**/*.test.ts")),
            "stopSpecOnExpectationFailure" -> ujson.Bool(false),
            "random" -> ujson.Bool(false)
          )
        )
      )

      PathRef(path)
    }

    private def runTest: T[Unit] = Task {
      conf()
      val jasmine = "node_modules/jasmine/bin/jasmine.js"
      val tsnode = "node_modules/ts-node/register/transpile-only.js"
      val tsconfigPath = "node_modules/tsconfig-paths/register.js"
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

    def runCoverage: T[TestResult] = Task {
      istanbulNycrcConfigBuilder()
      conf()
      val jasmine = "node_modules/jasmine/bin/jasmine.js"
      val tsnode = "node_modules/ts-node/register/transpile-only.js"
      val tsconfigPath = "node_modules/tsconfig-paths/register.js"

      os.call(
        (
          "./node_modules/.bin/nyc",
          "node",
          jasmine,
          s"--config=jasmine.json",
          s"--require=$tsnode",
          s"--require=$tsconfigPath"
        ),
        stdout = os.Inherit,
        env = forkEnv(),
        cwd = compile()._1.path
      )

      // remove symlink
      os.remove(Task.workspace / "out/node_modules")
      os.remove(Task.workspace / "out/tsconfig.json")
      os.remove(Task.workspace / "out/.nycrc")
      ()
    }

  }

  trait Cypress extends TscModule with IntegrationSuite with TestModule {
    override def npmDevDeps: T[Seq[String]] = Task {
      Seq(
        "cypress@13.17.0"
      )
    }

    def testConfigSource: T[PathRef] =
      Task.Source(Task.workspace / "cypress.config.ts")

    override def compilerOptions: T[Map[String, ujson.Value]] =
      Task {
        Map(
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
      val port_ = port()
      val env = service.forkEnv() + ("PORT" -> port_)

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

  trait PlayWright extends TscModule with IntegrationSuite with TestModule {
    override def npmDevDeps: T[Seq[String]] = Task {
      super.npmDevDeps() ++ Seq(
        "playwright@1.49.0",
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
      val port_ = port()
      val env = service.forkEnv() + ("PORT" -> port_)

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
