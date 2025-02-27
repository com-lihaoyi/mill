package mill.integration

import mill.integration.MillInitSbtUtils.bumpSbtTo1107
import mill.integration.MillInitUtils.*
import utest.*

object MillInitSbtUtils {
  def bumpSbtTo1107(workspacePath: os.Path) =
    // bump sbt version to resolve compatibility issues with lower sbt versions and higher JDK versions
    os.write.over(workspacePath / "project" / "build.properties", "sbt.version = 1.10.7")

  // relatively small libraries

  val scalaPlatforms = Seq("js", "jvm", "native")
}

object MillInitScala3ExampleProjectTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 17 KB
    - sbt 1.10.7
     */
    val url =
      "https://github.com/scala/scala3-example-project/archive/853808c50601e88edaa7272bcfb887b96be0e22a.zip"

    test - integrationTest(url)(
      testMillInit(
        _,
        expectedCompileTasks =
          Some(SplitResolvedTasks(successful = Seq("compile", "test.compile"), failed = Seq.empty)),
        expectedTestTasks = Some(SplitResolvedTasks(successful = Seq("test"), failed = Seq.empty))
      )
    )
  }
}

object MillInitSbtScalaCsv200Tests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 34 KB
    - originally sbt 1.10.0
     */
    val url = "https://github.com/tototoshi/scala-csv/archive/refs/tags/2.0.0.zip"

    test - integrationTest(url) { tester =>
      bumpSbtTo1107(tester.workspacePath)

      // Cross-builds are not supported yet.
      testMillInit(
        tester,
        expectedCompileTasks =
          Some(SplitResolvedTasks(successful = Seq(), failed = Seq("compile", "test.compile"))),
        expectedTestTasks = Some(SplitResolvedTasks(successful = Seq(), failed = Seq("test")))
      )
    }
  }
}

object MillInitSbtScalaCsv136Tests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 28 KB
    - originally sbt 1.2.8
     */
    val url = "https://github.com/tototoshi/scala-csv/archive/refs/tags/1.3.6.zip"

    test - integrationTest(url) { tester =>
      import tester.*
      bumpSbtTo1107(workspacePath)

      testMillInit(
        tester,
        expectedCompileTasks =
          Some(SplitResolvedTasks(successful = Seq("compile", "test.compile"), failed = Seq.empty)),
        expectedTestTasks = Some(SplitResolvedTasks(successful = Seq("test"), failed = Seq.empty))
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
      import tester.*
      bumpSbtTo1107(workspacePath)
      /*
      `multi1.compile` doesn't work well when Mill is run with JDK 17 and 21:
      ```text
      1 tasks failed
      multi1.compile java.io.IOError: java.lang.RuntimeException: /packages cannot be represented as URI
          java.base/jdk.internal.jrtfs.JrtPath.toUri(JrtPath.java:175)
          scala.tools.nsc.classpath.JrtClassPath.asURLs(DirectoryClassPath.scala:183)
          ...
      ```
      Passing a `jvmId` 11 doesn't work here.
       */
      writeMillJvmVersionTemurin11(workspacePath)

      val submodules = Seq("common", "multi1", "multi2")
      testMillInit(
        tester,
        expectedCompileTasks = Some(SplitResolvedTasks(
          successful = Seq("compile") ++ submodules.flatMap(allCompileTasks),
          failed = Seq.empty
        )),
        expectedTestTasks =
          Some(SplitResolvedTasks(successful = submodules.map(testTask), failed = Seq.empty))
      )
    }
  }
}

// relatively large libraries

object MillInitSbtGatlingTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 1.8 MB
    - sbt 1.10.7
     */
    val url = "https://github.com/gatling/gatling/archive/refs/tags/v3.13.4.zip"

    val submodules = Seq(
      "gatling-app",
      "gatling-benchmarks",
      "gatling-charts",
      "gatling-commons",
      "gatling-core-java",
      "gatling-core",
      "gatling-http-client",
      "gatling-http-java",
      "gatling-http",
      "gatling-jdbc-java",
      "gatling-jdbc",
      "gatling-jms-java",
      "gatling-jms",
      "gatling-jsonpath",
      "gatling-netty-util",
      "gatling-quicklens",
      "gatling-recorder",
      "gatling-redis-java",
      "gatling-redis",
      "gatling-samples",
      "gatling-test-framework"
    )
    val submodulesWithoutTests = Seq(
      "gatling-app",
      "gatling-benchmarks",
      "gatling-quicklens",
      "gatling-samples",
      "gatling-test-framework"
    )
    val submodulesWithTests = submodules diff submodulesWithoutTests

    test - integrationTest(url) { tester =>
      testMillInit(
        tester,
        expectedCompileTasks = Some(SplitResolvedTasks(
          all = Seq("compile")
            ++ submodulesWithTests.flatMap(allCompileTasks)
            ++ submodulesWithoutTests.map(compileTask),
          failed = Seq(
            /*
            `...gatling-benchmarks/src/main/scala/io/gatling/Utils.scala:26:19: no arguments allowed for nullary method toString: (): String`
            This fails to compile with sbt too.
             */
            "gatling-benchmarks.compile"
          )
        )),
        expectedTestTasks = Some(SplitResolvedTasks(
          all = submodulesWithTests.map(testTask),
          failed = Seq(
            /*
            `java.util.MissingResourceException: Can't find bundle for base name gatling-version, locale ...`
            The version file in resources `gatling-commons/src/main/resources/gatling-version.properties`
            is generated by a custom sbt task `generateVersionFileSettings`
            and therefore missing after conversion.
             */
            //
            "gatling-charts.test"
          )
        ))
      )
    }
  }
}
