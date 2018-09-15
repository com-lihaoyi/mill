package mill.contrib.scalapblib

import ammonite.ops.{Path, cp, ls, mkdir, pwd, rm, _}
import mill.eval.Result
import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object TutorialTests extends TestSuite {

  trait TutorialBase extends TestUtil.BaseModule {
    override def millSourcePath: Path = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  trait TutorialModule extends ScalaPBModule {
    def scalaVersion = "2.12.4"
    def scalaPBVersion = "0.7.4"
    def scalaPBFlatPackage = true
  }

  object Tutorial extends TutorialBase {

    object core extends TutorialModule {
      override def scalaPBVersion = "0.7.4"
    }
  }

  val resourcePath: Path = pwd / 'contrib / 'scalapblib / 'test / 'protobuf / 'tutorial

  def protobufOutPath(eval: TestEvaluator): Path =
    eval.outPath / 'core / 'compileScalaPB / 'dest / 'com / 'example / 'tutorial

  def workspaceTest[T](m: TestUtil.BaseModule)(t: TestEvaluator => T)
                      (implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    rm(m.millSourcePath)
    println(m.millSourcePath)
    rm(eval.outPath)
    println(eval.outPath)
    mkdir(m.millSourcePath / 'core / 'protobuf)
    cp(resourcePath, m.millSourcePath / 'core / 'protobuf / 'tutorial)
    t(eval)
  }

  def compiledSourcefiles: Seq[RelPath] = Seq[RelPath](
    "AddressBook.scala",
    "Person.scala",
    "TutorialProto.scala"
  )

  def tests: Tests = Tests {
    'scalapbVersion - {

      'fromBuild - workspaceTest(Tutorial) { eval =>
        val Right((result, evalCount)) = eval.apply(Tutorial.core.scalaPBVersion)

        assert(
          result == "0.7.4",
          evalCount > 0
        )
      }
    }

    'compileScalaPB - {
      'calledDirectly - workspaceTest(Tutorial) { eval =>
        val Right((result, evalCount)) = eval.apply(Tutorial.core.compileScalaPB)

        val outPath = protobufOutPath(eval)

        val outputFiles = ls.rec(result.path).filter(_.isFile)

        val expectedSourcefiles = compiledSourcefiles.map(outPath / _)

        assert(
          result.path == eval.outPath / 'core / 'compileScalaPB / 'dest,
          outputFiles.nonEmpty,
          outputFiles.forall(expectedSourcefiles.contains),
          outputFiles.size == 3,
          evalCount > 0
        )

        // don't recompile if nothing changed
        val Right((_, unchangedEvalCount)) = eval.apply(Tutorial.core.compileScalaPB)

        assert(unchangedEvalCount == 0)
      }

      // This throws a NullPointerException in coursier somewhere
      //
      // 'triggeredByScalaCompile - workspaceTest(Tutorial) { eval =>
      //   val Right((_, evalCount)) = eval.apply(Tutorial.core.compile)

      //   val outPath = protobufOutPath(eval)

      //   val outputFiles = ls.rec(outPath).filter(_.isFile)

      //   val expectedSourcefiles = compiledSourcefiles.map(outPath / _)

      //   assert(
      //     outputFiles.nonEmpty,
      //     outputFiles.forall(expectedSourcefiles.contains),
      //     outputFiles.size == 3,
      //     evalCount > 0
      //   )

      //   // don't recompile if nothing changed
      //   val Right((_, unchangedEvalCount)) = eval.apply(Tutorial.core.compile)

      //   assert(unchangedEvalCount == 0)
      // }
    }
  }
}
