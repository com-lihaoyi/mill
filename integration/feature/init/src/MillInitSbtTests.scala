package mill.integration

import mill.integration.MillInitSbtTests.{bumpSbtTo1107, initCommand}
import utest.*

object MillInitSbtTests {
  val initCommand = ("init", "--base-module", "BaseModule", "--deps-object", "Deps", "--merge")
  def bumpSbtTo1107(workspacePath: os.Path) =
    // bump sbt version to resolve compatibility issues with lower sbt versions and higher JDK versions
    os.write.over(workspacePath / "project" / "build.properties", "sbt.version = 1.10.7")
}

// relatively small libraries

object MillInitLibraryExampleTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 21 KB
    - sbt 1.5.2
     */
    val url = "https://github.com/scalacenter/library-example/archive/refs/tags/v1.0.1.zip"

    test - integrationTest(url) { tester =>
      import tester.*

      val initResult = eval(initCommand, stdout = os.Inherit, stderr = os.Inherit)
      assert(initResult.isSuccess)

      val compileResult = eval("compile")
      assert(compileResult.isSuccess)
    }
  }
}

object MillInitScalaCsv200Tests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 34 KB
    - sbt 1.10.7
     */
    val url = "https://github.com/tototoshi/scala-csv/archive/refs/tags/2.0.0.zip"

    test - integrationTest(url) { tester =>
      import tester.*

      val initResult = eval(initCommand, stdout = os.Inherit, stderr = os.Inherit)
      assert(initResult.isSuccess)

      val compileResult = eval("compile")
      // Cross builds are not supported yet.
      assert(!compileResult.isSuccess)
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
      import tester.*

      bumpSbtTo1107(workspacePath)

      val initResult = eval(initCommand, stdout = os.Inherit, stderr = os.Inherit)
      assert(initResult.isSuccess)

      val compileResult = eval("compile")
      // It works here, but scala 2.11 with JDK 6 seems not supported when run with JDK 17 in shell.
      assert(compileResult.isSuccess)
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

      val initResult = eval(initCommand, stdout = os.Inherit, stderr = os.Inherit)
      assert(initResult.isSuccess)

      val compileResult = eval("compile")
      assert(compileResult.isSuccess)

      // Submodules don't compile well, which seems to be due to incompatible bytecode versions in dependencies.
      val compileSubmodulesResult = eval("_.compile")
      assert(!compileSubmodulesResult.isSuccess)
    }
  }
}

// relatively large libraries

object MillInitZioHttpTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 1.4 MB
    - sbt 1.10.6
     */
    val url = "https://github.com/zio/zio-http/archive/refs/tags/v3.0.1.zip"

    test - integrationTest(url) { tester =>
      import tester.*

      val initResult = eval(initCommand, stdout = os.Inherit, stderr = os.Inherit)
      assert(initResult.isSuccess)

      val compileResult = eval("compile")
      assert(compileResult.isSuccess)

      val compileSubmodulesResult = eval("_.compile")
      // Some dependencies with currently unsupported `CrossVersion` `For3Use2_13` are not imported properly
      assert(!compileSubmodulesResult.isSuccess)
    }
  }
}

// Scala.js and scala-native projects are not properly imported
object MillInitSbtScalazTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 0.8 MB
    - sbt 1.10.7
     */
    val url = "https://github.com/scalaz/scalaz/archive/refs/tags/v7.3.8.zip"

    test - integrationTest(url) { tester =>
      import tester.*

      os.call(("chmod", "+x", "sbt"), cwd = workspacePath)

      val initResult = eval(initCommand, stdout = os.Inherit, stderr = os.Inherit)
      assert(initResult.isSuccess)

      val compileResult = eval("compile")
      assert(compileResult.isSuccess)
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

      val initResult = eval(initCommand, stdout = os.Inherit, stderr = os.Inherit)
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

      val initResult = eval(initCommand, stdout = os.Inherit, stderr = os.Inherit)
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
    - sbt 1.10.1
     */
    val url = "https://github.com/typelevel/scalacheck/archive/refs/tags/v1.18.1.zip"

    test - integrationTest(url) { tester =>
      import tester.*

      val initResult = eval(initCommand, stdout = os.Inherit, stderr = os.Inherit)
      assert(initResult.isSuccess)

      val compileResult = eval("compile")
      assert(compileResult.isSuccess)
    }
  }
}
