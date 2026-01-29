package mill.javalib

import mill.T
import mill.api.Result
import mill.api.daemon.internal.TestModuleApi
import mill.api.daemon.internal.TestReporter
import mill.api.daemon.internal.bsp.{BspBuildTarget, BspModuleApi}
import mill.api.PathRef
import mill.api.Task
import mill.api.TaskCtx
import mill.api.DefaultTaskModule
import mill.javalib.bsp.BspModule
import mill.api.JsonFormatters.given
import mill.constants.EnvVars
import mill.javalib.api.internal.ZincOp
import mill.javalib.testrunner.{Framework, TestArgs, TestResult, TestRunner, TestRunnerUtils}
import mill.util.Version

import java.nio.file.Path

/**
 * A module containing JVM test suites. Requires you define a [[testFramework]] for your
 * Java, Scala or Kotlin testing library that implements the `sbt.testing` interface.
 * For many test frameworks, pre-configured traits are provided for you in [[TestModule.Junit4]],
 * [[TestModule.Junit5]], [[TestModule.ScalaTest]], etc.
 */
trait TestModule
    extends TestModule.JavaModuleBase
    with WithJvmWorkerModule
    with RunModule
    with DefaultTaskModule
    with TestModuleApi {

  override def defaultTask() = "testForked"

  /**
   * The classpath containing the tests. This is most likely the output of the compilation target.
   * By default, this uses the result of [[localRunClasspath]], which is most likely the result of a local compilation.
   */
  def testClasspath: T[Seq[PathRef]] = Task { localRunClasspath() }

  /**
   * The test framework to use to discover and run run tests.
   *
   * For convenience, you can also mix-in one of these predefined traits:
   * - [[TestModule.Junit4]]
   * - [[TestModule.Junit5]]
   * - [[TestModule.Junit6]]
   * - [[TestModule.Munit]]
   * - [[TestModule.ScalaTest]]
   * - [[TestModule.Specs2]]
   * - [[TestModule.TestNg]]
   * - [[TestModule.Utest]]
   * - [[TestModule.Weaver]]
   * - [[TestModule.ZioTest]]
   * - [[TestModule.Spock]]
   *
   * Most of these provide additional `xxxVersion` tasks, to manage the test framework dependencies for you.
   */
  def testFramework: T[String]

  /**
   * Test classes (often called test suites) discovered by the configured [[testFramework]].
   */
  def discoveredTestClasses: T[Seq[String]] = Task {
    val worker = jvmWorker().internalWorker()
    val discoveredTests = worker.apply(
      ZincOp.DiscoverTests(
        runClasspath().map(_.path),
        testClasspath().map(_.path),
        testFramework()
      ),
      javaHome().map(_.path)
    )

    discoveredTests.sorted
  }

  /**
   * Default arguments to be passed to `testForked`, `testOnly`, and `testCached`
   *
   * If you set this but would like to run `testForked` or `testOnly` without these default values,
   * pass `--addDefault=false` as first argument to them.
   */
  def testArgsDefault: T[Seq[String]] = Task(Nil)

  /**
   * Discovers and runs the module's tests in a subprocess, reporting the
   * results to the console.
   * @see [[testCached]]
   */
  def testForked(
      args: String*
  ): Task.Command[(msg: String, results: Seq[TestResult])] = {
    val argsTask = Task.Anon { testArgsDefault() ++ args }
    Task.Command {
      testTask(argsTask, Task.Anon { Seq.empty[String] })()
    }
  }

  def getTestEnvironmentVars(args: String*): Task.Command[(
      mainClass: String,
      testRunnerClasspathArg: String,
      argsFile: String,
      classpath: Seq[Path]
  )] = {
    Task.Command {
      getTestEnvironmentVarsTask(Task.Anon { args })()
    }
  }

  /**
   * Args to be used by [[testCached]].
   */
  def testCachedArgs: T[Seq[String]] = testArgsDefault

  /**
   * Discovers and runs the module's tests in a subprocess, reporting the
   * results to the console.
   * If no input has changed since the last run, no test were executed.
   *
   * @see [[testForked()]]
   */
  def testCached: T[(msg: String, results: Seq[TestResult])] = Task {
    testTask(testCachedArgs, Task.Anon { Seq.empty[String] })()
  }

  /**
   * How the test classes in this module will be split.
   * Test classes from different groups are ensured to never
   * run on the same JVM process, and therefore can be run in parallel.
   * When used in combination with [[testParallelism]],
   * every JVM test running process will guarantee to never claim tests
   * from different test groups.
   */
  def testForkGrouping: T[Seq[Seq[String]]] = Task {
    Seq(discoveredTestClasses())
  }

  /**
   * Whether to use the test parallelism to run tests in multiple JVM processes.
   * When used in combination with [[testForkGrouping]], every JVM test running process
   * will guarantee to never claim tests from different test groups.
   */
  def testParallelism: T[Boolean] = Task { true }

  /**
   * Discovers and runs the module's tests in a subprocess, reporting the
   * results to the console.
   * Arguments before "--" will be used as wildcard selector to select
   * test classes, arguments after "--" will be passed as regular arguments.
   * `testOnly *foo foobar bar* -- arguments` will test only classes with name
   * (includes package name) 1. end with "foo", 2. exactly "foobar", 3. start
   * with "bar", with "arguments" as arguments passing to test framework.
   */
  def testOnly(args: String*): Task.Command[(msg: String, results: Seq[TestResult])] = {
    val (selector, testArgs) = args.indexOf("--") match {
      case -1 => (args, Seq.empty)
      case pos =>
        val (s, t) = args.splitAt(pos)
        (s, t.tail)
    }

    val argsTask = Task.Anon { testArgsDefault() ++ testArgs }
    Task.Command {
      testTask(argsTask, Task.Anon { selector })()
    }
  }

  /**
   * Runs tests selectively based on code changes since the last run.
   *
   * This command compares method-level bytecode signatures between the current
   * and previous run to identify which classes have changed. It then runs only:
   *
   * 1. Tests whose code or dependencies have changed
   * 2. Tests that failed in the previous run (to verify fixes)
   * 3. All tests if no previous state exists (first run)
   *
   * State is persisted in `testQuick-state.json` in the task's destination directory.
   *
   * @param args Optional arguments passed to the test framework
   */
  def testQuick(args: String*): Task.Command[(msg: String, results: Seq[TestResult])] = {
    Task.Command(persistent = true) {
      val stateFile = Task.dest / "testQuick-state.json"
      val currentSignatures = methodCodeHashSignatures()

      // Load previous state
      case class TestQuickState(
          signatures: Map[String, Int],
          failedTests: Set[String],
          passedTests: Set[String]
      )

      val prevState: Option[TestQuickState] = if (os.exists(stateFile)) {
        try {
          val json = upickle.read[ujson.Value](os.read(stateFile))
          Some(TestQuickState(
            json.obj.get("signatures").map(_.obj.map { case (k, v) =>
              k -> v.num.toInt
            }.toMap).getOrElse(Map.empty),
            json.obj.get("failedTests").map(_.arr.map(_.str).toSet).getOrElse(Set.empty),
            json.obj.get("passedTests").map(_.arr.map(_.str).toSet).getOrElse(Set.empty)
          ))
        } catch {
          case _: Exception => None
        }
      } else None

      val discovered = discoveredTestClasses()

      // Determine which tests to run
      val testsToRun: Seq[String] = prevState match {
        case None =>
          // No previous state - run all tests
          Task.log.info("testQuick: No previous state found, running all tests")
          discovered

        case Some(prev) =>
          // Find changed methods
          val changedMethods = currentSignatures.filter { case (method, hash) =>
            prev.signatures.get(method) match {
              case Some(prevHash) => prevHash != hash
              case None => true // New method
            }
          }.keySet

          // Extract class names from method signatures (format: "className#methodName(...)")
          val changedClasses = changedMethods.flatMap { sig =>
            sig.split('#').headOption.map(_.replace('/', '.'))
          }

          // Tests to run: those referencing changed classes + previously failed
          val affectedTests = discovered.filter { testClass =>
            // A test is affected if any of its methods are in the changed set
            // or if it references any changed class (conservative approach)
            val testClassPath = testClass.replace('.', '/')
            changedClasses.exists { changedClass =>
              testClass.startsWith(changedClass) ||
              changedClass.startsWith(testClass) ||
              currentSignatures.keys.exists { sig =>
                sig.contains(testClassPath) && changedMethods.exists(sig.contains)
              }
            } ||
            // Also check if the test class itself changed
            currentSignatures.exists { case (sig, hash) =>
              sig.contains(testClassPath) &&
              prev.signatures.get(sig).exists(_ != hash)
            }
          }

          // Include previously failed tests
          val testsFromFailures = prev.failedTests.filter(discovered.contains)

          // New tests (not in previous run)
          val newTests = discovered.filterNot(t =>
            prev.passedTests.contains(t) || prev.failedTests.contains(t)
          )

          val combined = (affectedTests ++ testsFromFailures ++ newTests).distinct

          if (combined.isEmpty) {
            Task.log.info("testQuick: No changes detected, skipping tests")
          } else {
            Task.log.info(s"testQuick: Running ${combined.size} of ${discovered.size} tests")
          }

          combined
      }

      // Run the selected tests using TestModuleUtil directly
      val (msg, results) = if (testsToRun.isEmpty) {
        ("No tests to run - all tests passed and no changes detected", Seq.empty[TestResult])
      } else {
        val testModuleUtil = new TestModuleUtil(
          testUseArgsFile(),
          forkArgs(),
          testsToRun, // Use the computed selectors directly
          jvmWorker().scalalibClasspath(),
          resources(),
          testFramework(),
          runClasspath(),
          testClasspath(),
          testArgsDefault() ++ args,
          testForkGrouping(),
          jvmWorker().testrunnerEntrypointClasspath(),
          allForkEnv(),
          testSandboxWorkingDir(),
          forkWorkingDir(),
          testReportXml(),
          javaHome().map(_.path),
          testParallelism(),
          testLogLevel(),
          propagateEnv(),
          jvmWorker().internalWorker()
        )
        testModuleUtil.runTests()
      }

      // Save new state
      val newFailedTests = results.filter(_.status == "Failure").map(_.fullyQualifiedName).toSet
      val newPassedTests = results.filter(_.status == "Success").map(_.fullyQualifiedName).toSet

      // Carry forward results from tests we didn't run (if they hadn't changed)
      val allPassedTests = prevState.map(_.passedTests).getOrElse(Set.empty).diff(
        newFailedTests ++ testsToRun.toSet
      ) ++ newPassedTests

      val allFailedTests = prevState.map(_.failedTests).getOrElse(Set.empty).diff(
        newPassedTests ++ testsToRun.filterNot(newFailedTests.contains).toSet
      ) ++ newFailedTests

      val stateJson = ujson.Obj(
        "signatures" -> ujson.Obj.from(currentSignatures.map { case (k, v) => k -> ujson.Num(v) }),
        "failedTests" -> ujson.Arr.from(allFailedTests.map(ujson.Str(_))),
        "passedTests" -> ujson.Arr.from(allPassedTests.map(ujson.Str(_)))
      )
      os.write.over(stateFile, upickle.write(stateJson, indent = 2))

      (msg, results)
    }
  }

  /**
   * Controls whether the TestRunner should receive its arguments via an args-file instead of a long parameter list.
   * Defaults to what `runUseArgsFile` return.
   */
  def testUseArgsFile: T[Boolean] = Task { runUseArgsFile() || scala.util.Properties.isWin }

  /**
   * Sets the file name for the generated JUnit-compatible test report.
   * If None is set, no file will be generated.
   */
  def testReportXml: T[Option[String]] = Task { Some("test-report.xml") }

  def testLogLevel: T[TestReporter.LogLevel] = Task(TestReporter.LogLevel.Debug)

  /**
   * Returns a Tuple where the first element is the main-class, second and third are main-class-arguments and the forth is classpath
   */
  private def getTestEnvironmentVarsTask(args: Task[Seq[String]])
      : Task[(
          mainClass: String,
          testRunnerClasspathArg: String,
          argsFile: String,
          classpath: Seq[Path]
      )] =
    Task.Anon {
      val mainClass = "mill.javalib.testrunner.entrypoint.MillTestRunnerMain"
      val outputPath = Task.dest / "out.json"
      val resultPath = Task.dest / "results.log"
      val selectors = Seq.empty

      val testArgs = TestArgs(
        framework = testFramework(),
        classpath = runClasspath().map(_.path).toVector,
        arguments = args(),
        sysProps = Map.empty,
        outputPath = outputPath,
        resultPath = resultPath,
        colored = Task.log.prompt.colored,
        testCp = testClasspath().map(_.path),
        globSelectors = Left(selectors),
        logLevel = testLogLevel()
      )

      val argsFile = Task.dest / "testargs"
      os.write(argsFile, upickle.write(testArgs))

      val testRunnerClasspathArg =
        jvmWorker().scalalibClasspath()
          .map(_.path.toURL).mkString(",")

      val cp = (runClasspath() ++ jvmWorker().testrunnerEntrypointClasspath()).map(_.path.toNIO)

      Result.Success((mainClass, testRunnerClasspathArg, argsFile.toString, cp))
    }

  /**
   * Whether to use the test task destination folder as the working directory
   * when running tests. `true` means test subprocess run in the `.dest/sandbox` folder of
   * the test task, providing better isolation and encouragement of best practices
   * (e.g. not reading/writing stuff randomly from the project source tree). `false`
   * means the test subprocess runs in the project root folder, providing weaker
   * isolation.
   */
  def testSandboxWorkingDir: T[Boolean] = true

  override def allForkEnv: T[Map[String, String]] = Task {
    super.allForkEnv() ++ Map(
      EnvVars.MILL_TEST_RESOURCE_DIR -> resources().iterator.map(_.path).mkString(";")
    )
  }

  /**
   * The actual task shared by `test`-tasks that runs test in a forked JVM.
   */
  protected def testTask(
      args: Task[Seq[String]],
      globSelectors: Task[Seq[String]]
  ): Task[(msg: String, results: Seq[TestResult])] =
    Task.Anon {
      val testModuleUtil = new TestModuleUtil(
        testUseArgsFile(),
        forkArgs(),
        globSelectors(),
        jvmWorker().scalalibClasspath(),
        resources(),
        testFramework(),
        runClasspath(),
        testClasspath(),
        args(),
        testForkGrouping(),
        jvmWorker().testrunnerEntrypointClasspath(),
        allForkEnv(),
        testSandboxWorkingDir(),
        forkWorkingDir(),
        testReportXml(),
        javaHome().map(_.path),
        testParallelism(),
        testLogLevel(),
        propagateEnv(),
        jvmWorker().internalWorker()
      )
      testModuleUtil.runTests()
    }

  /**
   * Discovers and runs the module's tests in-process in an isolated classloader,
   * reporting the results to the console
   */
  def testLocal(args: String*): Task.Command[(msg: String, results: Seq[TestResult])] =
    Task.Command {
      val (doneMsg, results) = TestRunner.runTestFramework(
        Framework.framework(testFramework()),
        runClasspath().map(_.path),
        Seq.from(testClasspath().map(_.path)),
        args,
        Task.testReporter
      )
      TestModule.handleResults(doneMsg, results, Task.ctx(), testReportXml())
    }

  override def bspBuildTarget: BspBuildTarget = {
    val parent = super.bspBuildTarget
    parent.copy(
      canTest = true,
      tags = Seq(BspModuleApi.Tag.Test)
    )
  }

  private[mill] def bspBuildTargetScalaTestClasses: Task[(
      frameworkName: String,
      classes: Seq[String]
  )] = Task.Anon {
    val (frameworkName, classFingerprint) =
      mill.util.Jvm.withClassLoader(
        classPath = runClasspath().map(_.path),
        sharedPrefixes = Seq("sbt.testing.", "mill.api.daemon.internal.TestReporter")
      ) { classLoader =>
        val framework = Framework.framework(testFramework())(classLoader)
        framework.name() -> TestRunnerUtils
          .discoverTests(classLoader, framework, testClasspath().map(_.path))
      }
    val classes = classFingerprint.map(classF => classF._1.getName.stripSuffix("$"))
    (frameworkName = frameworkName, classes = classes)
  }
}

