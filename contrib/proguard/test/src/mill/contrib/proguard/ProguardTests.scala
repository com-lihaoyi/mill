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
    override def scalaVersion: T[String] = T(sys.props.getOrElse("MILL_SCALA_3_NEXT_VERSION", ???))

    def proguardContribClasspath = Task {
      millProjectModule("mill-contrib-proguard", repositoriesTask())
    }

    override def runClasspath: T[Seq[PathRef]] =
      Task { super.runClasspath() ++ proguardContribClasspath() }

  }

  val testModuleSourcesPath: Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "proguard"

  def tests: Tests = Tests {
    test("Proguard module") {
      test("should download proguard jars") - UnitTester(proguard, testModuleSourcesPath).scoped {
        eval =>
          val Right(result) = eval.apply(proguard.proguardClasspath)
          assert(
            result.value.iterator.toSeq.nonEmpty,
            result.value.iterator.toSeq.head.path.toString().contains("proguard-base")
          )
      }

      test("should create a proguarded jar") - UnitTester(proguard, testModuleSourcesPath).scoped {
        eval =>
          val Right(result) = eval.apply(proguard.proguard)
          assert(os.exists(result.value.path))
      }
    }
  }
}
