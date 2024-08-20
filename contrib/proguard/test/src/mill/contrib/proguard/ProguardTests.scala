package mill.contrib.proguard

import mill._
import mill.define.Target
import mill.util.Util.millProjectModule
import mill.scalalib.ScalaModule
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import os.Path
import utest._
import utest.framework.TestPath

object ProguardTests extends TestSuite {

  object proguard extends TestBaseModule with ScalaModule with Proguard {
    override def scalaVersion: T[String] = T(sys.props.getOrElse("MILL_SCALA_2_13_VERSION", ???))

    def proguardContribClasspath = T {
      millProjectModule("mill-contrib-proguard", repositoriesTask())
    }

    override def runClasspath: Target[Seq[PathRef]] =
      T { super.runClasspath() ++ proguardContribClasspath() }

  }

  val testModuleSourcesPath: Path =
    os.pwd / "contrib" / "proguard" / "test" / "resources" / "proguard"

  def workspaceTest[T](m: mill.testkit.TestBaseModule)(t: UnitTester => T)(
      implicit tp: TestPath
  ): T = {
    val eval = new UnitTester(m, debugEnabled = true)
    os.remove.all(m.millSourcePath)
    os.remove.all(eval.outPath)
    os.makeDir.all(m.millSourcePath / os.up)
    os.copy(testModuleSourcesPath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {
    test("Proguard module") {
      test("should download proguard jars") - workspaceTest(proguard) { eval =>
        val Right(result) = eval.apply(proguard.proguardClasspath)
        assert(
          result.value.iterator.toSeq.nonEmpty,
          result.value.iterator.toSeq.head.path.toString().contains("proguard-base")
        )
      }

      test("should create a proguarded jar") - workspaceTest(proguard) { eval =>
        val Right(result) = eval.apply(proguard.proguard)
        assert(os.exists(result.value.path))
      }
    }
  }
}
