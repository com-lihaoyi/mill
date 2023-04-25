package mill
package playlib

import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object PlayModuleTests extends TestSuite with PlayTestSuite {

  object playmulti extends TestUtil.BaseModule {
    object core extends Cross[CoreCrossModule](matrix)
    trait CoreCrossModule extends PlayModule with Cross.Module2[String, String] {
      val (crossScalaVersion, crossPlayVersion) = (crossValue, crossValue2)
      override def playVersion = crossPlayVersion
      override def scalaVersion = crossScalaVersion
      override def twirlVersion = "1.5.1"
      override def twirlScalaVersion = sys.props.getOrElse("MILL_SCALA_2_13_VERSION", ???)
      object test extends PlayTests
      override def ivyDeps = T { super.ivyDeps() ++ Agg(ws()) }
    }
  }

  val resourcePath: os.Path = os.pwd / "contrib" / "playlib" / "test" / "resources" / "playmulti"

  def tests: Tests = Tests {
    test("layout") {
      test("fromBuild") {
        matrix.foreach { case (scalaVersion, playVersion) =>
          workspaceTest(playmulti) { eval =>
            val Right((conf, _)) = eval.apply(playmulti.core(scalaVersion, playVersion).conf)
            val Right((app, _)) = eval.apply(playmulti.core(scalaVersion, playVersion).app)
            val Right((sources, _)) = eval.apply(playmulti.core(scalaVersion, playVersion).sources)
            val Right((resources, _)) =
              eval.apply(playmulti.core(scalaVersion, playVersion).resources)
            val Right((testSources, _)) =
              eval.apply(playmulti.core(scalaVersion, playVersion).test.sources)
            val Right((testResources, _)) =
              eval.apply(playmulti.core(scalaVersion, playVersion).test.resources)
            assert(
              conf.map(_.path.relativeTo(playmulti.millSourcePath).toString()) == Seq("core/conf"),
              app.map(_.path.relativeTo(playmulti.millSourcePath).toString()) == Seq("core/app"),
              sources == app,
              resources.map(_.path.relativeTo(playmulti.millSourcePath).toString()).contains(
                "core/conf"
              ),
              testSources.map(_.path.relativeTo(playmulti.millSourcePath).toString()) == Seq(
                "core/test"
              ),
              testResources.map(_.path.relativeTo(playmulti.millSourcePath).toString()) == Seq(
                "core/test/resources"
              )
            )
          }
        }
      }
    }
    test("dependencies") {
      test("fromBuild") {
        matrix.foreach { case (scalaVersion, playVersion) =>
          workspaceTest(playmulti) { eval =>
            val Right((deps, evalCount)) =
              eval.apply(playmulti.core(scalaVersion, playVersion).ivyDeps)
            val expectedModules = Seq[String](
              "play",
              "play-guice",
              "play-server",
              "play-logback",
              "play-ahc-ws"
            )
            val outputModules = deps.map(_.dep.module.name.value)
            assert(
              outputModules.forall(expectedModules.contains),
              evalCount > 0
            )
          }
        }
      }
    }
    test("compile") {
      matrix.foreach { case (scalaVersion, playVersion) =>
        workspaceTest(playmulti) { eval =>
          val eitherResult = eval.apply(playmulti.core(scalaVersion, playVersion).compile)
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
            eval.outPath / "core" / scalaVersion / playVersion / "compile.dest" / "classes" / _
          )
          assert(
            result.classes.path == eval.outPath / "core" / scalaVersion / playVersion / "compile.dest" / "classes",
            outputFiles.nonEmpty,
            outputFiles.forall(expectedClassfiles.contains),
            outputFiles.size == 15,
            evalCount > 0
          )

          // don't recompile if nothing changed
          val Right((_, unchangedEvalCount)) =
            eval.apply(playmulti.core(scalaVersion, playVersion).compile)
          assert(unchangedEvalCount == 0)
        }
      }
    }
  }
}
