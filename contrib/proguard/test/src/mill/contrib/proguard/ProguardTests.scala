package mill.contrib.proguard

import mill.*
import mill.api.Discover
import mill.scalalib.ScalaModule
import mill.testkit.{TestRootModule, UnitTester}
import mill.util.Jvm
import os.Path
import utest.*

object ProguardTests extends TestSuite {

  object proguard extends TestRootModule with ScalaModule with Proguard {
    // TODO: This test works for a Scala 2.13 App, but not for a Scala 3 App, probably due to tasty files
    override def scalaVersion: T[String] = Task.Input {
      sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???)
    }
    def proguardVersion = Task.Input {
      sys.props.getOrElse("TEST_PROGUARD_VERSION", ???)
    }

    lazy val millDiscover = Discover[this.type]
  }

  val testModuleSourcesPath: Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "proguard"

  def tests: Tests = Tests {
    test("Proguard module") {
      test("should download proguard jars") - UnitTester(proguard, testModuleSourcesPath).scoped {
        eval =>
          val Right(result) = eval.apply(proguard.proguardClasspath): @unchecked
          assertAll(
            result.value.iterator.toSeq.nonEmpty,
            result.value.iterator.toSeq.head.path.toString().contains("proguard-base")
          )
      }

      test("assembly jar") - UnitTester(proguard, testModuleSourcesPath).scoped {
        eval =>
          // Not sure why this is broken in Scala 3
          val Right(result) = eval.apply(proguard.assembly): @unchecked
          assert(os.exists(result.value.path))

          val res = os.call(
            cmd = (Jvm.javaExe, "-jar", result.value.path, "world"),
            mergeErrIntoOut = true,
            check = false
          )
          assertAll(
            res.exitCode == 0,
            res.out.text().contains("Hello world!")
          )
          s"jar size: ${os.size(result.value.path)}"
      }

      test("should create a proguarded jar") - UnitTester(proguard, testModuleSourcesPath).scoped {
        eval =>
          val Right(result) = eval.apply(proguard.proguard): @unchecked
          assert(os.exists(result.value.path))

          val res = os.call(
            cmd = (Jvm.javaExe, "-jar", result.value.path, "proguarded", "world"),
            mergeErrIntoOut = true,
            check = false
          )
          assertAll(
            res.exitCode == 0,
            res.out.text().contains("Hello proguarded world!")
          )
          s"jar size: ${os.size(result.value.path)}"
      }
    }
  }
}
