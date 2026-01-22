package mill.javalib

import mill.*
import mill.testkit.UnitTester
import utest.*

import java.util.jar.JarFile

object AssemblyTests extends TestSuite with AssemblyTestUtils {

  object HelloJavaWithMain extends mill.testkit.TestRootModule {
    object core extends JavaModule
    object app extends JavaModule {
      override def moduleDeps = Seq(core)
      override def mainClass: T[Option[String]] = Some("hello.Main")
    }

    lazy val millDiscover = mill.api.Discover[this.type]
  }

  def tests: Tests = Tests {

    test("assembly") {
      test("assembly") - UnitTester(HelloJavaWithMain, resourcePath).scoped {
        eval =>
          val Right(result) =
            eval.apply(HelloJavaWithMain.app.assembly): @unchecked
          assert(
            os.exists(result.value.path),
            result.evalCount > 0
          )
          val jarFile = new JarFile(result.value.path.toIO)
          try {
            val entries = jarEntries(jarFile)

            val mainPresent = entries.contains("hello/Main.class")
            assert(mainPresent)

            val mainClass = jarMainClass(jarFile)
            assert(mainClass.contains("hello.Main"))
          } finally jarFile.close()
      }

      test("run") - UnitTester(HelloJavaWithMain, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloJavaWithMain.app.assembly): @unchecked

        assert(
          os.exists(result.value.path),
          result.evalCount > 0
        )

        val runResult = os.proc("java", "-jar", result.value.path, "testArg").call(cwd = eval.outPath)

        assert(runResult.exitCode == 0)
      }
    }
  }

}
