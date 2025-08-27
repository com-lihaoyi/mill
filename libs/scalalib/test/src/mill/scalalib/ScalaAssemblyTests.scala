package mill.scalalib

import mill.*
import mill.scalalib.HelloWorldTests.*
import mill.testkit.UnitTester
import utest.*

import java.util.jar.JarFile

object ScalaAssemblyTests extends TestSuite with ScalaAssemblyTestUtils {

  def tests: Tests = Tests {

    test("assembly") {
      test("assembly") - UnitTester(HelloWorldTests.HelloWorldWithMain, resourcePath).scoped {
        eval =>
          val Right(result) =
            eval.apply(HelloWorldTests.HelloWorldWithMain.core.assembly): @unchecked
          assert(
            os.exists(result.value.path),
            result.evalCount > 0
          )
          val jarFile = new JarFile(result.value.path.toIO)
          try {
            val entries = jarEntries(jarFile)

            val mainPresent = entries.contains("Main.class")
            assert(mainPresent)
            assert(entries.exists(s => s.contains("scala/Predef.class")))

            val mainClass = jarMainClass(jarFile)
            assert(mainClass.contains("Main"))
          } finally jarFile.close()
      }

      test("run") - UnitTester(HelloWorldTests.HelloWorldWithMain, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldTests.HelloWorldWithMain.core.assembly): @unchecked

        assert(
          os.exists(result.value.path),
          result.evalCount > 0
        )
        val runResult = eval.outPath / "hello-mill"

        os.proc("java", "-jar", result.value.path, runResult).call(cwd = eval.outPath)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
    }
  }

}
