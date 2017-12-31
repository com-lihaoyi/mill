package mill.scalalib

import ammonite.ops.ImplicitWd._
import ammonite.ops._
import mill.{Module, scalalib}
import mill.define.{Cross, Task}
import mill.discover.Discovered
import mill.eval.Result
import utest._
import mill.util.JsonFormatters._
import mill.util.TestEvaluator

object JawnBuild{
  val Jawn = Cross("2.10.6", "2.11.11", "2.12.3").map(new Jawn(_))
  class Jawn(crossVersion: String) extends mill.Module{
    trait JawnModule extends scalalib.SbtModule{ outer =>
      def scalaVersion = crossVersion
      def scalacOptions = Seq(
        "-deprecation",
        "-optimize",
        "-unchecked"
      )
      def testProjectDeps: Seq[TestModule] = Nil
      object test extends this.Tests{
        def projectDeps = super.projectDeps ++ testProjectDeps
        def ivyDeps = Seq(
          Dep("org.scalatest", "scalatest", "3.0.3"),
          Dep("org.scalacheck", "scalacheck", "1.13.5")
        )
        def testFramework = "org.scalatest.tools.Framework"
      }
    }
    object Parser extends JawnModule{
      def basePath = JawnTests.srcPath/"parser"
    }
    object Util extends JawnModule{
      def projectDeps = Seq(Parser)
      def testProjectDeps = Seq(Parser.test)
      def basePath = JawnTests.srcPath/"util"
    }
    object Ast extends JawnModule{
      def projectDeps = Seq(Parser, Util)
      def testProjectDeps = Seq(Parser.test, Util.test)
      def basePath = JawnTests.srcPath/"ast"
    }
    class Support(name: String, ivyDeps0: Dep*) extends JawnModule{
      def projectDeps = Seq[Module](Parser)
      def basePath = JawnTests.srcPath/"support"/"argonaut"
      def ivyDeps = ivyDeps0
    }
    object Argonaut extends Support("argonaut", Dep("io.argonaut", "argonaut", "6.2"))
    object Json4s extends Support("json4s", Dep("org.json4s", "json4s-ast", "3.5.2"))

    object Play extends Support("play"){
      def ivyDeps = mill.T{
        scalaBinaryVersion() match{
          case "2.10" => Seq(Dep("com.typesafe.play", "play-json", "2.4.11"))
          case "2.11" => Seq(Dep("com.typesafe.play", "play-json", "2.5.15"))
          case _ => Seq(Dep("com.typesafe.play", "play-json", "2.6.0"))
        }
      }
    }

    object Rojoma extends Support("rojoma", Dep("com.rojoma", "rojoma-json", "2.4.3"))
    object RojomaV3 extends Support("rojoma-v3", Dep("com.rojoma", "rojoma-json-v3", "3.7.2"))
    object Spray extends Support("spray", Dep("io.spray", "spray-json", "1.3.3"))
  }
  
}
object JawnTests extends TestSuite{
  val workspacePath = pwd / 'target / 'workspace / "jawn"
  val srcPath = pwd / 'scalalib / 'src / 'test / 'resource / "jawn"
  val tests = Tests{
    rm(workspacePath)
    mkdir(workspacePath/up)
    cp(srcPath, workspacePath)
    val mapping = Discovered.mapping(JawnBuild)
    val eval = new TestEvaluator(mapping, workspacePath)

    'test - {
      def compileOutput = workspacePath / 'jawn / "2.12.3" / 'Parser / 'compile
      def testCompileOutput = workspacePath / 'jawn / "2.12.3" / 'Parser / 'test / 'compile
      assert(!exists(compileOutput), !exists(testCompileOutput))
      val Right(_) = eval(JawnBuild.Jawn("2.12.3").Parser.test.test())
      assert(
        ls.rec(compileOutput).exists(_.last == "AsyncParser.class"),
        ls.rec(testCompileOutput).exists(_.last == "CharBuilderSpec.class")
      )
    }

  }
}
