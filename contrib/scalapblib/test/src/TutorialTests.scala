package mill.contrib.scalapblib

import mill.util.{TestEvaluator, TestUtil}
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object TutorialTests extends TestSuite {

  trait TutorialBase extends TestUtil.BaseModule {
    override def millSourcePath: os.Path = TestUtil.getSrcPathBase() / millOuterCtx.enclosing.split('.')
  }

  trait TutorialModule extends ScalaPBModule {
    def scalaVersion = "2.12.4"
    def scalaPBVersion = "0.7.4"
    def scalaPBFlatPackage = true
    def scalaPBIncludePath = Seq(scalaPBUnpackProto())
  }

  object Tutorial extends TutorialBase {

    object core extends TutorialModule {
      override def scalaPBVersion = "0.7.4"
    }
  }

  object TutorialWithProtoc extends TutorialBase {
    object core extends TutorialModule {
      override def scalaPBProtocPath = Some("/dev/null")
    }
  }

  object TutorialWithCustomArgs extends TutorialBase {
    object core extends TutorialModule {
      override def scalaPBCustomArgs = Seq(
        s"--zio_out=..."
      )
    }
  }

  val resourcePath: os.Path = os.pwd / 'contrib / 'scalapblib / 'test / 'protobuf / 'tutorial

  def protobufOutPath(eval: TestEvaluator): os.Path =
    eval.outPath / 'core / 'compileScalaPB / 'dest / 'com / 'example / 'tutorial

  def workspaceTest[T](m: TestUtil.BaseModule)(t: TestEvaluator => T)
                      (implicit tp: TestPath): T = {
    val eval = new TestEvaluator(m)
    os.remove.all(m.millSourcePath)
    println(m.millSourcePath)
    os.remove.all(eval.outPath)
    println(eval.outPath)
    os.makeDir.all(m.millSourcePath / 'core / 'protobuf)
    os.copy(resourcePath, m.millSourcePath / 'core / 'protobuf / 'tutorial)
    t(eval)
  }

  def compiledSourcefiles: Seq[os.RelPath] = Seq[os.RelPath](
    os.rel / "AddressBook.scala",
    os.rel / "Person.scala",
    os.rel / "TutorialProto.scala",
    os.rel / "Include.scala",
    os.rel / "IncludeProto.scala"
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

//     'compileScalaPB - {
      // Broken in Travis due to
      // protoc-jar: caught exception, retrying: java.io.IOException:
      // Cannot run program "/dev/null": error=13, Permission denied

//       'calledDirectly - workspaceTest(Tutorial) { eval =>
//         val Right((result, evalCount)) = eval.apply(Tutorial.core.compileScalaPB)

//         val outPath = protobufOutPath(eval)

//         val outputFiles = os.walk(result.path).filter(os.isFile)

//         val expectedSourcefiles = compiledSourcefiles.map(outPath / _)

//         assert(
//           result.path == eval.outPath / 'core / 'compileScalaPB / 'dest,
//           outputFiles.nonEmpty,
//           outputFiles.forall(expectedSourcefiles.contains),
//           outputFiles.size == 5,
//           evalCount > 0
//         )

//         // don't recompile if nothing changed
//         val Right((_, unchangedEvalCount)) = eval.apply(Tutorial.core.compileScalaPB)

//         assert(unchangedEvalCount == 0)
//       }

      // This throws a NullPointerException in coursier somewhere
      //
      // 'triggeredByScalaCompile - workspaceTest(Tutorial) { eval =>
      //   val Right((_, evalCount)) = eval.apply(Tutorial.core.compile)

      //   val outPath = protobufOutPath(eval)

      //   val outputFiles = os.walk(outPath).filter(_.isFile)

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
//     }

    'useExternalProtocCompiler - {
      /* This ensure that the `scalaPBProtocPath` is properly used.
       * As the given path is incorrect, the compilation should fail.
       */
      'calledWithWrongProtocFile - workspaceTest(TutorialWithProtoc) { eval =>
        val result = eval.apply(TutorialWithProtoc.core.compileScalaPB)
        assert(result.isLeft)
      }
    }

    'compilationArgs - {
      'calledWithCustomArgs - workspaceTest(TutorialWithCustomArgs) { eval =>
        val result = eval.apply(TutorialWithCustomArgs.core.compilationArgsScalaPB)
        result match {
          case Right((seq, _)) =>
            val args = seq.flatten
            assert(args.head.exists(_.contains("--zio_out")))
          case _ => assert(false)
        }
      }
    }
  }
}
