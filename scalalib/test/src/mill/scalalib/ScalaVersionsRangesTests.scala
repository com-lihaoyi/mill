package mill.scalalib

import mill.*
import mill.define.Discover
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.*
import utest.framework.TestPath

object ScalaVersionsRangesTests extends TestSuite {
  object ScalaVersionsRanges extends TestBaseModule {
    object core extends Cross[CoreCrossModule]("2.12.13", "2.13.5", "3.3.3")
    trait CoreCrossModule extends CrossScalaModule
        with CrossScalaVersionRanges {
      object test extends ScalaTests with TestModule.Utest {
        def ivyDeps = Agg(ivy"com.lihaoyi::utest:0.8.5")
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
        val Right(_) = eval(c.test.test()): @unchecked
      }
    }
  }
}
