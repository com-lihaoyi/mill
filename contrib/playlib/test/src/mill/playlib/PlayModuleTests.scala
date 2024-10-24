package mill
package playlib

import mill.scalalib.api.ZincWorkerUtil
import mill.testkit.{TestBaseModule, UnitTester}
import utest.{TestSuite, Tests, assert, _}

object PlayModuleTests extends TestSuite with PlayTestSuite {

  object playmulti extends TestBaseModule {
    object core extends Cross[CoreCrossModule](matrix)
    trait CoreCrossModule extends PlayModule with Cross.Module2[String, String] {
      val (crossScalaVersion, crossPlayVersion) = (crossValue, crossValue2)
      override def playVersion = crossPlayVersion
      override def scalaVersion = crossScalaVersion
      object test extends PlayTests
      override def ivyDeps = Task { super.ivyDeps() ++ Agg(ws()) }
    }
  }
  val resourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "playmulti"

  def tests: Tests = Tests {
    test("layout") {
      test("fromBuild") {
        matrix.foreach { case (scalaVersion, playVersion) =>
          UnitTester(playmulti, resourcePath).scoped { eval =>
            val Right(conf) = eval.apply(playmulti.core(scalaVersion, playVersion).conf)
            val Right(app) = eval.apply(playmulti.core(scalaVersion, playVersion).app)
            val Right(sources) = eval.apply(playmulti.core(scalaVersion, playVersion).sources)
            val Right(resources) =
              eval.apply(playmulti.core(scalaVersion, playVersion).resources)
            val Right(testSources) =
              eval.apply(playmulti.core(scalaVersion, playVersion).test.sources)
            val Right(testResources) =
              eval.apply(playmulti.core(scalaVersion, playVersion).test.resources)
            assert(
              conf.value.map(_.path.relativeTo(playmulti.millSourcePath).toString()) == Seq(
                "core/conf"
              ),
              app.value.map(_.path.relativeTo(playmulti.millSourcePath).toString()) == Seq(
                "core/app"
              ),
              sources == app,
              resources.value.map(_.path.relativeTo(playmulti.millSourcePath).toString()).contains(
                "core/conf"
              ),
              testSources.value.map(_.path.relativeTo(playmulti.millSourcePath).toString()) == Seq(
                "core/test"
              ),
              testResources.value.map(
                _.path.relativeTo(playmulti.millSourcePath).toString()
              ) == Seq(
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
          UnitTester(playmulti, resourcePath).scoped { eval =>
            val Right(result) =
              eval.apply(playmulti.core(scalaVersion, playVersion).ivyDeps)
            val expectedModules = Seq[String](
              "play",
              "play-guice",
              "play-server",
              "play-logback",
              "play-ahc-ws"
            )
            val outputModules = result.value.map(_.dep.module.name.value)
            assert(
              outputModules.forall(expectedModules.contains),
              result.evalCount > 0
            )
          }
        }
      }
      test("resolvedRunIvyDeps") {
        matrix.foreach { case (scalaVersion, playVersion) =>
          UnitTester(playmulti, resourcePath).scoped { eval =>
            val Right(_) = eval.apply(playmulti.core(scalaVersion, playVersion).resolvedRunIvyDeps)
          }
        }
      }
    }
    test("compile") {
      matrix.foreach { case (scalaVersion, playVersion) =>
        skipUnsupportedVersions(playVersion) {
          UnitTester(playmulti, resourcePath).scoped { eval =>
            val eitherResult = eval.apply(playmulti.core(scalaVersion, playVersion).compile)
            val Right(result) = eitherResult
            val outputClassFiles =
              os.walk(result.value.classes.path).filter(f => os.isFile(f) && f.ext == "class")

            val expectedClassfiles = Seq[os.RelPath](
              os.RelPath("controllers/HomeController.class"),
              os.RelPath("controllers/ReverseAssets.class"),
              os.RelPath("controllers/ReverseHomeController.class"),
              os.RelPath("controllers/routes.class"),
              os.RelPath("controllers/routes$javascript.class"),
              os.RelPath("controllers/javascript/ReverseHomeController.class"),
              os.RelPath("controllers/javascript/ReverseAssets.class"),
              if (ZincWorkerUtil.isScala3(scalaVersion)) os.RelPath("router/Routes$$anon$1.class")
              else os.RelPath("router/Routes$$anonfun$routes$1.class"),
              os.RelPath("router/Routes.class"),
              os.RelPath("router/RoutesPrefix$.class"),
              os.RelPath("router/RoutesPrefix.class"),
              os.RelPath("views/html/index$.class"),
              os.RelPath("views/html/index.class"),
              os.RelPath("views/html/main$.class"),
              os.RelPath("views/html/main.class")
            ).map(
              eval.outPath / "core" / scalaVersion / playVersion / "compile.dest/classes" / _
            )
            assert(
              result.value.classes.path == eval.outPath / "core" / scalaVersion / playVersion / "compile.dest/classes",
              outputClassFiles.nonEmpty,
              outputClassFiles.forall(expectedClassfiles.contains),
              outputClassFiles.size == 15,
              result.evalCount > 0
            )

            // don't recompile if nothing changed
            val Right(result2) =
              eval.apply(playmulti.core(scalaVersion, playVersion).compile)
            assert(result2.evalCount == 0)
          }
        }
      }
    }
  }
}