object TestModule {

  /**
   * TestModule using TestNG Framework to run tests.
   * You can override the [[testngVersion]] task or provide the UTest-dependency yourself.
   */
  trait TestNg extends TestModule {

    /** The TestNG version to use, or empty, if you want to provide the TestNG-dependency yourself. */
    def testngVersion: T[String] = Task { "" }
    override def testFramework: T[String] = "mill.testng.TestNGFramework"
    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      super.mandatoryMvnDeps() ++ Seq(
        mvn"com.lihaoyi:mill-contrib-testng:${mill.api.BuildInfo.millVersion}"
      ) ++
        Seq(testngVersion())
          .filter(!_.isBlank())
          .map(v => mvn"org.testng:testng:${v.trim()}")
    }
  }

  /**
   * TestModule that uses JUnit 4 Framework to run tests.
   * You can override the [[junit4Version]] task or provide the JUnit 4-dependency yourself.
   */
  trait Junit4 extends TestModule {

    /** The JUnit4 version to use, or empty, if you want to provide the Junit-dependency yourself. */
    def junit4Version: T[String] = Task { "" }
    override def testFramework: T[String] = "com.novocode.junit.JUnitFramework"
    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      super.mandatoryMvnDeps() ++
        Seq(
          mvn"${mill.javalib.api.Versions.sbtTestInterface}"
        ) ++
        Seq(junit4Version())
          .filter(!_.isBlank())
          .map(v => mvn"junit:junit:${v.trim()}")
    }
  }

  /**
   * TestModule that uses JUnit 5 Framework to run tests.
   * You can override the [[junitPlatformVersion]] and [[jupiterVersion]] task
   * or provide the JUnit 5-dependencies yourself.
   *
   * In case the [[jupiterVersion]] is set (and it is > 5.12), it pulls in JUnit-BOM in [[bomMvnDeps]]. If this is
   * true, then there is no need to specify the [[junitPlatformVersion]] anymore, because this is managed by the
   * BOM.
   *
   * See: https://junit.org/junit5/
   */
  trait Junit5 extends TestModule {

    /** The JUnit 5 Platform version to use, or empty, if you want to provide the dependencies yourself. */
    def junitPlatformVersion: T[String] = Task { "" }

    /** The JUnit Jupiter version to use, or empty, if you want to provide the dependencies yourself. */
    def jupiterVersion: T[String] = Task { "" }

    /** Whether to use the JUnit BOM for dependency management. Override in subclasses. */
    protected def useBom: T[Boolean] = Task {
      if (jupiterVersion().isBlank) false
      else Version.isAtLeast(jupiterVersion(), "5.12.0")(using Version.IgnoreQualifierOrdering)
    }

    /** The jupiter interface artifact to use. Override in subclasses for different versions. */
    protected def jupiterInterfaceArtifact: String = mill.javalib.api.Versions.jupiterInterface

    override def testFramework: T[String] = "com.github.sbt.junit.jupiter.api.JupiterFramework"

    override def bomMvnDeps: T[Seq[Dep]] = Task {
      super.bomMvnDeps() ++ {
        Seq(jupiterVersion())
          .filter(!_.isBlank() && useBom())
          .map(v => mvn"org.junit:junit-bom:${v.trim()}")
      }
    }

    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      super.mandatoryMvnDeps() ++
        Seq(mvn"${jupiterInterfaceArtifact}") ++
        Seq(junitPlatformVersion()).flatMap(v => {
          if (!v.isBlank) Some(mvn"org.junit.platform:junit-platform-launcher:${v.trim()}")
          else if (useBom()) Some(mvn"org.junit.platform:junit-platform-launcher")
          else None
        }) ++
        Seq(jupiterVersion())
          .filter(!_.isBlank())
          .map(v => mvn"org.junit.jupiter:junit-jupiter-api:${v.trim()}")
    }

    protected lazy val classesDir: Task[Option[os.Path]] = this match {
      case withCompileTask: JavaModule => Task.Anon {
          Some(withCompileTask.compile().classes.path)
        }
      case m => Task.Anon {
          m.testClasspath().map(_.path).find { path =>
            os.exists(path) && os.walk.stream(path).exists(p => os.isFile(p) && p.ext == "class")
          }
        }
    }

    /**
     * Overridden since Junit5 has its own discovery mechanism.
     *
     * This is basically a re-implementation of sbt's plugin for Junit5 test
     * discovery mechanism. See
     * https://github.com/sbt/sbt-jupiter-interface/blob/468d4f31f1f6ce8529fff8a8804dd733974c7686/src/plugin/src/main/scala/com/github/sbt/junit/jupiter/sbt/JupiterPlugin.scala#L97C15-L118
     * for details.
     *
     * Note that we access the test discovery via reflection, to avoid mill
     * itself having a dependency on Junit5. Hence, if you remove the
     * `sbt-jupiter-interface` dependency from `mvnDeps`, make sure to also
     * override this method.
     */
    override def discoveredTestClasses: T[Seq[String]] = Task {
      val worker = jvmWorker().internalWorker()
      worker.apply(
        mill.javalib.api.internal.ZincOp.DiscoverJunit5Tests(
          runClasspath().map(_.path),
          testClasspath().map(_.path),
          classesDir()
        ),
        javaHome().map(_.path)
      )
    }
  }

  /**
   * TestModule that uses JUnit 6 Framework to run tests.
   * You can override the [[jupiterVersion]] task or provide the JUnit 6-dependencies yourself.
   *
   * Note: JUnit 6 requires Java 17 or higher.
   *
   * JUnit 6 uses unified versioning where Platform, Jupiter, and Vintage all share
   * the same version number (e.g., 6.0.2).
   *
   * See: https://junit.org/junit6/
   */
  trait Junit6 extends Junit5 {

    /** The JUnit 6 version to use, or empty, if you want to provide the dependencies yourself. */
    override def jupiterVersion: T[String] = Task { "" }

    // JUnit 6 BOM is always available when version is provided (no minimum version requirement like JUnit 5)
    override protected def useBom: T[Boolean] = Task { !jupiterVersion().isBlank }

    // Use JUnit 6's jupiter-interface dependency
    override protected def jupiterInterfaceArtifact: String =
      mill.javalib.api.Versions.jupiterInterface6
  }

  /**
   * TestModule that uses ScalaTest Framework to run tests.
   * You can override the [[scalaTestVersion]] task or provide the Specs2-dependency yourself.
   *
   * See: https://www.scalatest.org
   */
  trait ScalaTest extends TestModule {

    /** The ScalaTest version to use, or the empty string, if you want to provide the ScalaTest-dependency yourself. */
    def scalaTestVersion: T[String] = Task { "" }

    /**
     * If non-empty, only the selected suites/specs will be added as dependencies.
     * E.g. `Seq("funsuite", "freespec")` will result in the tho dependencies:
     * `org.scalatest::scalatest-funsuite` and `org.scalatest::scalatest-freespec`.
     *
     * If empty (default), the full scalatest dependency is used.
     *
     * See also: https://www.scalatest.org/user_guide/selecting_a_style
     */
    def scalaTestStyles: T[Seq[String]] = Task { Seq.empty[String] }
    override def testFramework: T[String] = "org.scalatest.tools.Framework"
    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      super.mandatoryMvnDeps() ++
        Seq(scalaTestVersion())
          .filter(!_.isBlank())
          .flatMap(v =>
            scalaTestStyles() match {
              case Seq() => Seq(
                  // the full suite
                  mvn"org.scalatest::scalatest::${v.trim()}"
                )
              case features => features.map { feature =>
                  mvn"org.scalatest::scalatest-${feature}::${v.trim()}"
                }
            }
          )
    }
  }

  /**
   * TestModule that uses Specs2 Framework to run tests.
   * You can override the [[specs2Version]] task or provide the Specs2-dependency yourself.
   */
  trait Specs2 extends ScalaModuleBase with TestModule {

    /** The Specs2 version to use, or the empty string, if you want to provide the Specs2-dependency yourself. */
    def specs2Version: T[String] = Task { "" }
    override def testFramework: T[String] = "org.specs2.runner.Specs2Framework"
    override def scalacOptions = Task {
      super.scalacOptions() ++ Seq("-Yrangepos")
    }
    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      super.mandatoryMvnDeps() ++
        Seq(specs2Version())
          .filter(!_.isBlank())
          .map(v => mvn"org.specs2::specs2-core::${v.trim()}")
    }
  }

  /**
   * TestModule that uses UTest Framework to run tests.
   * You can override the [[utestVersion]] task or provide the UTest-dependency yourself.
   */
  trait Utest extends TestModule {

    /** The UTest version to use, or the empty string, if you want to provide the UTest-dependency yourself. */
    def utestVersion: T[String] = Task { "" }
    override def testFramework: T[String] = "utest.runner.Framework"
    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      super.mandatoryMvnDeps() ++
        Seq(utestVersion())
          .filter(!_.isBlank())
          .map(v => mvn"com.lihaoyi::utest::${v.trim()}")
    }
  }

  /**
   * TestModule that uses MUnit to run tests.
   * You can override the [[munitVersion]] task or provide the MUnit-dependency yourself.
   */
  trait Munit extends TestModule {

    /** The MUnit version to use, or the empty string, if you want to provide the MUnit-dependency yourself. */
    def munitVersion: T[String] = Task { "" }
    override def testFramework: T[String] = "munit.Framework"
    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      super.mandatoryMvnDeps() ++
        Seq(munitVersion())
          .filter(!_.isBlank())
          .map(v => mvn"org.scalameta::munit::${v.trim()}")
    }
  }

  /**
   * TestModule that uses Weaver to run tests.
   * You can override the [[weaverVersion]] task or provide the Weaver-dependency yourself.
   * https://github.com/disneystreaming/weaver-test
   */
  trait Weaver extends TestModule {

    /** The Weaver version to use, or the empty string, if you want to provide the Weaver-dependency yourself. */
    def weaverVersion: T[String] = Task { "" }
    override def testFramework: T[String] = "weaver.framework.CatsEffect"
    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      super.mandatoryMvnDeps() ++
        Seq(weaverVersion())
          .filter(!_.isBlank())
          .map(v => mvn"com.disneystreaming::weaver-scalacheck::${v.trim()}")
    }
  }

  /**
   * TestModule that uses ZIO Test Framework to run tests.
   * You can override the [[zioTestVersion]] task or provide the Weaver-dependency yourself.
   */
  trait ZioTest extends TestModule {

    /** The ZIO Test version to use, or the empty string, if you want to provide the ZIO Test-dependency yourself. */
    def zioTestVersion: T[String] = Task { "" }
    override def testFramework: T[String] = "zio.test.sbt.ZTestFramework"
    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      super.mandatoryMvnDeps() ++
        Seq(zioTestVersion())
          .filter(!_.isBlank())
          .flatMap(v =>
            Seq(
              mvn"dev.zio::zio-test:${v.trim()}",
              mvn"dev.zio::zio-test-sbt:${v.trim()}"
            )
          )
    }
  }

  /**
   * TestModule that uses ScalaCheck Test Framework to run tests.
   * You can override the [[scalaCheckVersion]] task or provide the dependency yourself.
   */
  trait ScalaCheck extends TestModule {

    /** The ScalaCheck version to use, or the empty string, if you want to provide the dependency yourself. */
    def scalaCheckVersion: T[String] = Task { "" }
    override def testFramework: T[String] = "org.scalacheck.ScalaCheckFramework"
    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      super.mandatoryMvnDeps() ++
        Seq(scalaCheckVersion())
          .filter(!_.isBlank())
          .map(v => mvn"org.scalacheck::scalacheck:${v.trim()}")
    }
  }

  /**
   * TestModule that uses Spock Test Framework to run tests.
   * You can override the [[spockVersion]] task or provide the Spock dependency yourself.
   *
   * In case the version is set, it pulls in Spock-BOM in [[bomMvnDeps]] (only for 2.3 onwards)
   * and Spock-Core in [[mvnDeps]]
   */
  trait Spock extends TestModule.Junit5 {

    /** The Spock Test version to use, or the empty string, if you want to provide the Spock test dependency yourself. */
    def spockVersion: T[String] = Task {
      ""
    }

    private def isSpockBomAvailable: T[Boolean] = Task {
      if (spockVersion().isBlank) {
        false
      } else {
        Version.isAtLeast(spockVersion(), "2.3")(using Version.IgnoreQualifierOrdering)
      }
    }

    override def bomMvnDeps: T[Seq[Dep]] = Task {
      super.bomMvnDeps() ++
        Seq(spockVersion())
          .filter(!_.isBlank() && isSpockBomAvailable())
          .flatMap(v =>
            Seq(
              mvn"org.spockframework:spock-bom:${v.trim()}"
            )
          )
    }

    override def mandatoryMvnDeps: T[Seq[Dep]] = Task {
      super.mandatoryMvnDeps() ++
        Seq(spockVersion())
          .filter(!_.isBlank())
          .flatMap(v =>
            Seq(
              mvn"org.spockframework:spock-core:${v.trim()}"
            )
          )
    }
  }

  def handleResults(
      doneMsg: String,
      results: Seq[TestResult],
      ctx: Option[TaskCtx.Env]
  ): Result[(msg: String, results: Seq[TestResult])] =
    TestModuleUtil.handleResults(doneMsg, results, ctx)

  def handleResults(
      doneMsg: String,
      results: Seq[TestResult],
      ctx: TaskCtx.Env & TaskCtx.Dest,
      testReportXml: Option[String],
      props: Option[Map[String, String]] = None
  ): Result[(msg: String, results: Seq[TestResult])] =
    TestModuleUtil.handleResults(doneMsg, results, ctx, testReportXml, props)

  trait JavaModuleBase extends BspModule {
    def mvnDeps: T[Seq[Dep]] = Seq()
    def mandatoryMvnDeps: T[Seq[Dep]] = Seq()
    def resources: T[Seq[PathRef]] = Task { Seq.empty[PathRef] }
    def bomMvnDeps: T[Seq[Dep]] = Seq()

    /** Override in JavaModule to provide method code hash signatures for selective testing */
    def methodCodeHashSignatures: T[Map[String, Int]] = Task { Map.empty[String, Int] }
  }

  trait ScalaModuleBase extends mill.Module {
    def scalacOptions: T[Seq[String]] = Seq()
  }

}
