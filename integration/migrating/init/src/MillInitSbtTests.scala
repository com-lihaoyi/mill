package mill.integration

import mill.constants.Util
import mill.integration.MillInitSbtUtils.bumpSbt
import mill.integration.MillInitUtils.*
import utest.*

import scala.collection.immutable.SortedSet

object MillInitSbtUtils {

  /**
   * bump `sbt` version to our tested version to resolve compatibility issues with lower `sbt` versions and higher JDK versions
   */
  def bumpSbt(workspacePath: os.Path) =
    os.write.over(
      workspacePath / "project" / "build.properties",
      s"sbt.version = ${sys.props.getOrElse("TEST_SBT_VERSION", ???)}"
    )

  // relatively small libraries

  val scalaPlatforms = Seq("js", "jvm", "native")
}

object MillInitScala3ExampleProjectTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 17 KB
    - `sbt` 1.10.7
     */
    val url =
      "https://github.com/scala/scala3-example-project/archive/853808c50601e88edaa7272bcfb887b96be0e22a.zip"

    test - integrationTest(url)(
      testMillInit(
        _,
        expectedAllSourceFileNums = Map("allSourceFiles" -> 13, "test.allSourceFiles" -> 1),
        expectedCompileTaskResults = Some(SplitTaskResults(
          successful = SortedSet("compile", "test.compile"),
          failed = SortedSet.empty
        )),
        expectedTestTaskResults =
          Some(SplitTaskResults(successful = SortedSet("test"), failed = SortedSet.empty))
      )
    )
  }
}

object MillInitSbtScalaCsv200Tests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 34 KB
    - originally `sbt` 1.10.0
     */
    val url = "https://github.com/tototoshi/scala-csv/archive/refs/tags/2.0.0.zip"

    test - integrationTest(url) { tester =>
      bumpSbt(tester.workspacePath)

      // Cross-builds are not supported yet.
      testMillInit(
        tester,
        expectedAllSourceFileNums = Map("allSourceFiles" -> 10, "test.allSourceFiles" -> 6),
        expectedCompileTaskResults =
          Some(SplitTaskResults(
            successful = SortedSet(),
            failed = SortedSet("compile", "test.compile")
          )),
        expectedTestTaskResults =
          Some(SplitTaskResults(successful = SortedSet(), failed = SortedSet("test")))
      )
    }
  }
}

object MillInitSbtScalaCsv136Tests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 28 KB
    - originally `sbt` 1.2.8
     */
    val url = "https://github.com/tototoshi/scala-csv/archive/refs/tags/1.3.6.zip"

    test - integrationTest(url) { tester =>
      import tester._
      bumpSbt(workspacePath)

      testMillInit(
        tester,
        expectedAllSourceFileNums = Map("allSourceFiles" -> 11, "test.allSourceFiles" -> 6),
        expectedCompileTaskResults =
          Some(SplitTaskResults(
            successful = SortedSet("compile", "test.compile"),
            failed = SortedSet.empty
          )),
        expectedTestTaskResults =
          Some(SplitTaskResults(successful = SortedSet("test"), failed = SortedSet.empty))
      )
    }
  }
}

// same as the one in the unit tests
object MillInitSbtMultiProjectExampleTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 12 KB
    - originally `sbt` 1.0.2
     */
    val url =
      "https://github.com/pbassiner/sbt-multi-project-example/archive/152b31df9837115b183576b0080628b43c505389.zip"

    test - integrationTest(url) { tester =>
      import tester._
      bumpSbt(workspacePath)
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

      val submodules = SortedSet("common", "multi1", "multi2")
      testMillInit(
        tester,
        expectedAllSourceFileNums = Map(
          "allSourceFiles" -> 0,
          "multi1.test.allSourceFiles" -> 1,
          "multi1.allSourceFiles" -> 1,
          "multi2.allSourceFiles" -> 1,
          "common.test.allSourceFiles" -> 1,
          "multi2.test.allSourceFiles" -> 1,
          "common.allSourceFiles" -> 1
        ),
        expectedCompileTaskResults = Some(SplitTaskResults(
          successful = SortedSet("compile") ++ submodules.flatMap(allCompileTasks),
          failed = SortedSet.empty
        )),
        expectedTestTaskResults =
          Some(SplitTaskResults(successful = submodules.map(testTask), failed = SortedSet.empty))
      )
    }
  }
}

