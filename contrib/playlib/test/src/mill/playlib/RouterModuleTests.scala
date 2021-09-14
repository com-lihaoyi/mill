package mill.playlib

import ammonite.ops.{Path, cp, ls, mkdir, pwd, rm, _}
import mill.T
import mill.api.Result.Failure
import mill.define.Cross
import mill.scalalib.CrossScalaModule
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object RouterModuleTests extends TestSuite with PlayTestSuite {

  trait HelloBase extends TestUtil.BaseModule {
    override def millSourcePath: Path =
      TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  trait HelloWorldModule extends mill.playlib.RouterModule with CrossScalaModule

  object HelloWorld extends HelloBase {

    object core extends Cross[CoreCrossModule](matrix: _*)
    class CoreCrossModule(val crossScalaVersion: String, crossPlayVersion: String)
        extends HelloWorldModule {
      def millSourcePath = super.millSourcePath / os.up
      def playVersion = crossPlayVersion
    }

  }

  val resourcePath: Path = pwd / "contrib" / "playlib" / "test" / "resources" / "hello-world"
  val invalidResourcePath: Path = pwd / "contrib" / "playlib" / "test" / "resources" / "invalid"
  val invalidSubResourcePath: Path =
    pwd / "contrib" / "playlib" / "test" / "resources" / "invalidsub"

  def tests: Tests = Tests {
    test("compileRouter") {
      matrix.foreach { case (scalaVersion, playVersion) =>
        workspaceTest(HelloWorld) { eval =>
          val eitherResult = eval.apply(HelloWorld.core(scalaVersion, playVersion).compileRouter)
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
            eval.outPath / "core" / scalaVersion / playVersion / "compileRouter" / "dest" / _
          )
          assert(
            result.classes.path == eval.outPath / "core" / scalaVersion / playVersion / "compileRouter" / "dest",
            outputFiles.nonEmpty,
            outputFiles.forall(expectedClassfiles.contains),
            outputFiles.size == 7,
            evalCount > 0
          )

          // don't recompile if nothing changed
          val Right((_, unchangedEvalCount)) =
            eval.apply(HelloWorld.core(scalaVersion, playVersion).compileRouter)

          assert(unchangedEvalCount == 0)
        }
      }
    }
    test("compileRouterInvalidRoutes") {
      matrix.foreach { case (scalaVersion, playVersion) =>
        workspaceTest(HelloWorld, resourcePath = invalidResourcePath) { eval =>
          val project = HelloWorld.core(scalaVersion, playVersion)
          val eitherResult = eval.apply(project.compileRouter)
          val Left(Failure(message, x)) = eitherResult
          val playExpectedMessage =
            if (playVersion.startsWith("2.6.")) {
              "HTTP Verb (GET, POST, ...), include (->), comment (#), or modifier line (+) expected"
            } else {
              "end of input expected"
            }
          val expectedMessage = "Unable to compile play routes, compilation error in " +
            project.millSourcePath.toIO.getAbsolutePath.replace(
              """\""",
              "/"
            ) + "/routes/routes at line 4, " +
            "column" + " 1: " + playExpectedMessage
          // fix windows paths
          val normalizeMessage = message.replace("""\""", "/")
          assert(
            normalizeMessage == expectedMessage
          )
        }
      }
    }
    test("compileRouterInvalidSubRoutes") {
      matrix.foreach { case (scalaVersion, playVersion) =>
        workspaceTest(HelloWorld, resourcePath = invalidSubResourcePath) { eval =>
          val eitherResult = eval.apply(HelloWorld.core(scalaVersion, playVersion).compileRouter)
          val Left(Failure(message, x)) = eitherResult
          val playExpectedMessage =
            if (playVersion.startsWith("2.6.")) {
              "HTTP Verb (GET, POST, ...), include (->), comment (#), or modifier line (+) expected"
            } else {
              "end of input expected"
            }
          val expectedMessage = "Unable to compile play routes, compilation error in " +
            HelloWorld.core.millSourcePath.toIO.getAbsolutePath.replace(
              """\""",
              "/"
            ) + "/routes/sub.routes at line 3, column" +
            " 1: " + playExpectedMessage
          // fix windows paths
          val normalizeMessage = message.replace("""\""", "/")
          assert(
            normalizeMessage == expectedMessage
          )
        }
      }
    }
  }
}
