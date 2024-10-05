package mill
package kotlinlib
package js

import mill.testkit.{TestBaseModule, UnitTester}
import mill.Cross
import utest.{TestSuite, Tests, test}

object KotlinJSKotlinVersionsTests extends TestSuite {

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "kotlin-js"
  private val kotlinLowestVersion = "1.8.20"
  // TODO: Cannot support Kotlin 2+, because it doesn't publish .jar anymore, but .klib files only. Coursier is not
  //  able to work with that (unlike Gradle, which can leverage .module metadata).
  // https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-js/2.0.20/
  private val kotlinHighestVersion = "1.9.25"
  private val kotlinVersions = Seq(kotlinLowestVersion, kotlinHighestVersion)

  trait KotlinJSCrossModule extends KotlinJSModule with Cross.Module[String] {
    def kotlinVersion = crossValue
  }

  trait KotlinJSFooCrossModule extends KotlinJSCrossModule {
    override def moduleDeps = Seq(module.bar(crossValue))
  }

  object module extends TestBaseModule {

    object bar extends Cross[KotlinJSCrossModule](kotlinVersions)
    object foo extends Cross[KotlinJSFooCrossModule](kotlinVersions)
  }

  private def testEval() = UnitTester(module, resourcePath)

  def tests: Tests = Tests {
    test("compile with lowest Kotlin version") {
      val eval = testEval()

      val Right(_) = eval.apply(module.foo(kotlinLowestVersion).compile)
    }

    test("compile with highest Kotlin version") {
      val eval = testEval()

      val Right(_) = eval.apply(module.foo(kotlinHighestVersion).compile)
    }
  }

}
