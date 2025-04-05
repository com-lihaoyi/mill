package mill.playlib

import mill.Task
import mill.testkit.{TestBaseModule, UnitTester}
import utest.{TestSuite, Tests, assert, _}
import mill.define.Discover
import mill.main.TokenReaders._
object PlaySingleApiModuleTests extends TestSuite with PlayTestSuite {

  object playsingleapi extends TestBaseModule with PlayApiModule {
    override val moduleDir = os.temp() // workaround problem in `SingleModule`
    override def playVersion = Task { testPlay28 }
    override def scalaVersion = Task { "2.13.12" }
    object test extends PlayTests

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "playsingleapi"

  def tests: Tests = Tests {
    test("playVersion") {
      test("fromBuild") - UnitTester(playsingleapi, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(playsingleapi.playVersion): @unchecked
        assert(
          result.value == testPlay28,
          result.evalCount > 0
        )
      }
    }
    test("layout") {
      test("fromBuild") - UnitTester(playsingleapi, resourcePath).scoped { eval =>
        val Right(conf) = eval.apply(playsingleapi.conf): @unchecked
        val Right(app) = eval.apply(playsingleapi.app): @unchecked
        val Right(sources) = eval.apply(playsingleapi.sources): @unchecked
        val Right(resources) = eval.apply(playsingleapi.resources): @unchecked
        val Right(testSources) = eval.apply(playsingleapi.test.sources): @unchecked
        val Right(testResources) = eval.apply(playsingleapi.test.resources): @unchecked
        assert(
          conf.value.map(_.path.relativeTo(playsingleapi.moduleDir).toString()) == Seq(
            "conf"
          ),
          app.value.map(_.path.relativeTo(playsingleapi.moduleDir).toString()) == Seq("app"),
          sources.value == app.value,
          resources.value == conf.value,
          testSources.value.map(
            _.path.relativeTo(playsingleapi.moduleDir).toString()
          ) == Seq(
            "test"
          ),
          testResources.value.map(
            _.path.relativeTo(playsingleapi.moduleDir).toString()
          ) == Seq(
            "test/resources"
          )
        )
      }
    }
    test("compile") - UnitTester(playsingleapi, resourcePath).scoped { eval =>
      val eitherResult = eval.apply(playsingleapi.compile)
      val Right(result) = eitherResult: @unchecked
      val outputFiles = os.walk(result.value.classes.path).filter(os.isFile)
      val expectedClassfiles = Seq[os.RelPath](
        os.RelPath("controllers/HomeController.class"),
        os.RelPath("controllers/ReverseAssets.class"),
        os.RelPath("controllers/ReverseHomeController.class"),
        os.RelPath("controllers/routes.class"),
        os.RelPath("controllers/routes$javascript.class"),
        os.RelPath("controllers/javascript/ReverseHomeController.class"),
        os.RelPath("controllers/javascript/ReverseAssets.class"),
        os.RelPath("router/Routes$$anonfun$routes$1.class"),
        os.RelPath("router/Routes.class"),
        os.RelPath("router/RoutesPrefix$.class"),
        os.RelPath("router/RoutesPrefix.class")
      ).map(
        eval.outPath / "compile.dest/classes" / _
      )
      assert(
        result.value.classes.path == eval.outPath / "compile.dest/classes",
        outputFiles.nonEmpty,
        outputFiles.forall(expectedClassfiles.contains),
        outputFiles.size == 11,
        result.evalCount > 0
      )

      val Right(result2) = eval.apply(playsingleapi.compile): @unchecked

      assert(result2.evalCount == 1)
    }
  }
}
