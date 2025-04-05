package mill.kotlinlib.js

import mill.define.Discover
import mill.testkit.{TestBaseModule, UnitTester}
import mill.{Cross, T}
import utest.{TestSuite, Tests, assert, test}
import mill.util.TokenReaders._
object KotlinJsLinkTests extends TestSuite {

  private val kotlinVersion = "1.9.25"

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "kotlin-js"

  trait KotlinJsCrossModule extends KotlinJsModule with Cross.Module[Boolean] {
    override def kotlinVersion = KotlinJsLinkTests.kotlinVersion
    override def kotlinJsSplitPerModule: T[Boolean] = crossValue
    override def kotlinJsBinaryKind: T[Option[BinaryKind]] = Some(BinaryKind.Executable)
    override def moduleDeps = Seq(module.bar)
    // drop cross-value
    override def artifactNameParts = super.artifactNameParts().dropRight(1)
  }

  object module extends TestBaseModule {

    object bar extends KotlinJsModule {
      def kotlinVersion = KotlinJsLinkTests.kotlinVersion
    }

    object foo extends Cross[KotlinJsCrossModule](Seq(true, false))

    lazy val millDiscover = Discover[this.type]
  }

  private def testEval() = UnitTester(module, resourcePath)

  def tests: Tests = Tests {
    test("link { per module }") {
      val eval = testEval()

      val Right(result) = eval.apply(module.foo(true).linkBinary): @unchecked

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

      val Right(result) = eval.apply(module.foo(false).linkBinary): @unchecked

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
