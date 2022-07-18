package mill.playlib

import mill.T
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object PlaySingleApiModuleTests extends TestSuite with PlayTestSuite {

  object playsingleapi extends TestUtil.BaseModule with PlayApiModule with SingleModule {
    override def playVersion = T { "2.8.16" }
    def twirlVersion = T { "1.5.1" }
    override def scalaVersion = T { "2.13.8" }
    object test extends PlayTests
  }

  val resourcePath: os.Path =
    os.pwd / "contrib" / "playlib" / "test" / "resources" / "playsingleapi"

  def tests: Tests = Tests {
    test("playVersion") {
      test("fromBuild") {
        workspaceTest(playsingleapi) { eval =>
          val Right((result, evalCount)) = eval.apply(playsingleapi.playVersion)
          assert(
            result == "2.8.16",
            evalCount > 0
          )
        }
      }
    }
    test("layout") {
      test("fromBuild") {
        workspaceTest(playsingleapi) { eval =>
          val Right((conf, _)) = eval.apply(playsingleapi.conf)
          val Right((app, _)) = eval.apply(playsingleapi.app)
          val Right((sources, _)) = eval.apply(playsingleapi.sources)
          val Right((resources, _)) = eval.apply(playsingleapi.resources)
          val Right((testSources, _)) = eval.apply(playsingleapi.test.sources)
          val Right((testResources, _)) = eval.apply(playsingleapi.test.resources)
          assert(
            conf.map(_.path.relativeTo(playsingleapi.millSourcePath).toString()) == Seq("conf"),
            app.map(_.path.relativeTo(playsingleapi.millSourcePath).toString()) == Seq("app"),
            sources == app,
            resources == conf,
            testSources.map(_.path.relativeTo(playsingleapi.millSourcePath).toString()) == Seq(
              "test"
            ),
            testResources.map(_.path.relativeTo(playsingleapi.millSourcePath).toString()) == Seq(
              "test/resources"
            )
          )
        }
      }
    }
    test("compile") {
      workspaceTest(playsingleapi) { eval =>
        val eitherResult = eval.apply(playsingleapi.compile)
        val Right((result, evalCount)) = eitherResult
        val outputFiles = os.walk(result.classes.path).filter(os.isFile)
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
          eval.outPath / "compile.dest" / "classes" / _
        )
        assert(
          result.classes.path == eval.outPath / "compile.dest" / "classes",
          outputFiles.nonEmpty,
          outputFiles.forall(expectedClassfiles.contains),
          outputFiles.size == 11,
          evalCount > 0
        )

        val Right((_, unchangedEvalCount)) = eval.apply(playsingleapi.compile)

        assert(unchangedEvalCount == 0)
      }
    }
  }
}
