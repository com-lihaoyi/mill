package mill.integration

import mill.constants.Util
import mill.integration.testMillInit
import utest.*

def bumpSbtTo1107(workspacePath: os.Path) =
  // bump sbt version to resolve compatibility issues with lower sbt versions and higher JDK versions
  os.write.over(workspacePath / "project" / "build.properties", "sbt.version = 1.10.7")

// relatively small libraries

object MillInitLibraryExampleTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 21 KB
    - sbt 1.5.2
     */
    val url = "https://github.com/scalacenter/library-example/archive/refs/tags/v1.0.1.zip"

    test - integrationTest(url)(
      _.testMillInit(
        expectedCompileTasks = Some(SplitResolvedTasks(Seq("compile"), Seq())),
        expectedTestTasks = None // scalaprops not supported in `TestModule`
      )
    )
  }
}

object MillInitScalaCsv200Tests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 34 KB
    - originally sbt 1.10.0
     */
    val url = "https://github.com/tototoshi/scala-csv/archive/refs/tags/2.0.0.zip"

    test - integrationTest(url) { tester =>
      bumpSbtTo1107(tester.workspacePath)

      // Cross builds are not supported yet.
      tester.testMillInit(
        expectedCompileTasks = Some(SplitResolvedTasks(Seq(), Seq("compile", "test.compile"))),
        expectedTestTasks = Some(SplitResolvedTasks(Seq(), Seq("test")))
      )
    }
  }
}

object MillInitScalaCsv136Tests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 28 KB
    - originally sbt 1.2.8
     */
    val url = "https://github.com/tototoshi/scala-csv/archive/refs/tags/1.3.6.zip"

    test - integrationTest(url) { tester =>
      bumpSbtTo1107(tester.workspacePath)

      tester.testMillInit(
        expectedCompileTasks = Some(SplitResolvedTasks(
          Seq("compile", "test.compile"),
          Seq.empty
        )),
        expectedTestTasks = Some(SplitResolvedTasks(
          Seq(),
          /*
          Relative paths to the workspace are used in the test sources such as `new File("src/test/resources/simple.csv")`
          and they seem to cause the test to fail with Mill:
          ```text
          java.io.FileNotFoundException: src/test/resources/simple.csv (No such file or directory)
          ```
           */
          Seq("test")
        ))
      )
    }
  }
}

// same as the one in the unit tests
object MillInitSbtMultiProjectExampleTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 12 KB
    - originally sbt 1.0.2
     */
    val url =
      "https://github.com/pbassiner/sbt-multi-project-example/archive/152b31df9837115b183576b0080628b43c505389.zip"

    test - integrationTest(url) { tester =>
      bumpSbtTo1107(tester.workspacePath)

      val submodules = Seq("common", "multi1", "multi2")

      tester.testMillInit(
        expectedCompileTasks =
          Some(if (System.getProperty("java.version").split('.').head.toInt <= 11)
            SplitResolvedTasks(
              Seq("compile") ++ submodules.map(_.compileTask),
              // probably due to inheriting `MavenTests` instead of `SbtTests`
              submodules.map(_.testCompileTask)
            )
          else {
            // Submodules don't compile well with JDK 17 and 21, which seems to be due to incompatible bytecode versions in dependencies.
            val succeededSubmoduleCompileTasks = Seq("common.compile", "multi2.compile")
            SplitResolvedTasks(
              Seq("compile") ++ succeededSubmoduleCompileTasks,
              (submodules.flatMap(_.allCompileTasks).toSet -- succeededSubmoduleCompileTasks).toSeq
            )
          }),
        expectedTestTasks = Some(SplitResolvedTasks(Seq(), submodules.map(_.testTask)))
      )
    }
  }
}

// relatively large libraries

object MillInitZioHttpTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 1.4 MB
    - originally sbt 1.10.0
     */
    val url = "https://github.com/zio/zio-http/archive/refs/tags/v3.0.1.zip"

    test - integrationTest(url) { tester =>
      bumpSbtTo1107(tester.workspacePath)

      Seq(
        "sbt-zio-http-grpc-tests",
        "sbt-zio-http-grpc",
        "zio-http-benchmarks.compile",
        "zio-http-cli",
        "zio-http-docs",
        "zio-http-example",
        "zio-http-gen",
        "zio-http-htmx",
        "zio-http-testkit",
        "zio-http-tools",
        "zio-http.js",
        "zio-http.jvm"
      )

      // TODO
      /*
      // probably due to inheriting `MavenTests` instead of `SbtTests`
      // Some dependencies with currently unsupported `CrossVersion` `For3Use2_13` are not imported properly
      tester.testMillInit(
        expectedCompileTasks = Some(SplitResolvedTasks(Seq("compile"), submodules.flatMap(_.allCompileTasks))),
        expectedTestTasks = Some(SplitResolvedTasks(Seq(), submodules.flatMap(_.allTestTasks)))
      )
       */
    }
  }
}

// Scala.js and scala-native projects are not properly imported
object MillInitSbtScalazTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 0.8 MB
    - sbt 1.9.7
     */
    val url = "https://github.com/scalaz/scalaz/archive/refs/tags/v7.3.8.zip"

    test - integrationTest(url) { tester =>
      import tester.*

      if (!Util.isWindows)
        os.call(("chmod", "+x", "sbt"), cwd = workspacePath)

      val initResult = eval(defaultInitCommand, stdout = os.Inherit, stderr = os.Inherit)
      assert(initResult.isSuccess)

      val compileResult = eval("compile")
      assert(compileResult.isSuccess)

      // TODO
      val resolveCompilesResult = eval(("resolve", "__.compile"))
      println("Resolve result: " + resolveCompilesResult.out)
    }
  }
}

// Scala.js and scala-native projects are not properly imported
object MillInitSbtCatsTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 1.9 MB
    - sbt 1.10.7
    - MUnit
     */
    val url = "https://github.com/typelevel/cats/archive/refs/tags/v2.13.0.zip"

    test - integrationTest(url) { tester =>
      import tester.*

      val initResult = eval(defaultInitCommand, stdout = os.Inherit, stderr = os.Inherit)
      assert(initResult.isSuccess)

      val compileResult = eval("compile")
      assert(compileResult.isSuccess)
    }
  }
}

// Converting child projects nested in a parent directory which is not a project is not supported yet.
object MillInitSbtPlayFrameworkTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    // Commented out as it causes `java.util.concurrent.TimeoutException: Future timed out after [600000 milliseconds]` in the CI.
    /*
    /*
    - 4.8 MB
    - sbt 1.10.5
     */
    val url = "https://github.com/playframework/playframework/archive/refs/tags/3.0.6.zip"

    test - integrationTest(url) { tester =>
      import tester.*

      val initResult = eval(defaultInitCommand, stdout = os.Inherit, stderr = os.Inherit)
      assert(initResult.isSuccess)

      val compileResult = eval("compile")
      assert(compileResult.isSuccess)
    }
     */
  }
}

// Scala.js and scala-native projects are not properly imported
object MillInitSbtScalaCheckTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 245 KB
    - originally sbt 1.10.1
     */
    val url = "https://github.com/typelevel/scalacheck/archive/refs/tags/v1.18.1.zip"

    test - integrationTest(url) { tester =>
      import tester.*

      bumpSbtTo1107(workspacePath)

      val initResult = eval(defaultInitCommand, stdout = os.Inherit, stderr = os.Inherit)
      assert(initResult.isSuccess)

      val compileResult = eval("compile")
      assert(compileResult.isSuccess)
    }
  }
}
