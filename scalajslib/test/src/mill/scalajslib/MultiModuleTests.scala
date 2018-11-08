package mill.scalajslib

import mill._
import mill.define.Discover
import mill.eval.Evaluator
import mill.util._
import mill.scalalib._
import utest._

object MultiModuleTests extends TestSuite {
  val workspacePath =  TestUtil.getOutPathStatic() / "multi-module"
  val sourcePath = os.pwd / 'scalajslib / 'test / 'resources / "multi-module"

  object MultiModule extends TestUtil.BaseModule {
    trait BaseModule extends ScalaJSModule {
      def scalaVersion = "2.12.4"
      def scalaJSVersion = "0.6.22"
    }

    object client extends BaseModule {
      override def millSourcePath = workspacePath / 'client
      override def moduleDeps = Seq(shared)
      override def mainClass = Some("Main")
      object test extends Tests {
        def testFrameworks = Seq("utest.runner.Framework")
        override def ivyDeps = Agg(ivy"com.lihaoyi::utest::0.6.3")
      }
    }

    object shared extends BaseModule {
      override def millSourcePath = workspacePath / 'shared
    }

    override lazy val millDiscover = Discover[this.type]
  }

  val evaluator = TestEvaluator.static(MultiModule)

  def tests: Tests = Tests {
    prepareWorkspace()

    def checkOpt(mode: OptimizeMode) = {
      val task = mode match {
        case FullOpt => MultiModule.client.fullOpt
        case FastOpt => MultiModule.client.fastOpt
      }
      val Right((linked, evalCount)) = evaluator(task)

      val runOutput = ScalaJsUtils.runJS(linked.path)
      assert(
        evalCount > 0,
        runOutput == "Hello from Scala.js, result is: 3"
      )
    }

    'fastOpt - TestUtil.disableInJava9OrAbove(checkOpt(FastOpt))
    'fullOpt - TestUtil.disableInJava9OrAbove(checkOpt(FullOpt))

    'test - {
      val Right(((_, testResults), evalCount)) = evaluator(MultiModule.client.test.test())

      assert(
        evalCount > 0,
        testResults.size == 3,
        testResults.forall(_.status == "Success")
      )
    }

    'run - {
      val command = MultiModule.client.run()
      
      val Right((_, evalCount)) = evaluator(command)

      val paths = Evaluator.resolveDestPaths(
        evaluator.outPath,
        command.ctx.segments
      )
      val log = os.read(paths.log)
      assert(
        evalCount > 0,
        log.contains("node"),
        log.contains("Hello from Scala.js, result is: 3")
      )
    }
  }

  def prepareWorkspace(): Unit = {
    os.remove.all(workspacePath)
    os.makeDir.all(workspacePath / os.up)
    os.copy(sourcePath, workspacePath)
  }

}
