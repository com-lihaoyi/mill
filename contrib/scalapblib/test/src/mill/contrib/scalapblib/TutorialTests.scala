package mill.contrib.scalapblib

import mill.*
import mill.api.PathRef
import mill.constants.Util
import mill.api.Discover
import mill.testkit.UnitTester
import mill.testkit.TestRootModule
import utest.{TestSuite, Tests, assert, *}

object TutorialTests extends TestSuite {
  val testScalaPbVersion = "0.11.7"

  trait TutorialBase extends TestRootModule {
    val core: TutorialModule
  }

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

    lazy val millDiscover = Discover[this.type]
  }

  object TutorialWithProtoc extends TutorialBase {
    object core extends TutorialModule {
      override def scalaPBProtocPath = Some("/dev/null")
    }

    lazy val millDiscover = Discover[this.type]

  }

  object TutorialWithAdditionalArgs extends TutorialBase {
    object core extends TutorialModule {
      override def scalaPBAdditionalArgs = Task {
        Seq(
          "--additional-test=..."
        )
      }
    }
    lazy val millDiscover = Discover[this.type]
  }

  object TutorialWithSpecificSources extends TutorialBase {
    object core extends TutorialModule {
      override def scalaPBSources: T[Seq[PathRef]] = Task.Sources(
        "protobuf/tutorial/Tutorial.proto"
      )

      override def scalaPBSearchDeps = true
      override def scalaPBIncludePath = Task.Sources(
        moduleDir / "protobuf/tutorial"
      )
    }
    lazy val millDiscover = Discover[this.type]
  }

  object TutorialWithScala3Soures extends TutorialBase {
    object core extends TutorialModule {
      override def scalaPBScala3Sources = { true }
    }
    lazy val millDiscover = Discover[this.type]
  }

  object TutorialWithJavaGen extends TutorialBase {
    object core extends TutorialModule {
      override def scalaPBGenerators = Seq(JavaGen)
    }

    lazy val millDiscover = Discover[this.type]
  }

  object TutorialWithScalaAndJavaGen extends TutorialBase {
    object core extends TutorialModule {
      override def scalaPBGenerators = Seq[Generator](ScalaGen, JavaGen)
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath: os.Path = os.Path(sys.env("MILL_TEST_RESOURCE_DIR"))

  def protobufOutPath(eval: UnitTester): os.Path =
    eval.outPath / "core/compileScalaPB.dest/com/example/tutorial"

  def compiledScalaSourcefiles: Seq[os.RelPath] = Seq[os.RelPath](
    os.rel / "AddressBook.scala",
    os.rel / "Person.scala",
    os.rel / "TutorialProto.scala",
    os.rel / "Include.scala",
    os.rel / "IncludeProto.scala"
  )

  def compiledJavaSourcefiles: Seq[os.RelPath] = Seq[os.RelPath](
    os.rel / "AddressBookProtos.java",
    os.rel / "IncludeOuterClass.java"
  )

  // Helper function to test compilation with different generators
  def testCompilation(
    module: TutorialBase,
    expectedFiles: Seq[os.RelPath]
  ): Unit = {
    UnitTester(module, resourcePath).scoped { eval =>
      if (!mill.constants.Util.isWindows) {
        val Right(result) = eval.apply(module.core.compileScalaPB): @unchecked

        val outPath = protobufOutPath(eval)
        val outputFiles = os.walk(result.value.path).filter(os.isFile)
        val expectedSourcefiles = expectedFiles.map(outPath / _)

        assert(
          result.value.path == eval.outPath / "core/compileScalaPB.dest",
          outputFiles.nonEmpty,
          outputFiles.forall(expectedSourcefiles.contains),
          outputFiles.size == outputFiles.size,
          result.evalCount > 0
        )

        // don't recompile if nothing changed
        val Right(result2) = eval.apply(module.core.compileScalaPB): @unchecked
        assert(result2.evalCount == 0)
      }
    }
  }

  def tests: Tests = Tests {
    test("scalapbVersion") {

      test("fromBuild") - UnitTester(Tutorial, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(Tutorial.core.scalaPBVersion): @unchecked

        assert(
          result.value == testScalaPbVersion,
          result.evalCount > 0
        )
      }
    }

    test("compileScalaPB") {
      test("scalaGen") - testCompilation(Tutorial, compiledScalaSourcefiles)
      test("javaGen") - testCompilation(TutorialWithJavaGen, compiledJavaSourcefiles)
      test("scalaAndJavaGen") - testCompilation(TutorialWithScalaAndJavaGen, compiledScalaSourcefiles ++ compiledJavaSourcefiles)

      test("calledWithSpecificFile") - UnitTester(
        TutorialWithSpecificSources,
        resourcePath
      ).scoped { eval =>
        if (!Util.isWindows) {
          val Right(result) =
            eval.apply(TutorialWithSpecificSources.core.compileScalaPB): @unchecked

          val outPath = protobufOutPath(eval)

          val outputFiles = os.walk(result.value.path).filter(os.isFile)

          val expectedSourcefiles = Seq[os.RelPath](
            os.rel / "AddressBook.scala",
            os.rel / "Person.scala",
            os.rel / "TutorialProto.scala",
            os.rel / "IncludeProto.scala"
          ).map(outPath / _)

          assert(
            result.value.path == eval.outPath / "core/compileScalaPB.dest",
            outputFiles.nonEmpty,
            outputFiles.forall(expectedSourcefiles.contains),
            outputFiles.size == 3,
            result.evalCount > 0
          )

          // don't recompile if nothing changed
          val Right(result2) = eval.apply(Tutorial.core.compileScalaPB): @unchecked

          assert(result2.evalCount == 0)
        }
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
//      //   val expectedSourcefiles = compiledScalaSourcefiles.map(outPath / _)
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
      /* This ensures that the `scalaPBProtocPath` is properly used.
       * As the given path is incorrect, the compilation should fail.
       */
      test("calledWithWrongProtocFile") - UnitTester(TutorialWithProtoc, resourcePath).scoped {
        eval =>
          val result = eval.apply(TutorialWithProtoc.core.compileScalaPB)
          assert(result.isLeft)
      }
    }

    test("calledWithScala3Sources") - UnitTester(TutorialWithScala3Soures, resourcePath).scoped {
      eval =>
        val result = eval.apply(Tutorial.core.compileScalaPB)
        assert(result.isRight)
    }

    test("compilationArgs") {
      test("calledWithAdditionalArgs") - UnitTester(
        TutorialWithAdditionalArgs,
        resourcePath
      ).scoped { eval =>
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
