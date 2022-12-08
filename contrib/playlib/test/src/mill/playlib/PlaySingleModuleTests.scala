package mill.playlib

import mill.define.Cross
import mill.scalalib.CrossScalaModule
import mill.T
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object PlaySingleModuleTests extends TestSuite with PlayTestSuite {

  object playsingle extends TestUtil.BaseModule with PlayModule with SingleModule {
    override def playVersion = T { testPlay28 }
    override def twirlVersion = T { "1.5.1" }
    override def twirlScalaVersion: T[String] =
      T { sys.props.getOrElse("MILL_SCALA_2_13_VERSION", ???) }
    override def scalaVersion = T { sys.props.getOrElse("MILL_SCALA_2_13_VERSION", ???) }
    object test extends PlayTests
  }

  val resourcePath: os.Path = os.pwd / "contrib" / "playlib" / "test" / "resources" / "playsingle"

  def tests: Tests = Tests {
    test("layout") {
      test("fromBuild") {
        workspaceTest(playsingle) { eval =>
          val Right((conf, _)) = eval.apply(playsingle.conf)
          val Right((app, _)) = eval.apply(playsingle.app)
          val Right((sources, _)) = eval.apply(playsingle.sources)
          val Right((resources, _)) = eval.apply(playsingle.resources)
          val Right((testSources, _)) = eval.apply(playsingle.test.sources)
          val Right((testResources, _)) = eval.apply(playsingle.test.resources)
          assert(
            conf.map(_.path.relativeTo(playsingle.millSourcePath).toString()) == Seq("conf"),
            app.map(_.path.relativeTo(playsingle.millSourcePath).toString()) == Seq("app"),
            sources == app,
            resources.map(_.path.relativeTo(playsingle.millSourcePath).toString()).contains("conf"),
            testSources.map(_.path.relativeTo(playsingle.millSourcePath).toString()) == Seq("test"),
            testResources.map(_.path.relativeTo(playsingle.millSourcePath).toString()) == Seq(
              "test/resources"
            )
          )
        }
      }
    }
    test("compile") {
      workspaceTest(playsingle) { eval =>
        val eitherResult = eval.apply(playsingle.compile)
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
          os.RelPath("router/RoutesPrefix.class"),
          os.RelPath("views/html/index$.class"),
          os.RelPath("views/html/index.class"),
          os.RelPath("views/html/main$.class"),
          os.RelPath("views/html/main.class")
        ).map(
          eval.outPath / "compile.dest" / "classes" / _
        )
        assert(
          result.classes.path == eval.outPath / "compile.dest" / "classes",
          outputFiles.nonEmpty,
          outputFiles.forall(expectedClassfiles.contains),
          outputFiles.size == 15,
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) = eval.apply(playsingle.compile)
      // assert(unchangedEvalCount == 0)
      }
    }
  }
}
