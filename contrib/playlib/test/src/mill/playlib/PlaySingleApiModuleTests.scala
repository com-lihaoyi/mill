package mill.playlib

import ammonite.ops.{Path, cp, ls, mkdir, pwd, rm, _}
import mill.T
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object PlaySingleApiModuleTests extends TestSuite {

  object playsingleapi extends TestUtil.BaseModule with PlayApiModule with SingleModule{
    override def playVersion = T{"2.7.0"}
    def twirlVersion = T{"1.4.0"}
    override def scalaVersion = T{"2.12.8"}
    object test extends PlayTests
  }

  val resourcePath: Path = pwd / 'contrib / 'playlib / 'test / 'resources / "playsingleapi"

  def workspaceTest[T, M <: TestUtil.BaseModule](m: M, resourcePath: Path = resourcePath)
                                                (t: TestEvaluator => T)
                                                (implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    rm(m.millSourcePath)
    rm(eval.outPath)
    mkdir(m.millSourcePath / up)
    cp(resourcePath, m.millSourcePath)
    t(eval)
  }

  def tests: Tests = Tests {
    'playVersion - {
      'fromBuild - workspaceTest(playsingleapi) { eval =>
        val Right((result, evalCount)) = eval.apply(playsingleapi.playVersion)
        assert(
          result == "2.7.0",
          evalCount > 0
        )
      }
    }
    'layout - {
      'fromBuild - workspaceTest(playsingleapi) { eval =>
        val Right((conf, _)) = eval.apply(playsingleapi.conf)
        val Right((app, _)) = eval.apply(playsingleapi.app)
        val Right((sources, _)) = eval.apply(playsingleapi.sources)
        val Right((resources, _)) = eval.apply(playsingleapi.resources)
        val Right((testSources, _)) = eval.apply(playsingleapi.test.sources)
        val Right((testResources, _)) = eval.apply(playsingleapi.test.resources)
        assert(
          conf.map(_.path.relativeTo(playsingleapi.millSourcePath).toString()) == Seq("conf"),
          app.map(_.path.relativeTo(playsingleapi.millSourcePath).toString()) == Seq("app"),
          sources== app,
          resources== conf,
          testSources.map(_.path.relativeTo(playsingleapi.millSourcePath).toString()) == Seq("test"),
          testResources.map(_.path.relativeTo(playsingleapi.millSourcePath).toString()) == Seq("test/resources")
        )
      }
    }
    'compile - workspaceTest(playsingleapi) { eval =>
      val eitherResult = eval.apply(playsingleapi.compile)
      val Right((result, evalCount)) = eitherResult
      val outputFiles = ls.rec(result.classes.path).filter(_.isFile)
      val expectedClassfiles = Seq[RelPath](
        RelPath("controllers/HomeController.class"),
        RelPath("controllers/ReverseAssets.class"),
        RelPath("controllers/ReverseHomeController.class"),
        RelPath("controllers/routes.class"),
        RelPath("controllers/routes$javascript.class"),
        RelPath("controllers/javascript/ReverseHomeController.class"),
        RelPath("controllers/javascript/ReverseAssets.class"),
        RelPath("router/Routes$$anonfun$routes$1.class"),
        RelPath("router/Routes.class"),
        RelPath("router/RoutesPrefix$.class"),
        RelPath("router/RoutesPrefix.class")
      ).map(
        eval.outPath / 'compile / 'dest / 'classes / _
      )
      assert(
        result.classes.path == eval.outPath / 'compile / 'dest / 'classes,
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
