package mill.integration

import mill.constants.CacheFiles
import mill.testkit.{IntegrationTester, UtestIntegrationTestSuite}
import utest.*

object MillScalaVersionTests extends UtestIntegrationTestSuite {
  private def writeBuildMill(tester: IntegrationTester, scalaVersion: Option[String]): Unit = {
    val scalaVersionLine = scalaVersion.fold("")(v => s"//| mill-scala-version: $v")
    os.write.over(
      tester.workspacePath / "build.mill",
      s"""$scalaVersionLine
         |package build
         |
         |// empty file
         |""".stripMargin
    )
  }

  private def readResolveRunner(tester: IntegrationTester) =
    os.read.lines(tester.workspacePath / "out" / CacheFiles.filename(CacheFiles.resolveRunner))

  private def scalaLibraryFor(version: String) =
    s"org/scala-lang/scala3-library_3/$version/scala3-library_3-$version.jar"

  private def scalaCompilerFor(version: String) =
    s"org/scala-lang/scala3-compiler_3/$version/scala3-compiler_3-$version.jar"

  private val ScalaVersion = "3.7.2-RC1"

  val tests: Tests = Tests {
    test("noDirective") - integrationTest { tester =>
      import tester.*

      writeBuildMill(tester, scalaVersion = None)
      val res = eval("version")
      assert(res.isSuccess)

      val lines = readResolveRunner(tester)
      assert(!lines.exists(_.contains(scalaCompilerFor(ScalaVersion))))
      assert(!lines.exists(_.contains(scalaLibraryFor(ScalaVersion))))
    }

    test("withDirective") - integrationTest { tester =>
      import tester.*

      writeBuildMill(tester, scalaVersion = Some(ScalaVersion))
      val res = eval("version")
      assert(res.isSuccess)

      val lines = readResolveRunner(tester)
      assert(lines.exists(_.contains(scalaCompilerFor(ScalaVersion))))
      assert(lines.exists(_.contains(scalaLibraryFor(ScalaVersion))))
    }

    test("withDirectiveAndThenWithout") - integrationTest { tester =>
      import tester.*

      {
        writeBuildMill(tester, scalaVersion = Some(ScalaVersion))
        val res = eval("version")
        assert(res.isSuccess)

        val lines = readResolveRunner(tester)
        assert(lines.exists(_.contains(scalaCompilerFor(ScalaVersion))))
        assert(lines.exists(_.contains(scalaLibraryFor(ScalaVersion))))
      }

      {
        // This should recompile the build with the new version.
        writeBuildMill(tester, scalaVersion = None)
        val res = eval("version")
        assert(res.isSuccess)

        val lines = readResolveRunner(tester)
        assert(!lines.exists(_.contains(scalaCompilerFor(ScalaVersion))))
        assert(!lines.exists(_.contains(scalaLibraryFor(ScalaVersion))))
      }
    }

  }
}
