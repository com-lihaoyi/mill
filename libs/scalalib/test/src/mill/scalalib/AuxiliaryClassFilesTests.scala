package mill.scalalib

import mill.*
import mill.api.Discover
import mill.testkit.{TestRootModule, UnitTester}
import utest.*

// Regress test for issue https://github.com/com-lihaoyi/mill/issues/1901
// Tests that auxiliary class files (.tasty) are deleted together with companion class files
object AuxiliaryClassFilesTests extends TestSuite {

  val scala3Version = sys.props.getOrElse("TEST_SCALA_3_3_VERSION", ???)

  object AuxiliaryClassFiles extends TestRootModule {
    object app extends ScalaModule {
      def scalaVersion = scala3Version
      // force this on for testing even though there's only one file
      def zincIncrementalCompilation = true
    }

    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "auxiliary-class-files"

  def tests: Tests = Tests {
    test("tastyFilesDeletedWithCompanionClassFiles") - UnitTester(
      AuxiliaryClassFiles,
      sourceRoot = resourcePath
    ).scoped { eval =>
      val Right(_) = eval.apply(AuxiliaryClassFiles.app.compile).runtimeChecked

      val classes = eval.outPath / "app/compile.dest/classes"
      val firstRun = os.list(classes).map(_.last).sorted

      assert(firstRun == Seq("foo$.class", "foo.class", "foo.tasty"))

      // Remove the source file
      os.remove(eval.evaluator.workspace / "app/src/foo.scala")

      val Right(_) = eval.apply(AuxiliaryClassFiles.app.compile).runtimeChecked

      val secondRun = os.list(classes).map(_.last)

      // All files should be deleted
      assert(secondRun == Seq.empty)
    }

    test("compilationFailsWhenDeletingClassUsedByOtherFiles") - UnitTester(
      AuxiliaryClassFiles,
      sourceRoot = resourcePath
    ).scoped { eval =>
      // Add a file that depends on foo
      os.write(
        eval.evaluator.workspace / "app/src/bar.scala",
        "object bar { println(foo) }"
      )

      val Right(_) = eval.apply(AuxiliaryClassFiles.app.compile).runtimeChecked

      val classes = eval.outPath / "app/compile.dest/classes"
      val firstRun = os.list(classes).map(_.last).sorted

      assert(firstRun == Seq(
        "bar$.class",
        "bar.class",
        "bar.tasty",
        "foo$.class",
        "foo.class",
        "foo.tasty"
      ))

      // Remove foo.scala (bar depends on it)
      os.remove(eval.evaluator.workspace / "app/src/foo.scala")

      val Left(_) = eval.apply(AuxiliaryClassFiles.app.compile).runtimeChecked

      val secondRun = os.list(classes).map(_.last)

      // All class files should be deleted on failed compilation
      assert(secondRun == Seq.empty)
    }
  }
}
