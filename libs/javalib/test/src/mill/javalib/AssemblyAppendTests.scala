package mill.javalib

import mill.*
import mill.api.Task
import mill.testkit.UnitTester
import utest.*

import java.util.jar.JarFile
import scala.util.Using

object AssemblyAppendTests extends TestSuite with AssemblyTestUtils {
  def tests: Tests = Tests {
    def checkAppendMulti[M <: mill.testkit.TestRootModule](
        module: M,
        task: Task.Simple[PathRef]
    ): Unit = UnitTester(
      module,
      sourceRoot = assemblyMultiResourcePath
    ).scoped { eval =>
      val Right(result) = eval.apply(task).runtimeChecked

      Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
        assert(jarEntries(jarFile).contains("reference.conf"))

        val referenceContent = readFileFromJar(jarFile, "reference.conf")

        assert(
          // reference config from core module
          referenceContent.contains("Core Reference Config File"),
          // reference config from model module
          referenceContent.contains("Model Reference Config File"),
          // concatenated content
          referenceContent.contains("bar.baz=hello"),
          referenceContent.contains("foo.bar=2")
        )
      }
    }

    def checkAppendWithSeparator[M <: mill.testkit.TestRootModule](
        module: M,
        task: Task.Simple[PathRef]
    ): Unit = UnitTester(
      module,
      sourceRoot = assemblyMultiResourcePath
    ).scoped { eval =>
      val Right(result) = eval.apply(task).runtimeChecked

      Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
        assert(jarEntries(jarFile).contains("without-new-line.conf"))

        val result = readFileFromJar(jarFile, "without-new-line.conf").split('\n').toSet
        val expected = Set("without-new-line.first=first", "without-new-line.second=second")
        assert(result == expected)
      }
    }

    test("appendMultiModule") - checkAppendMulti(
      HelloJavaMultiAppend,
      HelloJavaMultiAppend.core.assembly
    )
    test("appendPatternMultiModule") - checkAppendMulti(
      HelloJavaMultiAppendPattern,
      HelloJavaMultiAppendPattern.core.assembly
    )
    test("appendPatternMultiModuleWithSeparator") - checkAppendWithSeparator(
      HelloJavaMultiAppendByPatternWithSeparator,
      HelloJavaMultiAppendByPatternWithSeparator.core.assembly
    )

  }
}
