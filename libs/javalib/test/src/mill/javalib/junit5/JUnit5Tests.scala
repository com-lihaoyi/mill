package mill.javalib.junit5

import mill.api.Task.Simple
import mill.api.{Discover, Module, Task}
import mill.javalib.JavaModule
import mill.javalib.TestModule
import mill.javalib.DepSyntax
import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import mill.util.TokenReaders._

object JUnit5Tests extends TestSuite {

  val junitVersion = "5.13.4"

  object module extends TestRootModule with JavaModule {
    object test extends JavaTests with TestModule.Junit5
    lazy val millDiscover = Discover[this.type]

    object deps extends Module {

      object junitBom extends JavaTests with TestModule.Junit5 {
        override def jupiterVersion: Simple[String] = junitVersion
      }

      object junitNoBom extends JavaTests with TestModule.Junit5 {
        // JUnit-BOM available starting with 5.12.0
        override def jupiterVersion: Simple[String] = "5.11.0"
      }
    }

  }

  val testModuleSourcesPath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "junit5"

  def tests = Tests {
    test("discovery") {
      UnitTester(module, testModuleSourcesPath).scoped { eval =>
        val res = eval(module.test.discoveredTestClasses)
        assert(res.isRight)
        assert(res.toOption.get.value == Seq("qux.QuxTests"))
      }
    }
    test("execution") {
      UnitTester(module, testModuleSourcesPath).scoped { eval =>
        val res = eval(module.test.testForked(""))
        assert(res.isRight)
        val qualifiedNames = res.toOption.get.value.results
        assert(qualifiedNames.forall(_.fullyQualifiedName == "qux.QuxTests"))
      }
    }
    test("dependency management") {
      def testEval() = UnitTester(module, testModuleSourcesPath)
      val junitBom = mvn"org.junit:junit-bom:$junitVersion"
      val jupiter = mvn"org.junit.jupiter:junit-jupiter-api:$junitVersion"
      val junitPlatformLauncher = mvn"org.junit.platform:junit-platform-launcher"

      test("jupiter added when version is set") {
        testEval().scoped { eval =>
          val Right(resultDeps) = eval.apply(module.deps.junitBom.mandatoryMvnDeps): @unchecked
          assert(
            resultDeps.value.contains(jupiter)
          )
        }
      }

      test("junit bom & platform are added when version is at least 5.12.0") {
        testEval().scoped { eval =>
          val Right(resultBom) = eval.apply(module.deps.junitBom.bomMvnDeps): @unchecked
          assert(
            resultBom.value.contains(junitBom)
          )

          val Right(resultDeps) = eval.apply(module.deps.junitBom.mandatoryMvnDeps): @unchecked
          assert(
            resultDeps.value.contains(junitPlatformLauncher)
          )
        }
      }

      test("junit bom & platform are NOT added when version is below 5.11.0") {
        testEval().scoped { eval =>
          val Right(resultBom) = eval.apply(module.deps.junitNoBom.bomMvnDeps): @unchecked
          assert(
            !resultBom.value.contains(junitBom)
          )

          val Right(resultDeps) = eval.apply(module.deps.junitNoBom.mandatoryMvnDeps): @unchecked
          assert(
            !resultDeps.value.contains(junitPlatformLauncher)
          )
        }
      }
    }
  }
}
