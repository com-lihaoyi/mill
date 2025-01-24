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

  trait Coverage extends TypeScriptModule with TestModule {
    def coverage(args: String*): Command[TestResult] =
      Task.Command {
        coverageTask(Task.Anon { args })()
      }

    protected def coverageTask(args: Task[Seq[String]]): Task[TestResult]

    def coverageDirs: T[Seq[String]] = Task.traverse(moduleDeps)(_.compile)().map { p =>
      (p._2.path.subRelativeTo(Task.workspace / "out") / "src").toString + "/**/*.ts"
    }

    // <rootDir> = '/out'; allow coverage resolve distributed source files.
    // & define coverage files relative to <rootDir>.
    def link: Task[Unit] = Task.Anon {
      os.symlink(Task.workspace / "out/node_modules", npmInstall().path / "node_modules")
      os.symlink(Task.workspace / "out/tsconfig.json", compile()._1.path / "tsconfig.json")
      if (os.exists(compile()._1.path / ".nycrc"))
        os.symlink(Task.workspace / "out/.nycrc", compile()._1.path / ".nycrc")
    }

    def nycrcBuilder: Task[Path] = Task.Anon {
      val compiled = compile()._1.path
      val runner = compiled / ".nycrc"

      val content =
        s"""|{
            |  "extends": "@istanbuljs/nyc-config-typescript",
            |  "all": true,
            |  "include": ${ujson.Arr.from(coverageDirs())},
            |  "exclude": ["node_modules"],
            |  "reporter": ["text", "html"],
            |  "require": ["ts-node/register", "tsconfig-paths/register"]
            |}
            |""".stripMargin

      os.write.over(runner, content)

      runner
    }
  }

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

  trait Jest extends Coverage with Shared {
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

    override def compilerOptions: T[Map[String, ujson.Value]] =
      Task {
        super.compilerOptions() + ("resolveJsonModule" -> ujson.Bool(true))
      }

    def conf: Task[Path] = Task.Anon {
      val compiled = compile()._1.path
      val config = compiled / "jest.config.ts"

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
            |preset: 'ts-jest',
            |testEnvironment: 'node',
            |    testMatch: ['<rootDir>/**/**/**/*.test.ts', '<rootDir>/**/**/**/*.test.js'],
            |transform: ${ujson.Obj("^.+\\.(ts|tsx)$" -> ujson.Arr.from(Seq(
             ujson.Str("ts-jest"),
             ujson.Obj("tsconfig" -> "tsconfig.json")
           )))},
            |moduleFileExtensions: ${ujson.Arr.from(Seq("ts", "tsx", "js", "jsx", "json", "node"))},
            |moduleNameMapper: pathsToModuleNameMapper(moduleDeps)
            |}
            |""".stripMargin

      os.write.over(config, content)

      config
    }

    private def runTest: T[TestResult] = Task {
      os.call(
        (
          "node",
          npmInstall().path / "node_modules/jest/bin/jest.js",
          "--config",
          conf(),
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
    private def coverageConf: Task[Path] = Task.Anon {
      val compiled = compile()._1.path
      val config = compiled / "jest.config.ts"

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
            |rootDir: ${ujson.Str((Task.workspace / "out").toString)},
            |preset: 'ts-jest',
            |testEnvironment: 'node',
            |testMatch: [${ujson.Str(
             s"<rootDir>/${compile()._2.path.subRelativeTo(Task.workspace / "out") / "src"}/**/*.test.ts"
           )}],
            |transform: ${ujson.Obj("^.+\\.(ts|tsx)$" -> ujson.Arr.from(Seq(
             ujson.Str("ts-jest"),
             ujson.Obj("tsconfig" -> "tsconfig.json")
           )))},
            |moduleFileExtensions: ${ujson.Arr.from(Seq("ts", "tsx", "js", "jsx", "json", "node"))},
            |moduleNameMapper: pathsToModuleNameMapper(sortedModuleDeps),
            |
            |collectCoverage: true,
            |collectCoverageFrom: ${ujson.Arr.from(coverageDirs())},
            |coverageDirectory: 'coverage',
            |coverageReporters: ['text', 'html'],
            |}
            |""".stripMargin

      os.write.over(config, content)

      config
    }

    private def runCoverage: T[TestResult] = Task {
      link()
      os.call(
        (
          "node",
          "node_modules/jest/bin/jest.js",
          "--config",
          coverageConf(),
          "--coverage",
          getPathToTest()
        ),
        stdout = os.Inherit,
        env = forkEnv(),
        cwd = Task.workspace / "out"
      )

      // remove symlink
      os.remove(Task.workspace / "out/node_modules")
      os.remove(Task.workspace / "out/tsconfig.json")
      ()
    }

    protected def coverageTask(args: Task[Seq[String]]): Task[TestResult] = Task.Anon {
      runCoverage()
    }

  }

  trait Mocha extends Coverage with Shared {
    override def npmDevDeps: T[Seq[String]] = Task {
      Seq(
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
    def conf: Task[Path] = Task.Anon {
      val compiled = compile()._1.path
      val runner = compiled / "test-runner.js"

      val content =
        """|require('ts-node/register');
           |require('tsconfig-paths/register');
           |require('mocha/bin/_mocha');
           |""".stripMargin

      os.write.over(runner, content)

      runner
    }

    private def runTest: T[Unit] = Task {
      os.call(
        (
          "node",
          conf(),
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
    private def runCoverage: T[TestResult] = Task {
      nycrcBuilder()
      link()
      os.call(
        (
          "./node_modules/.bin/nyc",
          "node",
          conf(),
          getPathToTest()
        ),
        stdout = os.Inherit,
        env = forkEnv(),
        cwd = Task.workspace / "out"
      )

      // remove symlink
      os.remove(Task.workspace / "out/node_modules")
      os.remove(Task.workspace / "out/tsconfig.json")
      os.remove(Task.workspace / "out/.nycrc")
      ()
    }

    protected def coverageTask(args: Task[Seq[String]]): Task[TestResult] = Task.Anon {
      runCoverage()
    }
  }

  trait Vitest extends Coverage with Shared {
    override def npmDevDeps: T[Seq[String]] =
      Task {
        Seq(
          "@vitest/runner@3.0.3",
          "vite@6.0.11",
          "vitest@3.0.3",
          "vite-tsconfig-paths@3.6.0",
          "@vitest/coverage-v8@3.0.3"
        )
      }

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

    def conf: Task[Path] = Task.Anon {
      val compiled = compile()._1.path
      val config = compiled / "vite.config.ts"

      val content =
        """|import { defineConfig } from 'vite';
           |import tsconfigPaths from 'vite-tsconfig-paths';
           |
           |export default defineConfig({
           |    plugins: [tsconfigPaths()],
           |    test: {
           |        globals: true,
           |        environment: 'node',
           |        include: ['**/**/*.test.ts']
           |    },
           |});
           |""".stripMargin

      os.write.over(config, content)

      config
    }

    private def runTest: T[TestResult] = Task {
      os.call(
        (
          npmInstall().path / "node_modules/.bin/ts-node",
          npmInstall().path / "node_modules/.bin/vitest",
          "--run",
          "--config",
          conf(),
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
    def coverageConf: Task[Path] = Task.Anon {
      val compiled = compile()._1.path
      val config = compiled / "vite.config.ts"

      val content =
        s"""|import { defineConfig } from 'vite';
            |import tsconfigPaths from 'vite-tsconfig-paths';
            |
            |export default defineConfig({
            |    plugins: [tsconfigPaths()],
            |    test: {
            |        globals: true,
            |        environment: 'node',
            |        include: [${ujson.Str(
             s"${compile()._2.path.subRelativeTo(Task.workspace / "out") / "src"}/**/*.test.ts"
           )}],
            |        coverage: {
            |            provider: 'v8',
            |            reporter: ['text', 'json', 'html'],
            |            reportsDirectory: 'coverage',
            |            include: ${ujson.Arr.from(
             coverageDirs()
           )}, // Specify files to include for coverage
            |        },
            |    },
            |});
            |""".stripMargin

      os.write.over(config, content)

      config
    }

    private def runCoverage: T[TestResult] = Task {
      link()
      os.call(
        (
          npmInstall().path / "node_modules/.bin/ts-node",
          npmInstall().path / "node_modules/.bin/vitest",
          "--run",
          "--config",
          coverageConf(),
          "--coverage",
          getPathToTest()
        ),
        stdout = os.Inherit,
        env = forkEnv(),
        cwd = Task.workspace / "out"
      )
      // remove symlink
      os.remove(Task.workspace / "out/node_modules")
      os.remove(Task.workspace / "out/tsconfig.json")
      ()
    }

    protected def coverageTask(args: Task[Seq[String]]): Task[TestResult] = Task { runCoverage() }

  }

  trait Jasmine extends Coverage with Shared {
    override def npmDevDeps: T[Seq[String]] =
      Task {
        Seq(
          "@types/jasmine@5.1.2",
          "jasmine@5.1.0",
          "@istanbuljs/nyc-config-typescript@1.0.2",
          "nyc@17.1.0",
          "source-map-support@0.5.21"
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

    def conf: T[Path] = Task {
      val path = compile()._1.path / "jasmine.json"
      os.write.over(
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

      path
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

    // with coverage
    def coverageConf: T[Path] = Task {
      val path = compile()._1.path / "jasmine.json"
      val specDir = compile()._2.path.subRelativeTo(Task.workspace / "out") / "src"
      os.write.over(
        path,
        ujson.write(
          ujson.Obj(
            "spec_dir" -> ujson.Str(specDir.toString),
            "spec_files" -> ujson.Arr(ujson.Str("**/*.test.ts")),
            "stopSpecOnExpectationFailure" -> ujson.Bool(false),
            "random" -> ujson.Bool(false)
          )
        )
      )
      path
    }

    private def runCoverage: T[TestResult] = Task {
      nycrcBuilder()
      link()
      val jasmine = "node_modules/jasmine/bin/jasmine.js"
      val tsnode = "node_modules/ts-node/register/transpile-only.js"
      val tsconfigPath = "node_modules/tsconfig-paths/register.js"
      val relConfigPath = coverageConf().subRelativeTo(Task.workspace / "out")
      os.call(
        (
          "./node_modules/.bin/nyc",
          "node",
          jasmine,
          s"--config=$relConfigPath",
          s"--require=$tsnode",
          s"--require=$tsconfigPath"
        ),
        stdout = os.Inherit,
        env = forkEnv(),
        cwd = Task.workspace / "out"
      )

      // remove symlink
      os.remove(Task.workspace / "out/node_modules")
      os.remove(Task.workspace / "out/tsconfig.json")
      os.remove(Task.workspace / "out/.nycrc")
      ()
    }

    protected def coverageTask(args: Task[Seq[String]]): Task[TestResult] = Task.Anon {
      runCoverage()
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

  trait PlayWright extends TypeScriptModule with IntegrationSuite with TestModule {
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
