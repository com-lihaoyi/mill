package mill.scalalib

import mill._
import mill.testkit.{TestBaseModule, UnitTester}
import utest._

import java.util.jar.JarFile
import scala.util.Using
import HelloWorldTests._
object ScalaAssemblyTests extends TestSuite {

  val akkaHttpDeps = Agg(ivy"com.typesafe.akka::akka-http:10.0.13")

  object HelloWorldAkkaHttpAppend extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Append("reference.conf"))
    }
  }

  object HelloWorldAkkaHttpExclude extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Exclude("reference.conf"))
    }
  }

  object HelloWorldAkkaHttpAppendPattern extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf"))
    }
  }

  object HelloWorldAkkaHttpExcludePattern extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.conf"))
    }
  }

  object HelloWorldAkkaHttpRelocate extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq(Assembly.Rule.Relocate("akka.**", "shaded.akka.@1"))
    }
  }

  object HelloWorldAkkaHttpNoRules extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def ivyDeps = akkaHttpDeps
      override def assemblyRules = Seq.empty
    }
  }

  object HelloWorldMultiAppend extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.Append("reference.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiExclude extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.Exclude("reference.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiAppendPattern extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiAppendByPatternWithSeparator extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.AppendPattern(".*.conf", "\n"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiExcludePattern extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq(Assembly.Rule.ExcludePattern(".*.conf"))
    }
    object model extends HelloWorldModule
  }

  object HelloWorldMultiNoRules extends TestBaseModule {
    object core extends HelloWorldModuleWithMain {
      override def moduleDeps = Seq(model)
      override def assemblyRules = Seq.empty
    }
    object model extends HelloWorldModule
  }

  def tests: Tests = Tests {

    test("assembly") {
      test("assembly") - UnitTester(HelloWorldTests.HelloWorldWithMain, resourcePath).scoped {
        eval =>
          val Right(result) = eval.apply(HelloWorldTests.HelloWorldWithMain.core.assembly)
          assert(
            os.exists(result.value.path),
            result.evalCount > 0
          )
          val jarFile = new JarFile(result.value.path.toIO)
          val entries = jarEntries(jarFile)

          val mainPresent = entries.contains("Main.class")
          assert(mainPresent)
          assert(entries.exists(s => s.contains("scala/Predef.class")))

          val mainClass = jarMainClass(jarFile)
          assert(mainClass.contains("Main"))
      }

      test("assemblyRules") {
        def checkAppend[M <: mill.testkit.TestBaseModule](module: M, target: Target[PathRef]) =
          UnitTester(module, resourcePath).scoped { eval =>
            val Right(result) = eval.apply(target)

            Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
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

        val helloWorldMultiResourcePath =
          os.Path(sys.env("MILL_TEST_RESOURCE_DIR")) / "hello-world-multi"

        def checkAppendMulti[M <: mill.testkit.TestBaseModule](
            module: M,
            target: Target[PathRef]
        ): Unit = UnitTester(
          module,
          sourceRoot = helloWorldMultiResourcePath
        ).scoped { eval =>
          val Right(result) = eval.apply(target)

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

        def checkAppendWithSeparator[M <: mill.testkit.TestBaseModule](
            module: M,
            target: Target[PathRef]
        ): Unit = UnitTester(
          module,
          sourceRoot = helloWorldMultiResourcePath
        ).scoped { eval =>
          val Right(result) = eval.apply(target)

          Using.resource(new JarFile(result.value.path.toIO)) { jarFile =>
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

      test("run") - UnitTester(HelloWorldTests.HelloWorldWithMain, resourcePath).scoped { eval =>
        val Right(result) = eval.apply(HelloWorldTests.HelloWorldWithMain.core.assembly)

        assert(
          os.exists(result.value.path),
          result.evalCount > 0
        )
        val runResult = eval.outPath / "hello-mill"

        os.proc("java", "-jar", result.value.path, runResult).call(cwd = eval.outPath)

        assert(
          os.exists(runResult),
          os.read(runResult) == "hello rockjam, your age is: 25"
        )
      }
    }
  }
}
