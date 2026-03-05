package mill.javalib

import fansi.Str
import mill.api.{Discover, Task}
import mill.testkit.{TestRootModule, UnitTester}
import utest.*
import mill.util.TokenReaders.*

import java.io.{ByteArrayOutputStream, PrintStream}

object JavacOptionsJvmOptionsTests extends TestSuite {

  object JavacOptionsWithDeprecatedRuntimeOptions extends TestRootModule {
    object core extends JavaModule {
      def javacOptions = Seq("-J-Dlegacy.runtime.option=true")
    }
    lazy val millDiscover = Discover[this.type]
  }

  object JavacOptionsAsJvmOptions extends TestRootModule {
    object core extends JavaModule {
      override def jvmOptions = Seq("-Dnew.runtime.option=true")
    }
    lazy val millDiscover = Discover[this.type]
  }

  object SemanticDbWithDeprecatedRuntimeOptions extends TestRootModule {
    object core extends JavaModule {
      def javacOptions = Seq("-J-Dsemanticdb.legacy.runtime=true")
    }
    lazy val millDiscover = Discover[this.type]
  }

  val resourcePath = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-java"

  private def runTaskWithOutput(task: Task[?], module: TestRootModule): String = {
    val errStream = new ByteArrayOutputStream()
    UnitTester(module, resourcePath, errStream = new PrintStream(errStream, true)).scoped { eval =>
      val result = eval.apply(task).runtimeChecked
      assert(result.isRight)
    }
    errStream.toString
  }

  def tests: Tests = Tests {
    test("warnDeprecatedJavacRuntimeOptions") {
      val output = Str(
        runTaskWithOutput(
          JavacOptionsWithDeprecatedRuntimeOptions.core.compile,
          JavacOptionsWithDeprecatedRuntimeOptions
        )
      ).plainText
      assert(
        output.contains("`-J` options in `javacOptions` are deprecated; use `jvmOptions` instead"),
        output.contains("-J-Dlegacy.runtime.option=true")
      )
    }

    test("noWarningWhenUsingJvmOptions") {
      val output = Str(
        runTaskWithOutput(JavacOptionsAsJvmOptions.core.compile, JavacOptionsAsJvmOptions)
      ).plainText
      assert(!output.contains("`-J` options in `javacOptions`"))
    }

    test("warnsInSemanticDbCompilation") {
      val output = Str(
        runTaskWithOutput(
          SemanticDbWithDeprecatedRuntimeOptions.core.semanticDbData,
          SemanticDbWithDeprecatedRuntimeOptions
        )
      ).plainText
      assert(
        output.contains("`-J` options in `javacOptions` are deprecated; use `jvmOptions` instead"),
        output.contains("-J-Dsemanticdb.legacy.runtime=true")
      )
    }
  }
}