// relatively large libraries

object MillInitSbtGatlingTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 1.8 MB
    - `sbt` 1.10.7
     */
    val url =
      "https://github.com/gatling/gatling/archive/711b8d4e7ac7aaa8d3173b2d77fb5e9c7843695a.zip"

    val submodules = SortedSet(
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
    val submodulesWithoutTests = SortedSet(
      "gatling-app",
      "gatling-benchmarks",
      "gatling-quicklens",
      "gatling-samples",
      "gatling-test-framework"
    )
    val submodulesWithTests = submodules diff submodulesWithoutTests

    test - integrationTest(url) { tester =>
      // timeout on Windows on CI
      if (!Util.isWindows)
        testMillInit(
          tester,
          expectedAllSourceFileNums = Map(
            "allSourceFiles" -> 0,
            "gatling-http.test.allSourceFiles" -> 32,
            "gatling-jms.allSourceFiles" -> 30,
            "gatling-jdbc.allSourceFiles" -> 2,
            "gatling-redis.allSourceFiles" -> 2,
            "gatling-core.allSourceFiles" -> 178,
            "gatling-commons.allSourceFiles" -> 23,
            "gatling-jdbc.test.allSourceFiles" -> 3,
            "gatling-redis-java.allSourceFiles" -> 3,
            "gatling-http-client.allSourceFiles" -> 89,
            "gatling-quicklens.allSourceFiles" -> 3,
            "gatling-commons.test.allSourceFiles" -> 11,
            "gatling-http-client.test.allSourceFiles" -> 27,
            "gatling-redis-java.test.allSourceFiles" -> 1,
            "gatling-jdbc-java.allSourceFiles" -> 1,
            "gatling-charts.test.allSourceFiles" -> 4,
            "gatling-app.allSourceFiles" -> 10,
            "gatling-jdbc-java.test.allSourceFiles" -> 1,
            "gatling-core.test.allSourceFiles" -> 65,
            "gatling-recorder.allSourceFiles" -> 65,
            "gatling-netty-util.test.allSourceFiles" -> 2,
            "gatling-http-java.allSourceFiles" -> 37,
            "gatling-jms-java.test.allSourceFiles" -> 1,
            "gatling-jms-java.allSourceFiles" -> 13,
            "gatling-netty-util.allSourceFiles" -> 4,
            "gatling-redis.test.allSourceFiles" -> 2,
            "gatling-http.allSourceFiles" -> 169,
            "gatling-charts.allSourceFiles" -> 58,
            "gatling-test-framework.allSourceFiles" -> 5,
            "gatling-recorder.test.allSourceFiles" -> 10,
            "gatling-benchmarks.allSourceFiles" -> 3,
            "gatling-jms.test.allSourceFiles" -> 16,
            "gatling-core-java.test.allSourceFiles" -> 2,
            "gatling-jsonpath.test.allSourceFiles" -> 3,
            "gatling-jsonpath.allSourceFiles" -> 8,
            "gatling-samples.allSourceFiles" -> 12,
            "gatling-core-java.allSourceFiles" -> 86,
            "gatling-http-java.test.allSourceFiles" -> 3
          ),
          expectedCompileTaskResults = Some(SplitTaskResults(
            successful = SortedSet("compile")
              ++ submodulesWithTests.flatMap(allCompileTasks)
              ++ submodulesWithoutTests.map(compileTask),
            failed = SortedSet.empty
          )),
          expectedTestTaskResults = Some(SplitTaskResults(
            all = submodulesWithTests.map(testTask),
            failed = SortedSet(
              /*
              `java.util.MissingResourceException: Can't find bundle for base name gatling-version, locale ...`
              The version file in resources `gatling-commons/src/main/resources/gatling-version.properties`
              is generated by a custom `sbt` task `generateVersionFileSettings`
              and therefore missing after conversion.
               */
              //
              "gatling-charts.test"
            ) ++ (if (Util.isWindows)
                    // This fails on Windows with `sbt` too.
                    Seq("gatling-core.test")
                  else Seq.empty)
          ))
        )
    }
  }
}
