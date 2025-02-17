package mill.integration

import mill.integration.MillInitSbtTests.initCommand
import utest.*

object MillInitSbtTests {
  val initCommand = ("init", "--base-module", "BaseModule", "--deps-object", "Deps", "--merge")
}

// Relatively large libraries

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
    - 2 MB
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
    /*
    - 5 MB
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
  }
}

// Scala.js and scala-native projects are not properly imported
object MillInitSbtScalaCheckTests extends BuildGenTestSuite {

  def tests: Tests = Tests {
    /*
    - 0.5 MB
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

// Relatively small libraries

object MillInitScalaCsvTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 25 KB
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

// same as the one in the unit tests
object MillInitSbtMultiProjectExampleTests extends BuildGenTestSuite {
  def tests: Tests = Tests {
    /*
    - 10 KB
     */
    val url =
      "https://github.com/pbassiner/sbt-multi-project-example/archive/152b31df9837115b183576b0080628b43c505389.zip"

    test - integrationTest(url) { tester =>
      import tester.*

      // sbt version bumped so it works with JDK 21
      os.write.over(workspacePath / "project" / "build.properties", "sbt.version = 1.10.7")

      val initResult = eval(initCommand, stdout = os.Inherit, stderr = os.Inherit)
      assert(initResult.isSuccess)

      val compileResult = eval("compile")
      assert(compileResult.isSuccess)

      val testResult = eval("test")
      assert(testResult.isSuccess)
    }
  }
}
