package mill.kotlinlib.js

import mill.testkit.{TestBaseModule, UnitTester}
import mill.{Cross, T}
import utest.{TestSuite, Tests, assert, test}

object KotlinJsLinkTests extends TestSuite {

  private val kotlinVersion = "1.9.25"

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "kotlin-js"

  trait KotlinJsCrossModule extends KotlinJsModule with Cross.Module[Boolean] {
    override def kotlinVersion = KotlinJsLinkTests.kotlinVersion
    override def splitPerModule: T[Boolean] = crossValue
    override def kotlinJsBinaryKind: T[Option[BinaryKind]] = Some(BinaryKind.Executable)
    override def moduleDeps = Seq(module.bar)
  }

  object module extends TestBaseModule {

    object bar extends KotlinJsModule {
      def kotlinVersion = KotlinJsLinkTests.kotlinVersion
    }

    object foo extends Cross[KotlinJsCrossModule](Seq(true, false))
  }

  private def testEval() = UnitTester(module, resourcePath)

  def tests: Tests = Tests {
    test("link { per module }") {
      val eval = testEval()

      val Right(result) = eval.apply(module.foo(true).linkBinary)

      val binariesDir = result.value.classes.path
      assert(
        os.isDir(binariesDir),
        os.exists(binariesDir / "foo.js"),
        os.exists(binariesDir / "foo.js.map"),
        os.exists(binariesDir / "bar.js"),
        os.exists(binariesDir / "bar.js.map"),
        os.exists(binariesDir / "kotlin-kotlin-stdlib.js"),
        os.exists(binariesDir / "kotlin-kotlin-stdlib.js.map")
      )
    }

    test("link { fat }") {
      val eval = testEval()

      val Right(result) = eval.apply(module.foo(false).linkBinary)

      val binariesDir = result.value.classes.path
      assert(
        os.isDir(binariesDir),
        os.exists(binariesDir / "foo.js"),
        os.exists(binariesDir / "foo.js.map"),
        !os.exists(binariesDir / "bar.js"),
        !os.exists(binariesDir / "bar.js.map"),
        !os.exists(binariesDir / "kotlin-kotlin-stdlib.js"),
        !os.exists(binariesDir / "kotlin-kotlin-stdlib.js.map")
      )
    }
  }

}
