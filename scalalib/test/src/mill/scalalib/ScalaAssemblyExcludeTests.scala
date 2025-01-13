package mill.scalalib

import mill._
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

import java.util.jar.JarFile
import scala.util.Using
import HelloWorldTests._
object ScalaAssemblyExcludeTests extends TestSuite with ScalaAssemblyTestUtils {
  def tests: Tests = Tests {
    def checkExclude[M <: mill.testkit.TestBaseModule](
        module: M,
        target: Target[PathRef],
        resourcePath: os.Path = resourcePath
    ) = UnitTester(module, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(target)

      Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
        assert(!jarEntries(jarFile).contains("reference.conf"))
      }
    }

    test("excludeWithDeps") - checkExclude(
      HelloWorldAkkaHttpExclude,
      HelloWorldAkkaHttpExclude.core.assembly
    )
    test("excludeMultiModule") - checkExclude(
      HelloWorldMultiExclude,
      HelloWorldMultiExclude.core.assembly,
      resourcePath = helloWorldMultiResourcePath
    )
    test("excludePatternWithDeps") - checkExclude(
      HelloWorldAkkaHttpExcludePattern,
      HelloWorldAkkaHttpExcludePattern.core.assembly
    )
    test("excludePatternMultiModule") - checkExclude(
      HelloWorldMultiExcludePattern,
      HelloWorldMultiExcludePattern.core.assembly,
      resourcePath = helloWorldMultiResourcePath
    )

    def checkRelocate[M <: mill.testkit.TestBaseModule](
        module: M,
        target: Target[PathRef],
        resourcePath: os.Path = resourcePath
    ) = UnitTester(module, resourcePath).scoped { eval =>
      val Right(result) = eval.apply(target)
      Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
        assert(!jarEntries(jarFile).contains("akka/http/scaladsl/model/HttpEntity.class"))
        assert(
          jarEntries(jarFile).contains("shaded/akka/http/scaladsl/model/HttpEntity.class")
        )
      }
    }

    test("relocate") {
      test("withDeps") - checkRelocate(
        HelloWorldAkkaHttpRelocate,
        HelloWorldAkkaHttpRelocate.core.assembly
      )

      test("run") - UnitTester(
        HelloWorldAkkaHttpRelocate,
        sourceRoot = os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-deps"
      ).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldAkkaHttpRelocate.core.runMain("Main"))
        assert(result.evalCount > 0)
      }
    }

    test("writeDownstreamWhenNoRule") {
      test("withDeps") - UnitTester(HelloWorldAkkaHttpNoRules, null).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldAkkaHttpNoRules.core.assembly)

        Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
          assert(jarEntries(jarFile).contains("reference.conf"))

          val referenceContent = readFileFromJar(jarFile, "reference.conf")

          val allOccurrences = Seq(
            referenceContent.contains("akka-http Reference Config File"),
            referenceContent.contains("akka-http-core Reference Config File"),
            referenceContent.contains("Akka Actor Reference Config File"),
            referenceContent.contains("Akka Stream Reference Config File"),
            referenceContent.contains("My application Reference Config File")
          )

          val timesOcccurres = allOccurrences.find(identity).size

          assert(timesOcccurres == 1)
        }
      }

      test("multiModule") - UnitTester(
        HelloWorldMultiNoRules,
        sourceRoot = helloWorldMultiResourcePath
      ).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldMultiNoRules.core.assembly)

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
