package mill.scalalib

import mill.*
import mill.api.Discover
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.*

object ScalaVersionsRangesTests extends TestSuite {

  object ScalaVersionsRanges extends TestRootModule {
    object core extends Cross[CoreCrossModule]("2.12.13", "2.13.5", "3.3.3")

    trait CoreCrossModule extends CrossScalaModule
        with CrossScalaVersionRanges {

      override def semanticDbVersion = "4.8.4" // last version to support these Scala versions

      object test extends ScalaTests with TestModule.Utest {
        override def utestVersion = "0.8.5"

        override def semanticDbVersion = "4.8.4" // last version to support these Scala versions
      }
    }

    lazy val millDiscover = Discover[this.type]
  }
  val resourcePath =
    os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "scala-versions-ranges"

  val tests = Tests {
    test("main with Scala 2.12- and 2.13+ specific code") - UnitTester(
      ScalaVersionsRanges,
      resourcePath
    ).scoped { eval =>
      ScalaVersionsRanges.core.crossModules.map { c =>
        val Right(_) = eval(c.run()): @unchecked
      }
    }
    test("test with Scala 2.12- and 2.13+ specific code") - UnitTester(
      ScalaVersionsRanges,
      resourcePath
    ).scoped { eval =>
      ScalaVersionsRanges.core.crossModules.map { c =>
        val Right(_) = eval(c.test.testForked()): @unchecked
      }
    }
  }
}
