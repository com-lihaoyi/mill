package mill.playlib

import ammonite.ops.{Path, cp, ls, mkdir, pwd, rm, _}
import mill.T
import mill.api.Result.Failure
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object HelloWorldTests extends TestSuite {

  trait HelloBase extends TestUtil.BaseModule {
    override def millSourcePath: Path = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  trait HelloWorldModule extends mill.playlib.RouterModule {
    def playVersion = "2.6.15"
  }

  object HelloWorld extends HelloBase {

    object core extends HelloWorldModule {
      override def playVersion = "2.6.14"

      /**
        * What version of Scala to use
        */
      override def scalaVersion: T[String] = "2.12.8"
    }

  }

  val resourcePath: Path = pwd / 'contrib / 'playlib / 'test / 'resources / "hello-world"
  val invalidResourcePath: Path = pwd / 'contrib / 'playlib / 'test / 'resources / "invalid"
  val invalidSubResourcePath: Path = pwd / 'contrib / 'playlib / 'test / 'resources / "invalidsub"

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

      'fromBuild - workspaceTest(HelloWorld) { eval =>
        val Right((result, evalCount)) = eval.apply(HelloWorld.core.playVersion)

        assert(
          result == "2.6.14",
          evalCount > 0
        )
      }
    }
    'compileRouter - workspaceTest(HelloWorld) { eval =>
      val eitherResult = eval.apply(HelloWorld.core.compileRouter)
      val Right((result, evalCount)) = eitherResult
      val outputFiles = ls.rec(result.classes.path).filter(_.isFile)
      val expectedClassfiles = Seq[RelPath](
        RelPath("controllers/ReverseRoutes.scala"),
        RelPath("controllers/routes.java"),
        RelPath("router/Routes.scala"),
        RelPath("router/RoutesPrefix.scala"),
        RelPath("sub/Routes.scala"),
        RelPath("sub/RoutesPrefix.scala"),
        RelPath("controllers/javascript/JavaScriptReverseRoutes.scala")
      ).map(
        eval.outPath / 'core / 'compileRouter / 'dest / _
      )
      assert(
        result.classes.path == eval.outPath / 'core / 'compileRouter / 'dest,
        outputFiles.nonEmpty,
        outputFiles.forall(expectedClassfiles.contains),
        outputFiles.size == 7,
        evalCount > 0
      )

      // don't recompile if nothing changed
      val Right((_, unchangedEvalCount)) = eval.apply(HelloWorld.core.compileRouter)

      assert(unchangedEvalCount == 0)
    }
    "compileRouter invalid routes"- workspaceTest(HelloWorld, resourcePath = invalidResourcePath) {
      eval =>
      val eitherResult = eval.apply(HelloWorld.core.compileRouter)
      val Left(Failure(message, x)) = eitherResult
      val expectedMessage = "Unable to compile play routes\ncompilation error in " +
        HelloWorld.core.millSourcePath.toIO.getAbsolutePath + "/routes/routes at line 4, " +
        "column" +
        " 1: HTTP Verb (GET, POST, ...), include (->), comment (#), or modifier line (+) expected"
      assert(
        message == expectedMessage
      )
    }
    "compileRouter invalid sub.routes " - workspaceTest(HelloWorld, resourcePath = invalidSubResourcePath) {
      eval =>
      val eitherResult = eval.apply(HelloWorld.core.compileRouter)
      val Left(Failure(message, x)) = eitherResult
      val expectedMessage = "Unable to compile play routes\ncompilation error in " +
        HelloWorld.core.millSourcePath.toIO.getAbsolutePath + "/routes/sub.routes at line 3, column" +
        " 1: HTTP Verb (GET, POST, ...), include (->), comment (#), or modifier line (+) expected"
      assert(
        message == expectedMessage
      )
    }
  }
}
