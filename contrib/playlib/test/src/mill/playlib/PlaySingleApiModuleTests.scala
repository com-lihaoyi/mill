package mill.playlib

import mill.{T, Task}
import mill.testkit.{TestBaseModule, UnitTester}
import utest.{TestSuite, Tests, assert, _}
import mill.main.TokenReaders._
object PlaySingleApiModuleTests extends TestSuite with PlayTestSuite {

  object playsingleapi extends TestBaseModule with PlayApiModule with SingleModule {
    override val millSourcePath = os.temp() // workaround problem in `SingleModule`
    override def playVersion = Task { testPlay28 }
    override def scalaVersion = Task { "2.13.12" }
    object test extends PlayTests

    def millDiscover = Discover[this.type]
  }

  val resourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "playsingleapi"

  def tests: Tests = Tests {
    test("playVersion") {
      test("fromBuild") - UnitTester(playsingleapi, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(playsingleapi.playVersion)
        assert(
          result.value == testPlay28,
          result.evalCount > 0
        )
      }
    }
    test("layout") {
      test("fromBuild") - UnitTester(playsingleapi, resourcePath).scoped { eval =>
        val Right(conf) = eval.apply(playsingleapi.conf)
        val Right(app) = eval.apply(playsingleapi.app)
        val Right(sources) = eval.apply(playsingleapi.sources)
        val Right(resources) = eval.apply(playsingleapi.resources)
        val Right(testSources) = eval.apply(playsingleapi.test.sources)
        val Right(testResources) = eval.apply(playsingleapi.test.resources)
        assert(
          conf.value.map(_.path.relativeTo(playsingleapi.millSourcePath).toString()) == Seq(
            "conf"
          ),
          app.value.map(_.path.relativeTo(playsingleapi.millSourcePath).toString()) == Seq("app"),
          sources.value == app.value,
          resources.value == conf.value,
          testSources.value.map(
            _.path.relativeTo(playsingleapi.millSourcePath).toString()
          ) == Seq(
            "test"
          ),
          testResources.value.map(
            _.path.relativeTo(playsingleapi.millSourcePath).toString()
          ) == Seq(
            "test/resources"
          )
        )
      }
    }
    test("compile") - UnitTester(playsingleapi, resourcePath).scoped { eval =>
      val eitherResult = eval.apply(playsingleapi.compile)
      val Right(result) = eitherResult
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

      val Right(result2) = eval.apply(playsingleapi.compile)

      assert(result2.evalCount == 0)
    }
  }
}
