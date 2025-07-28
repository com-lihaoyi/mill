package mill
package kotlinlib
package js

import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.{TestSuite, Tests, assert, assertAll, test}

object KotlinJsCompileTests extends TestSuite {

  private val kotlinVersion = "1.9.25"

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "kotlin-js"

  object module extends TestRootModule {

    object bar extends KotlinJsModule {
      def kotlinVersion = KotlinJsCompileTests.kotlinVersion
    }

    object foo extends KotlinJsModule {
      override def kotlinVersion = KotlinJsCompileTests.kotlinVersion
      override def moduleDeps = Seq(module.bar)
    }

    lazy val millDiscover = Discover[this.type]
  }

  private def testEval() = UnitTester(module, resourcePath)

  def tests: Tests = Tests {
    test("compile") {
      testEval().scoped { eval =>

        val Right(result) = eval.apply(module.foo.compile): @unchecked

        val irDir = result.value.classes.path
        assertAll(
          os.isDir(irDir),
          os.exists(irDir / "default/manifest"),
          os.exists(irDir / "default/linkdata/package_foo"),
          !os.walk(irDir).exists(_.ext == "klib")
        )
      }
    }

    test("failures") {
      testEval().scoped { eval =>

        val compilationUnit = module.foo.moduleDir / "src/foo/Hello.kt"

        val Right(_) = eval.apply(module.foo.compile): @unchecked

        os.write.over(compilationUnit, os.read(compilationUnit) + "}")

        val Left(_) = eval.apply(module.foo.compile): @unchecked

        os.write.over(compilationUnit, os.read(compilationUnit).dropRight(1))

        val Right(_) = eval.apply(module.foo.compile): @unchecked
      }
    }
  }

}
