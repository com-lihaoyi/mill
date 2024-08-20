package mill.contrib.scalapblib

import mill._
import mill.api.PathRef
import mill.testkit.UnitTester
import mill.testkit.TestBaseModule
import utest.framework.TestPath
import utest.{TestSuite, Tests, assert, _}

object TutorialTests extends TestSuite {
  val testScalaPbVersion = "0.11.7"

  trait TutorialBase extends TestBaseModule

  trait TutorialModule extends ScalaPBModule {
    def scalaVersion = sys.props.getOrElse("TEST_SCALA_2_12_VERSION", ???)
    def scalaPBVersion = testScalaPbVersion
    def scalaPBFlatPackage = true
    def scalaPBIncludePath = Seq(scalaPBUnpackProto())
  }

  object Tutorial extends TutorialBase {

    object core extends TutorialModule {
      override def scalaPBVersion = testScalaPbVersion
    }
  }

  object TutorialWithProtoc extends TutorialBase {
    object core extends TutorialModule {
      override def scalaPBProtocPath = Some("/dev/null")
    }
  }

  object TutorialWithAdditionalArgs extends TutorialBase {
    object core extends TutorialModule {
      override def scalaPBAdditionalArgs = T {
        Seq(
          "--additional-test=..."
        )
      }
    }
  }

  object TutorialWithSpecificSources extends TutorialBase {
    object core extends TutorialModule {
      override def scalaPBSources: T[Seq[PathRef]] = T.sources {
        millSourcePath / "protobuf" / "tutorial" / "Tutorial.proto"
      }

      override def scalaPBSearchDeps = true
      override def scalaPBIncludePath = Seq(
        PathRef(millSourcePath / "protobuf" / "tutorial")
      )
    }
  }

  val resourcePath: os.Path = os.pwd / "contrib" / "scalapblib" / "test" / "protobuf" / "tutorial"

  def protobufOutPath(eval: UnitTester): os.Path =
    eval.outPath / "core" / "compileScalaPB.dest" / "com" / "example" / "tutorial"

  def workspaceTest[T](m: mill.testkit.TestBaseModule)(t: UnitTester => T)(implicit
                                                                           tp: TestPath
  ): T = {
    val eval = new UnitTester(m)
    os.remove.all(m.millSourcePath)
    println(m.millSourcePath)
    os.remove.all(eval.outPath)
    println(eval.outPath)
    os.makeDir.all(m.millSourcePath / "core" / "protobuf")
    os.copy(resourcePath, m.millSourcePath / "core" / "protobuf" / "tutorial")
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
    test("scalapbVersion") {

      test("fromBuild") - workspaceTest(Tutorial) { eval =>
        val Right(result) = eval.apply(Tutorial.core.scalaPBVersion)

        assert(
          result.value == testScalaPbVersion,
          result.evalCount > 0
        )
      }
    }

    test("compileScalaPB") {
      test("calledDirectly") - workspaceTest(Tutorial) { eval =>
        val Right(result) = eval.apply(Tutorial.core.compileScalaPB)

        val outPath = protobufOutPath(eval)

        val outputFiles = os.walk(result.value.path).filter(os.isFile)

        val expectedSourcefiles = compiledSourcefiles.map(outPath / _)

        assert(
          result.value.path == eval.outPath / "core" / "compileScalaPB.dest",
          outputFiles.nonEmpty,
          outputFiles.forall(expectedSourcefiles.contains),
          outputFiles.size == 5,
          result.evalCount > 0
        )

        // don't recompile if nothing changed
        val Right(result2) = eval.apply(Tutorial.core.compileScalaPB)

        assert(result2.evalCount == 0)
      }

      test("calledWithSpecificFile") - workspaceTest(TutorialWithSpecificSources) { eval =>
        val Right(result) = eval.apply(TutorialWithSpecificSources.core.compileScalaPB)

        val outPath = protobufOutPath(eval)

        val outputFiles = os.walk(result.value.path).filter(os.isFile)

        val expectedSourcefiles = Seq[os.RelPath](
          os.rel / "AddressBook.scala",
          os.rel / "Person.scala",
          os.rel / "TutorialProto.scala",
          os.rel / "IncludeProto.scala"
        ).map(outPath / _)

        assert(
          result.value.path == eval.outPath / "core" / "compileScalaPB.dest",
          outputFiles.nonEmpty,
          outputFiles.forall(expectedSourcefiles.contains),
          outputFiles.size == 3,
          result.evalCount > 0
        )

        // don't recompile if nothing changed
        val Right(result2) = eval.apply(Tutorial.core.compileScalaPB)

        assert(result2.evalCount == 0)
      }

//      // This throws a NullPointerException in coursier somewhere
//      //
//      // test("triggeredByScalaCompile") -workspaceTest(Tutorial) { eval =>
//      //   val Right((_, evalCount)) = eval.apply(Tutorial.core.compile)
//
//      //   val outPath = protobufOutPath(eval)
//
//      //   val outputFiles = os.walk(outPath).filter(_.isFile)
//
//      //   val expectedSourcefiles = compiledSourcefiles.map(outPath / _)
//
//      //   assert(
//      //     outputFiles.nonEmpty,
//      //     outputFiles.forall(expectedSourcefiles.contains),
//      //     outputFiles.size == 3,
//      //     evalCount > 0
//      //   )
//
//      //   // don't recompile if nothing changed
//      //   val Right((_, unchangedEvalCount)) = eval.apply(Tutorial.core.compile)
//
//      //   assert(unchangedEvalCount == 0)
//      // }
//     }
    }

    test("useExternalProtocCompiler") {
      /* This ensure that the `scalaPBProtocPath` is properly used.
       * As the given path is incorrect, the compilation should fail.
       */
      test("calledWithWrongProtocFile") - workspaceTest(TutorialWithProtoc) { eval =>
        val result = eval.apply(TutorialWithProtoc.core.compileScalaPB)
        assert(result.isLeft)
      }
    }

    test("compilationArgs") {
      test("calledWithAdditionalArgs") - workspaceTest(TutorialWithAdditionalArgs) {
        eval =>
          val result =
            eval.apply(TutorialWithAdditionalArgs.core.scalaPBCompileOptions)
          result match {
            case Right(result) =>
              assert(result.value.exists(_.contains("--additional-test=...")))
            case _ => assert(false)
          }
      }
    }
  }
}
