package mill.scalalib

import mill.*
import mill.api.Task
import mill.scalalib.HelloWorldTests.*
import mill.testkit.UnitTester
import utest.*

import java.util.jar.JarFile
import scala.util.Using

object ScalaAssemblyAppendTests extends TestSuite with ScalaAssemblyTestUtils {
  def tests: Tests = Tests {
    def checkAppend[M <: mill.testkit.TestRootModule](module: M, task: Task.Simple[PathRef]) =
      UnitTester(module, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(task): @unchecked

        Using.resource(JarFile(result.value.path.toIO)) { jarFile =>
          assert(jarEntries(jarFile).contains("reference.conf"))

          val referenceContent = readFileFromJar(jarFile, "reference.conf")

          assert(
            // akka modules configs are present
            referenceContent.contains("akka-http Reference Config File"),
            referenceContent.contains("akka-http-core Reference Config File"),
            referenceContent.contains("Akka Actor Reference Config File"),
            referenceContent.contains("Akka Stream Reference Config File"),
            // our application config is present too
            referenceContent.contains("My application Reference Config File"),
            referenceContent.contains(
              """akka.http.client.user-agent-header="hello-world-client""""
            )
          )
        }
      }

    def checkAppendMulti[M <: mill.testkit.TestRootModule](
        module: M,
        task: Task.Simple[PathRef]
    ): Unit = UnitTester(
      module,
      sourceRoot = helloWorldMultiResourcePath
    ).scoped { eval =>
      val Right(result) = eval.apply(task): @unchecked

      Using.resource(JarFile(result.value.path.toIO)) { jarFile =>
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
      sourceRoot = helloWorldMultiResourcePath
    ).scoped { eval =>
      val Right(result) = eval.apply(task): @unchecked

      Using.resource(JarFile(result.value.path.toIO)) { jarFile =>
        assert(jarEntries(jarFile).contains("without-new-line.conf"))

        val result = readFileFromJar(jarFile, "without-new-line.conf").split('\n').toSet
        val expected = Set("without-new-line.first=first", "without-new-line.second=second")
        assert(result == expected)
      }
    }

    test("appendWithDeps") - checkAppend(
      HelloWorldAkkaHttpAppend,
      HelloWorldAkkaHttpAppend.core.assembly
    )
    test("appendMultiModule") - checkAppendMulti(
      HelloWorldMultiAppend,
      HelloWorldMultiAppend.core.assembly
    )
    test("appendPatternWithDeps") - checkAppend(
      HelloWorldAkkaHttpAppendPattern,
      HelloWorldAkkaHttpAppendPattern.core.assembly
    )
    test("appendPatternMultiModule") - checkAppendMulti(
      HelloWorldMultiAppendPattern,
      HelloWorldMultiAppendPattern.core.assembly
    )
    test("appendPatternMultiModuleWithSeparator") - checkAppendWithSeparator(
      HelloWorldMultiAppendByPatternWithSeparator,
      HelloWorldMultiAppendByPatternWithSeparator.core.assembly
    )

  }
}
