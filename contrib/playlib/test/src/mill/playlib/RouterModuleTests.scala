package mill.playlib

import mill.api.ExecResult
import mill.define.{Cross, Discover}
import mill.scalalib.ScalaModule
import mill.testkit.{TestBaseModule, UnitTester}
import utest.{TestSuite, Tests, assert, _}
import mill.util.TokenReaders._
object RouterModuleTests extends TestSuite with PlayTestSuite {

  trait HelloBase extends TestBaseModule

  trait HelloWorldModule extends mill.playlib.RouterModule with ScalaModule

  object HelloWorld extends HelloBase {

    object core extends Cross[CoreCrossModule](matrix)
    trait CoreCrossModule extends HelloWorldModule with Cross.Module2[String, String] {
      def scalaVersion = crossValue
      def playVersion = crossValue2
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourceFolder = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))
  val resourcePath: os.Path = resourceFolder / "hello-world"
  val invalidResourcePath: os.Path = resourceFolder / "invalid"
  val invalidSubResourcePath: os.Path = resourceFolder / "invalidsub"

  def tests: Tests = Tests {
    test("compileRouter") {
      matrix.foreach { case (scalaVersion, playVersion) =>
        skipUnsupportedVersions(playVersion) {
          UnitTester(HelloWorld, resourcePath).scoped { eval =>
            val eitherResult = eval.apply(HelloWorld.core(scalaVersion, playVersion).compileRouter)
            val Right(result) = eitherResult: @unchecked
            val outputFiles = os.walk(result.value.classes.path).filter(os.isFile)
            val expectedClassfiles = Seq[os.RelPath](
              os.RelPath("controllers/ReverseRoutes.scala"),
              os.RelPath("controllers/routes.java"),
              os.RelPath("router/Routes.scala"),
              os.RelPath("router/RoutesPrefix.scala"),
              os.RelPath("sub/Routes.scala"),
              os.RelPath("sub/RoutesPrefix.scala"),
              os.RelPath("controllers/javascript/JavaScriptReverseRoutes.scala")
            ).map(
              eval.outPath / "core" / scalaVersion / playVersion / "compileRouter.dest" / _
            )
            assert(
              result.value.classes.path == eval.outPath / "core" / scalaVersion / playVersion / "compileRouter.dest",
              outputFiles.nonEmpty,
              outputFiles.forall(expectedClassfiles.contains),
              outputFiles.size == 7,
              result.evalCount > 0
            )

            // don't recompile if nothing changed
            val Right(result2) =
              eval.apply(HelloWorld.core(scalaVersion, playVersion).compileRouter): @unchecked

            assert(result2.evalCount == 0)
          }
        }
      }
    }
    test("compileRouterInvalidRoutes") {
      matrix.foreach { case (scalaVersion, playVersion) =>
        skipUnsupportedVersions(playVersion) {
          UnitTester(HelloWorld, invalidResourcePath).scoped { eval =>
            val project = HelloWorld.core(scalaVersion, playVersion)
            val eitherResult = eval.apply(project.compileRouter)
            val Left(ExecResult.Failure(message)) = eitherResult: @unchecked
            val playExpectedMessage =
              if !playVersion.startsWith("2.7.") && !playVersion.startsWith("2.8.") then {
                "HTTP Verb (GET, POST, ...), include (->), comment (#), or modifier line (+) expected"
              } else {
                "end of input expected"
              }
            val expectedMessage = "Unable to compile play routes, compilation error in " +
              project.moduleDir.toIO.getAbsolutePath.replace(
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
    }
    test("compileRouterInvalidSubRoutes") {
      matrix.foreach { case (scalaVersion, playVersion) =>
        skipUnsupportedVersions(playVersion) {
          UnitTester(HelloWorld, invalidSubResourcePath).scoped { eval =>
            val eitherResult = eval.apply(HelloWorld.core(scalaVersion, playVersion).compileRouter)
            val Left(ExecResult.Failure(message)) = eitherResult: @unchecked
            val playExpectedMessage =
              if !playVersion.startsWith("2.7.") && !playVersion.startsWith("2.8.") then {
                "HTTP Verb (GET, POST, ...), include (->), comment (#), or modifier line (+) expected"
              } else {
                "end of input expected"
              }
            val expectedMessage = "Unable to compile play routes, compilation error in " +
              HelloWorld.core.moduleDir.toIO.getAbsolutePath.replace(
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
}
