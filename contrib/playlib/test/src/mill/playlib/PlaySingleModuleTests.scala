package mill.playlib

import mill.{T, Task}
import mill.testkit.{TestBaseModule, UnitTester}
import utest.{TestSuite, Tests, assert, _}

object PlaySingleModuleTests extends TestSuite with PlayTestSuite {

  object playsingle extends TestBaseModule with PlayModule with SingleModule {
    override val millSourcePath = os.temp() // workaround problem in `SingleModule`
    override def playVersion = Task { testPlay28 }
    override def scalaVersion = Task { sys.props.getOrElse("TEST_SCALA_2_13_VERSION", ???) }
    object test extends PlayTests
  }

  val resourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "playsingle"

  def tests: Tests = Tests {
    test("layout") {
      test("fromBuild") - UnitTester(playsingle, resourcePath).scoped { eval =>
        val Right(conf) = eval.apply(playsingle.conf)
        val Right(app) = eval.apply(playsingle.app)
        val Right(sources) = eval.apply(playsingle.sources)
        val Right(resources) = eval.apply(playsingle.resources)
        val Right(testSources) = eval.apply(playsingle.test.sources)
        val Right(testResources) = eval.apply(playsingle.test.resources)
        assert(
          conf.value.map(_.path.relativeTo(playsingle.millSourcePath).toString()) == Seq("conf"),
          app.value.map(_.path.relativeTo(playsingle.millSourcePath).toString()) == Seq("app"),
          sources == app,
          resources.value.map(_.path.relativeTo(playsingle.millSourcePath).toString()).contains(
            "conf"
          ),
          testSources.value.map(_.path.relativeTo(playsingle.millSourcePath).toString()) == Seq(
            "test"
          ),
          testResources.value.map(_.path.relativeTo(playsingle.millSourcePath).toString()) == Seq(
            "test/resources"
          )
        )
      }
    }
    test("compile") - UnitTester(playsingle, resourcePath).scoped { eval =>
      val eitherResult = eval.apply(playsingle.compile)
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
        os.RelPath("router/RoutesPrefix.class"),
        os.RelPath("views/html/index$.class"),
        os.RelPath("views/html/index.class"),
        os.RelPath("views/html/main$.class"),
        os.RelPath("views/html/main.class")
      ).map(
        eval.outPath / "compile.dest/classes" / _
      )
      assert(
        result.value.classes.path == eval.outPath / "compile.dest/classes",
        outputFiles.nonEmpty,
        outputFiles.forall(expectedClassfiles.contains),
        outputFiles.size == 15,
        result.evalCount > 0
      )

      // don't recompile if nothing changed
      val Right(result2) = eval.apply(playsingle.compile)
      // assert(unchangedEvalCount == 0)
    }
  }
}
