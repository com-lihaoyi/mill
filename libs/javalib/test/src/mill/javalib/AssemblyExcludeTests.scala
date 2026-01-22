package mill.javalib

import mill.*
import mill.api.Task
import mill.testkit.UnitTester
import utest.*

import java.util.jar.JarFile
import scala.util.Using

object AssemblyExcludeTests extends TestSuite with AssemblyTestUtils {
  def tests: Tests = Tests {
    def checkExclude[M <: mill.testkit.TestRootModule](
        module: M,
        task: Task.Simple[PathRef],
        resourcePath: os.Path = assemblyMultiResourcePath
    ) = UnitTester(module, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(task): @unchecked

      Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
        assert(!jarEntries(jarFile).contains("reference.conf"))
      }
    }

    test("excludeMultiModule") - checkExclude(
      HelloJavaMultiExclude,
      HelloJavaMultiExclude.core.assembly
    )
    test("excludePatternMultiModule") - checkExclude(
      HelloJavaMultiExcludePattern,
      HelloJavaMultiExcludePattern.core.assembly
    )

    test("writeDownstreamWhenNoRule") {
      test("multiModule") - UnitTester(
        HelloJavaMultiNoRules,
        sourceRoot = assemblyMultiResourcePath
      ).scoped { eval =>
        val Right(result) = eval.apply(HelloJavaMultiNoRules.core.assembly): @unchecked

        Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
          assert(jarEntries(jarFile).contains("reference.conf"))

          val referenceContent = readFileFromJar(jarFile, "reference.conf")

          assert(
            !referenceContent.contains("Model Reference Config File"),
            !referenceContent.contains("foo.bar=2"),
            referenceContent.contains("Core Reference Config File"),
            referenceContent.contains("bar.baz=hello")
          )
        }
      }
    }
  }

}
