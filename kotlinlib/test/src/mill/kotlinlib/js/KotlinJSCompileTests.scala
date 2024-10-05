package mill
package kotlinlib
package js

import mill.testkit.{TestBaseModule, UnitTester}
import mill.{Cross, T}
import utest.{TestSuite, Tests, assert, test}

import scala.util.Random

object KotlinJSCompileTests extends TestSuite {

  private val kotlinVersion = "1.9.25"

  private val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "kotlin-js"

  trait KotlinJSCrossModule extends KotlinJSModule with Cross.Module2[Boolean, String] {
    override def kotlinVersion = KotlinJSCompileTests.kotlinVersion
    override def splitPerModule: T[Boolean] = crossValue
    override def kotlinJSBinaryKind: T[Option[BinaryKind]] = crossValue2 match {
      case "none" => None
      case "library" => Some(BinaryKind.Library)
      case "executable" => Some(BinaryKind.Executable)
    }
    override def moduleDeps = Seq(module.bar)
  }

  object module extends TestBaseModule {

    val splitPerModuleOptions: Seq[Boolean] = Seq(true, false)
    val binaryKindOptions: Seq[String] = Seq("none", "library", "executable")

    private val matrix = for {
      splitPerModule <- splitPerModuleOptions
      binaryKind <- binaryKindOptions
    } yield (splitPerModule, binaryKind)

    object bar extends KotlinJSModule {
      def kotlinVersion = KotlinJSCompileTests.kotlinVersion
    }

    object foo extends Cross[KotlinJSCrossModule](matrix)
  }

  private def testEval() = UnitTester(module, resourcePath)

  def tests: Tests = Tests {
    test("compile { js / per module }") {
      val eval = testEval()

      val Right(result) = eval.apply(module.foo(true, "executable").compile)

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

    test("compile { js / fat }") {
      val eval = testEval()

      val Right(result) = eval.apply(module.foo(false, "executable").compile)

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

    test("compile { klib file }") {
      val eval = testEval()

      // klib output should always be a single file, irrespective of the split option
      Seq(true, false).foreach(split => {

        val Right(result) = eval.apply(module.foo(split, "library").compile)

        val library = result.value.classes.path
        assert(
          os.isFile(library),
          library.toString().endsWith("foo.klib")
        )
      })
    }

    test("compile { no binary }") {
      val eval = testEval()

      // no binary output should always be a folder with IR code, irrespective of the split option
      Seq(true, false).foreach(split => {

        val Right(result) = eval.apply(module.foo(split, "none").compile)

        val irDir = result.value.classes.path
        assert(
          os.isDir(irDir),
          os.exists(irDir / "default" / "manifest"),
          os.exists(irDir / "default" / "linkdata" / "package_foo"),
          !os.walk(irDir).exists(_.ext == "klib")
        )
      })
    }

    test("failures") {
      val eval = testEval()
      val split = module.splitPerModuleOptions(Random.nextInt(module.splitPerModuleOptions.length))
      val binaryKind = module.binaryKindOptions(Random.nextInt(module.binaryKindOptions.length))

      val compilationUnit = module.foo.millSourcePath / "src" / "foo" / "Hello.kt"

      val Right(_) = eval.apply(module.foo(split, binaryKind).compile)

      os.write.over(compilationUnit, os.read(compilationUnit) + "}")

      val Left(_) = eval.apply(module.foo(split, binaryKind).compile)

      os.write.over(compilationUnit, os.read(compilationUnit).dropRight(1))

      val Right(_) = eval.apply(module.foo(split, binaryKind).compile)
    }
  }

}
