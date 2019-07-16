package mill.playlib

import ammonite.ops.{Path, cp, ls, mkdir, pwd, rm, _}
import mill.T
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object PlaySingleModuleTests extends TestSuite {

  object playsingle extends TestUtil.BaseModule with PlayModule with SingleModule{
    override def playVersion = T{"2.7.0"}
    def twirlVersion = T{"1.4.0"}
    override def scalaVersion = T{"2.12.8"}
    object test extends PlayTests
  }

  val resourcePath: Path = pwd / 'contrib / 'playlib / 'test / 'resources / "playsingle"

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
      'fromBuild - workspaceTest(playsingle) { eval =>
        val Right((result, evalCount)) = eval.apply(playsingle.playVersion)
        assert(
          result == "2.7.0",
          evalCount > 0
        )
      }
    }
    'layout - {
      'fromBuild - workspaceTest(playsingle) { eval =>
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
          testResources.map(_.path.relativeTo(playsingle.millSourcePath).toString()) == Seq("test/resources")
        )
      }
    }
    'compile - workspaceTest(playsingle) { eval =>
      val eitherResult = eval.apply(playsingle.compile)
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
        RelPath("router/RoutesPrefix.class"),
        RelPath("views/html/index$.class"),
        RelPath("views/html/index.class"),
        RelPath("views/html/main$.class"),
        RelPath("views/html/main.class")
      ).map(
        eval.outPath / 'compile / 'dest / 'classes / _
      )
      assert(
        result.classes.path == eval.outPath / 'compile / 'dest / 'classes,
        outputFiles.nonEmpty,
        outputFiles.forall(expectedClassfiles.contains),
        outputFiles.size == 15,
        evalCount > 0
      )

      // don't recompile if nothing changed
      val Right((_, unchangedEvalCount)) = eval.apply(playsingle.compile)

      // FIXME the following test should be uncommented once
      // https://github.com/lihaoyi/mill/issues/554 is resolved
      // assert(unchangedEvalCount == 0)
    }
  }
}
