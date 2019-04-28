package mill
package playlib

import ammonite.ops.{Path, cp, ls, mkdir, pwd, rm, _}
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object PlayModuleTests extends TestSuite {

  object playmulti extends TestUtil.BaseModule{
    object core extends PlayModule {
      override def playVersion = T{"2.7.0"}
      override def twirlVersion = T{"1.4.0"}
      override def scalaVersion = T{"2.12.8"}
      object test extends PlayTests
      override def ivyDeps = T { super.ivyDeps() ++ Agg(ws())}
    }
  }

  val resourcePath: Path = pwd / 'contrib / 'playlib / 'test / 'resources / 'playmulti

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
      'fromBuild - workspaceTest(playmulti) { eval =>
        val Right((result, evalCount)) = eval.apply(playmulti.core.playVersion)
        assert(
          result == "2.7.0",
          evalCount > 0
        )
      }
    }
    'layout - {
      'fromBuild - workspaceTest(playmulti) { eval =>
        val Right((conf, _)) = eval.apply(playmulti.core.conf)
        val Right((app, _)) = eval.apply(playmulti.core.app)
        val Right((sources, _)) = eval.apply(playmulti.core.sources)
        val Right((resources, _)) = eval.apply(playmulti.core.resources)
        val Right((testSources, _)) = eval.apply(playmulti.core.test.sources)
        val Right((testResources, _)) = eval.apply(playmulti.core.test.resources)
        assert(
          conf.map(_.path.relativeTo(playmulti.millSourcePath).toString()) == Seq("core/conf"),
          app.map(_.path.relativeTo(playmulti.millSourcePath).toString()) == Seq("core/app"),
          sources== app,
          resources.map(_.path.relativeTo(playmulti.millSourcePath).toString()).contains("core/conf"),
          testSources.map(_.path.relativeTo(playmulti.millSourcePath).toString()) == Seq("core/test"),
          testResources.map(_.path.relativeTo(playmulti.millSourcePath).toString()) == Seq("core/test/resources")
        )
      }
    }
    'dependencies - {
      'fromBuild - workspaceTest(playmulti) { eval =>
        val Right((deps, evalCount)) = eval.apply(playmulti.core.ivyDeps)
        val expectedModules = Seq[String](
          "play",
          "play-guice",
          "play-server",
          "play-logback",
          "play-ahc-ws"
        )
        val outputModules = deps.map(_.dep.module.name)
        assert(
          outputModules.forall(expectedModules.contains),
          evalCount > 0
        )
      }
    }
    'compile - workspaceTest(playmulti) { eval =>
      val eitherResult = eval.apply(playmulti.core.compile)
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
        eval.outPath / 'core / 'compile / 'dest / 'classes / _
      )
      assert(
        result.classes.path == eval.outPath / 'core / 'compile / 'dest / 'classes,
        outputFiles.nonEmpty,
        outputFiles.forall(expectedClassfiles.contains),
        outputFiles.size == 15,
        evalCount > 0
      )


      // don't recompile if nothing changed
      val Right((_, unchangedEvalCount)) = eval.apply(playmulti.core.compile)

      // FIXME the following test should be uncommented once
      // https://github.com/lihaoyi/mill/issues/554 is resolved
      // assert(unchangedEvalCount == 0)
    }
  }
}
